package com.example.paymentact.workflow

import com.example.paymentact.activity.PaymentGatewayActivities
import com.example.paymentact.model.FailedChunk
import com.example.paymentact.model.GatewayResult
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration

class GatewayWorkflowImpl : GatewayWorkflow {

    private val logger = Workflow.getLogger(GatewayWorkflowImpl::class.java)

    private var totalChunks: Int = 0
    private var completedChunks: Int = 0
    private var currentChunkIndex: Int = 0

    private val activities = Workflow.newActivityStub(
        PaymentGatewayActivities::class.java,
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

    override fun processGateway(gateway: String, chunks: List<List<String>>): GatewayResult {
        val workflowId = Workflow.getInfo().workflowId
        logger.info("[workflowId={}, gateway={}] Processing {} chunks",
            workflowId, gateway, chunks.size)

        totalChunks = chunks.size
        val successfulPaymentIds = mutableListOf<String>()
        val failedChunks = mutableListOf<FailedChunk>()

        chunks.forEachIndexed { chunkIndex, chunkPaymentIds ->
            currentChunkIndex = chunkIndex
            logger.info("[workflowId={}, gateway={}] Processing chunk {}/{}: {} payments",
                workflowId, gateway, chunkIndex + 1, chunks.size, chunkPaymentIds.size)

            try {
                // Step A: Call IDB Facade (batch)
                logger.debug("[workflowId={}, gateway={}] Calling IDB facade for chunk {}",
                    workflowId, gateway, chunkIndex)
                activities.callIdbFacade(gateway, chunkPaymentIds)

                // Step B: Call PGI Gateway (sequential, one by one)
                val pgiSuccessful = mutableListOf<String>()
                val pgiFailed = mutableListOf<String>()

                for (paymentId in chunkPaymentIds) {
                    try {
                        activities.callPgiGateway(gateway, paymentId)
                        pgiSuccessful.add(paymentId)
                    } catch (e: Exception) {
                        logger.warn("[workflowId={}, gateway={}] PGI call failed for payment {}: {}",
                            workflowId, gateway, paymentId, e.message)
                        pgiFailed.add(paymentId)
                    }
                }

                successfulPaymentIds.addAll(pgiSuccessful)

                if (pgiFailed.isNotEmpty()) {
                    logger.warn("[workflowId={}, gateway={}] Chunk {} had {} PGI failures: {}",
                        workflowId, gateway, chunkIndex, pgiFailed.size, pgiFailed)
                    failedChunks.add(
                        FailedChunk(
                            chunkIndex = chunkIndex,
                            paymentIds = pgiFailed,
                            error = "PGI call failed after retries",
                            stage = "PGI"
                        )
                    )
                }

                completedChunks++
                logger.debug("[workflowId={}, gateway={}] Completed chunk {}/{}: {} successful, {} failed",
                    workflowId, gateway, chunkIndex + 1, chunks.size, pgiSuccessful.size, pgiFailed.size)

            } catch (e: Exception) {
                // IDB failed - entire chunk fails
                logger.error("[workflowId={}, gateway={}] IDB call failed for chunk {}: {}",
                    workflowId, gateway, chunkIndex, e.message)
                failedChunks.add(
                    FailedChunk(
                        chunkIndex = chunkIndex,
                        paymentIds = chunkPaymentIds,
                        error = "IDB facade call failed: ${e.message}",
                        stage = "IDB"
                    )
                )
                completedChunks++
            }
        }

        logger.info("[workflowId={}, gateway={}] Completed: {} successful payments, {} failed chunks",
            workflowId, gateway, successfulPaymentIds.size, failedChunks.size)

        return GatewayResult(
            gateway = gateway,
            successfulPaymentIds = successfulPaymentIds,
            failedChunks = failedChunks
        )
    }

    override fun getChunkProgress(): ChunkProgress {
        return ChunkProgress(
            totalChunks = totalChunks,
            completedChunks = completedChunks,
            currentChunkIndex = currentChunkIndex
        )
    }
}
