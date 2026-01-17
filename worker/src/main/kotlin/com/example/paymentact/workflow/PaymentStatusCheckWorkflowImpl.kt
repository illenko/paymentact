package com.example.paymentact.workflow

import com.example.paymentact.activity.ElasticsearchActivities
import com.example.paymentact.model.CheckStatusResult
import com.example.paymentact.model.FailedChunk
import com.example.paymentact.model.GatewayInfo
import com.example.paymentact.model.GatewayResult
import com.example.paymentact.model.ProgressInfo
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Async
import io.temporal.workflow.ChildWorkflowOptions
import io.temporal.workflow.Promise
import io.temporal.workflow.Workflow
import java.time.Duration

class PaymentStatusCheckWorkflowImpl : PaymentStatusCheckWorkflow {

    private val logger = Workflow.getLogger(PaymentStatusCheckWorkflowImpl::class.java)

    // Progress tracking
    private var totalPayments: Int = 0
    private var gatewaysIdentified: Int = 0
    private var chunksTotal: Int = 0
    private var chunksCompleted: Int = 0
    private var chunksFailed: Int = 0

    // Configuration - these would ideally come from workflow input or side effect
    private val maxParallelEsQueries = 10
    private val maxPaymentsPerChunk = 5

    private val esActivities = Workflow.newActivityStub(
        ElasticsearchActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setMaximumAttempts(3)
                    .setInitialInterval(Duration.ofSeconds(1))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofSeconds(10))
                    .build()
            )
            .build()
    )

    override fun checkPaymentStatuses(paymentIds: List<String>): CheckStatusResult {
        logger.info("Starting payment status check for {} payments", paymentIds.size)
        totalPayments = paymentIds.size

        // Step 1: ES Lookups (parallel with limited concurrency)
        val (gatewayByPayment, lookupFailed) = lookupGatewaysParallel(paymentIds)
        gatewaysIdentified = gatewayByPayment.values.distinct().size

        logger.info(
            "Gateway lookup complete: {} successful, {} failed, {} unique gateways",
            gatewayByPayment.size, lookupFailed.size, gatewaysIdentified
        )

        // Step 2: Group by gateway and chunk
        val paymentsByGateway = gatewayByPayment.entries
            .groupBy({ it.value }, { it.key })

        val chunksByGateway = paymentsByGateway.mapValues { (_, payments) ->
            payments.chunked(maxPaymentsPerChunk)
        }

        chunksTotal = chunksByGateway.values.sumOf { it.size }
        logger.info("Created {} chunks across {} gateways", chunksTotal, chunksByGateway.size)

        // Step 3: Spawn child workflows per gateway (parallel across gateways)
        val gatewayResults = processGatewaysParallel(chunksByGateway)

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
            chunksCompleted += result.failedChunks.size +
                    (if (result.successfulPaymentIds.isNotEmpty()) 1 else 0)
        }

        // Recalculate completed chunks properly
        chunksCompleted = chunksTotal

        logger.info(
            "Payment status check complete: {} gateways successful, {} gateways with failures, {} lookup failures",
            successful.size, failed.size, lookupFailed.size
        )

        return CheckStatusResult(
            successful = successful,
            failed = failed,
            gatewayLookupFailed = lookupFailed
        )
    }

    private fun lookupGatewaysParallel(paymentIds: List<String>): Pair<Map<String, String>, List<String>> {
        val gatewayByPayment = mutableMapOf<String, String>()
        val lookupFailed = mutableListOf<String>()

        // Process in batches to limit concurrency
        paymentIds.chunked(maxParallelEsQueries).forEach { batch ->
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
                }.onFailure {
                    logger.warn("Failed to lookup gateway for payment {}: {}", paymentId, it.message)
                    lookupFailed.add(paymentId)
                }
            }
        }

        return Pair(gatewayByPayment, lookupFailed)
    }

    private fun processGatewaysParallel(chunksByGateway: Map<String, List<List<String>>>): List<GatewayResult> {
        val childWorkflowOptions = ChildWorkflowOptions.newBuilder()
            .setWorkflowId("") // Will be set per gateway
            .build()

        val promises = mutableListOf<Promise<GatewayResult>>()
        val gateways = mutableListOf<String>()

        for ((gateway, chunks) in chunksByGateway) {
            gateways.add(gateway)

            val childOptions = ChildWorkflowOptions.newBuilder()
                .setWorkflowId("${Workflow.getInfo().workflowId}-gateway-$gateway")
                .build()

            val childWorkflow = Workflow.newChildWorkflowStub(
                GatewayWorkflow::class.java,
                childOptions
            )

            val promise = Async.function { childWorkflow.processGateway(gateway, chunks) }
            promises.add(promise)
        }

        // Wait for all child workflows to complete
        return promises.mapIndexed { index, promise ->
            try {
                promise.get()
            } catch (e: Exception) {
                logger.error("Child workflow for gateway {} failed: {}", gateways[index], e.message)
                // Return a failed result for this gateway
                GatewayResult(
                    gateway = gateways[index],
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
            chunksFailed = chunksFailed
        )
    }
}
