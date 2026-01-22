package com.example.paymentact.workflow

import com.example.paymentact.activity.ElasticsearchActivities
import com.example.paymentact.model.CheckStatusResult
import com.example.paymentact.model.FailedChunk
import com.example.paymentact.model.GatewayInfo
import com.example.paymentact.model.GatewayResult
import com.example.paymentact.model.PaymentStatusCheckInput
import com.example.paymentact.model.ProgressInfo
import com.example.paymentact.model.WorkflowConfig
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Async
import io.temporal.workflow.ChildWorkflowOptions
import io.temporal.workflow.Promise
import io.temporal.workflow.QueryMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

@WorkflowInterface
interface PaymentStatusCheckWorkflow {
    @WorkflowMethod
    fun checkPaymentStatuses(input: PaymentStatusCheckInput): CheckStatusResult

    @QueryMethod
    fun getProgress(): ProgressInfo
}

class PaymentStatusCheckWorkflowImpl : PaymentStatusCheckWorkflow {

    private val logger = Workflow.getLogger(PaymentStatusCheckWorkflowImpl::class.java)

    // Progress tracking
    private var totalPayments: Int = 0
    private var gatewaysIdentified: Int = 0
    private var chunksTotal: Int = 0
    private var chunksCompleted: Int = 0
    private var chunksFailed: Int = 0
    private var currentPhase: String = "INITIALIZING"

    // Configuration - set from input
    private var config: WorkflowConfig = WorkflowConfig()

    private lateinit var esActivities: ElasticsearchActivities

