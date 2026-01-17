package com.example.paymentact.workflow

import com.example.paymentact.activity.PaymentGatewayActivities
import com.example.paymentact.model.FailedChunk
import com.example.paymentact.model.GatewayResult
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import org.slf4j.LoggerFactory
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
        logger.info("Processing gateway {} with {} chunks", gateway, chunks.size)

        totalChunks = chunks.size
        val successfulPaymentIds = mutableListOf<String>()
        val failedChunks = mutableListOf<FailedChunk>()

        chunks.forEachIndexed { chunkIndex, chunkPaymentIds ->
            currentChunkIndex = chunkIndex
            logger.info("Processing chunk {} for gateway {}: {} payments", chunkIndex, gateway, chunkPaymentIds.size)

            try {
                // Step A: Call IDB Facade (batch)
                activities.callIdbFacade(gateway, chunkPaymentIds)

                // Step B: Call PGI Gateway (sequential, one by one)
                val pgiSuccessful = mutableListOf<String>()
                val pgiFailed = mutableListOf<String>()

                for (paymentId in chunkPaymentIds) {
                    try {
                        activities.callPgiGateway(gateway, paymentId)
                        pgiSuccessful.add(paymentId)
                    } catch (e: Exception) {
                        logger.warn("PGI call failed for payment {}: {}", paymentId, e.message)
                        pgiFailed.add(paymentId)
                    }
                }

                successfulPaymentIds.addAll(pgiSuccessful)

                if (pgiFailed.isNotEmpty()) {
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

            } catch (e: Exception) {
                // IDB failed - entire chunk fails
                logger.error("IDB call failed for chunk {} on gateway {}: {}", chunkIndex, gateway, e.message)
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

        logger.info(
            "Completed gateway {}: {} successful, {} failed chunks",
            gateway, successfulPaymentIds.size, failedChunks.size
        )

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