    private fun initializeActivities() {
        esActivities = Workflow.newActivityStub(
            ElasticsearchActivities::class.java,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(config.activityTimeoutSeconds))
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setMaximumAttempts(config.maxRetryAttempts)
                        .setInitialInterval(Duration.ofSeconds(1))
                        .setBackoffCoefficient(2.0)
                        .setMaximumInterval(Duration.ofSeconds(10))
                        .build()
                )
                .build()
        )
    }

    override fun checkPaymentStatuses(input: PaymentStatusCheckInput): CheckStatusResult {
        val workflowId = Workflow.getInfo().workflowId
        config = input.config
        initializeActivities()

        logger.info("[workflowId={}] Starting payment status check for {} payments with config: maxParallelEsQueries={}, maxPaymentsPerChunk={}",
            workflowId, input.paymentIds.size, config.maxParallelEsQueries, config.maxPaymentsPerChunk)

        totalPayments = input.paymentIds.size
        currentPhase = "ES_LOOKUP"

        // Step 1: ES Lookups (parallel with limited concurrency)
        val (gatewayByPayment, lookupFailed) = lookupGatewaysParallel(input.paymentIds, workflowId)
        gatewaysIdentified = gatewayByPayment.values.distinct().size

        logger.info("[workflowId={}] Gateway lookup complete: {} successful, {} failed, {} unique gateways",
            workflowId, gatewayByPayment.size, lookupFailed.size, gatewaysIdentified)

        currentPhase = "GATEWAY_PROCESSING"

        // Step 2: Group by gateway and chunk
        val paymentsByGateway = gatewayByPayment.entries
            .groupBy({ it.value }, { it.key })

        val chunksByGateway = paymentsByGateway.mapValues { (_, payments) ->
            payments.chunked(config.maxPaymentsPerChunk)
        }

        chunksTotal = chunksByGateway.values.sumOf { it.size }
        logger.info("[workflowId={}] Created {} chunks across {} gateways", workflowId, chunksTotal, chunksByGateway.size)

        // Step 3: Spawn child workflows per gateway (parallel across gateways)
        val gatewayResults = processGatewaysParallel(chunksByGateway, workflowId)

        currentPhase = "AGGREGATING"

        // Step 4: Aggregate results
        val successful = mutableMapOf<String, List<String>>()
        val failed = mutableMapOf<String, List<FailedChunk>>()

        for (result in gatewayResults) {
            if (result.successfulPaymentIds.isNotEmpty()) {
                successful[result.gateway] = result.successfulPaymentIds
            }
            if (result.failedChunks.isNotEmpty()) {
                failed[result.gateway] = result.failedChunks
                chunksFailed += result.failedChunks.size
            }
        }

        chunksCompleted = chunksTotal
        currentPhase = "COMPLETED"

        logger.info("[workflowId={}] Payment status check complete: {} gateways successful, {} gateways with failures, {} lookup failures",
            workflowId, successful.size, failed.size, lookupFailed.size)

        return CheckStatusResult(
            successful = successful,
            failed = failed,
            gatewayLookupFailed = lookupFailed
        )
    }

    private fun lookupGatewaysParallel(paymentIds: List<String>, workflowId: String): Pair<Map<String, String>, List<String>> {
        val gatewayByPayment = mutableMapOf<String, String>()
        val lookupFailed = mutableListOf<String>()

        val batches = paymentIds.chunked(config.maxParallelEsQueries)
        logger.info("[workflowId={}] Processing {} ES lookup batches", workflowId, batches.size)

        batches.forEachIndexed { batchIndex, batch ->
            val promises = batch.map { paymentId ->
                Async.function {
                    try {
                        val info = esActivities.getGatewayForPayment(paymentId)
                        Result.success(info)
                    } catch (e: Exception) {
                        Result.failure<GatewayInfo>(e)
                    }
                }
            }

            // Wait for all promises in this batch
            promises.forEachIndexed { index, promise ->
                val paymentId = batch[index]
                val result = promise.get()

                result.onSuccess { info ->
                    gatewayByPayment[info.paymentId] = info.gatewayName
                }.onFailure { e ->
                    logger.warn("[workflowId={}] Failed to lookup gateway for payment {}: {}",
                        workflowId, paymentId, e.message)
                    lookupFailed.add(paymentId)
                }
            }

            logger.debug("[workflowId={}] Completed ES lookup batch {}/{}", workflowId, batchIndex + 1, batches.size)
        }

        return Pair(gatewayByPayment, lookupFailed)
    }

    private fun processGatewaysParallel(chunksByGateway: Map<String, List<List<String>>>, workflowId: String): List<GatewayResult> {
        val promises = mutableListOf<Promise<GatewayResult>>()
        val gateways = mutableListOf<String>()

        for ((gateway, chunks) in chunksByGateway) {
            gateways.add(gateway)

            val childWorkflowId = "$workflowId-gateway-$gateway"
            val childOptions = ChildWorkflowOptions.newBuilder()
                .setWorkflowId(childWorkflowId)
                .build()

            val childWorkflow = Workflow.newChildWorkflowStub(
                GatewayWorkflow::class.java,
                childOptions
            )

            logger.info("[workflowId={}] Spawning child workflow {} for gateway {} with {} chunks",
                workflowId, childWorkflowId, gateway, chunks.size)

            val promise = Async.function { childWorkflow.processGateway(gateway, chunks) }
            promises.add(promise)
        }

        // Wait for all child workflows to complete
        return promises.mapIndexed { index, promise ->
            val gateway = gateways[index]
            try {
                val result = promise.get()
                logger.info("[workflowId={}] Child workflow for gateway {} completed: {} successful, {} failed chunks",
                    workflowId, gateway, result.successfulPaymentIds.size, result.failedChunks.size)
                result
            } catch (e: Exception) {
                logger.error("[workflowId={}] Child workflow for gateway {} failed: {}",
                    workflowId, gateway, e.message)
                // Return a failed result for this gateway
                GatewayResult(
                    gateway = gateway,
                    successfulPaymentIds = emptyList(),
                    failedChunks = listOf(
                        FailedChunk(
                            chunkIndex = -1,
                            paymentIds = emptyList(),
                            error = "Child workflow failed: ${e.message}",
                            stage = "WORKFLOW"
                        )
                    )
                )
            }
        }
    }

    override fun getProgress(): ProgressInfo {
        return ProgressInfo(
            totalPayments = totalPayments,
            gatewaysIdentified = gatewaysIdentified,
            chunksTotal = chunksTotal,
            chunksCompleted = chunksCompleted,
            chunksFailed = chunksFailed,
            currentPhase = currentPhase
        )
    }
}
