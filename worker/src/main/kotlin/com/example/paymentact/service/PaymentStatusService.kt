package com.example.paymentact.service

import com.example.paymentact.config.PaymentCheckConfig
import com.example.paymentact.model.CheckStatusQueryResponse
import com.example.paymentact.model.CheckStatusResult
import com.example.paymentact.model.CheckStatusStartResponse
import com.example.paymentact.model.PaymentStatusCheckInput
import com.example.paymentact.model.WorkflowConfig
import com.example.paymentact.model.WorkflowStatus
import com.example.paymentact.workflow.PaymentStatusCheckWorkflow
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class PaymentStatusService(
    private val workflowClient: WorkflowClient,
    private val paymentCheckConfig: PaymentCheckConfig,
    @Value("\${spring.temporal.workers[0].task-queue}") private val taskQueue: String
) {

    private val logger = LoggerFactory.getLogger(PaymentStatusService::class.java)

    fun startPaymentStatusCheck(paymentIds: List<String>): CheckStatusStartResponse {
        val workflowId = "payment-check-${UUID.randomUUID()}"

        logger.info("[workflowId={}] Starting payment status check for {} payments",
            workflowId, paymentIds.size)

        val options = WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(taskQueue)
            .build()

        val workflow = workflowClient.newWorkflowStub(PaymentStatusCheckWorkflow::class.java, options)

        // Build workflow input with configuration
        val input = PaymentStatusCheckInput(
            paymentIds = paymentIds,
            config = WorkflowConfig(
                maxParallelEsQueries = paymentCheckConfig.elasticsearch.maxParallelQueries,
                maxPaymentsPerChunk = paymentCheckConfig.gateway.maxPaymentsPerChunk,
                activityTimeoutSeconds = paymentCheckConfig.retry.timeoutSeconds,
                maxRetryAttempts = paymentCheckConfig.retry.maxAttempts
            )
        )

        // Start workflow asynchronously
        WorkflowClient.start(workflow::checkPaymentStatuses, input)

        logger.info("[workflowId={}] Started workflow successfully", workflowId)

        return CheckStatusStartResponse(workflowId = workflowId)
    }

    fun getWorkflowStatus(workflowId: String): CheckStatusQueryResponse {
        logger.debug("[workflowId={}] Querying workflow status", workflowId)

        try {
            val workflowStub = workflowClient.newUntypedWorkflowStub(workflowId)

            val progress = runCatching {
                workflowClient.newWorkflowStub(PaymentStatusCheckWorkflow::class.java, workflowId).getProgress()
            }.onFailure { e ->
                logger.warn("[workflowId={}] Could not query progress: {}", workflowId, e.message)
            }.getOrNull()

            // Check if workflow is completed
            val describe = workflowClient.workflowServiceStubs.blockingStub()
                .describeWorkflowExecution(
                    DescribeWorkflowExecutionRequest.newBuilder()
                        .setNamespace(workflowClient.options.namespace)
                        .setExecution(
                            WorkflowExecution.newBuilder()
                                .setWorkflowId(workflowId)
                                .build()
                        )
                        .build()
                )

            val executionStatus = describe.workflowExecutionInfo.status
            logger.debug("[workflowId={}] Execution status: {}", workflowId, executionStatus)

            return when (executionStatus) {
                WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING -> {
                    CheckStatusQueryResponse(
                        workflowId = workflowId,
                        status = WorkflowStatus.RUNNING,
                        progress = progress,
                        result = null
                    )
                }
                WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED -> {
                    val result = runCatching {
                        workflowStub.getResult(CheckStatusResult::class.java)
                    }.onFailure { e ->
                        logger.error("[workflowId={}] Failed to get result for completed workflow: {}", workflowId, e.message)
                    }.getOrNull()

                    logger.info("[workflowId={}] Workflow completed successfully", workflowId)
                    CheckStatusQueryResponse(
                        workflowId = workflowId,
                        status = WorkflowStatus.COMPLETED,
                        progress = progress,
                        result = result
                    )
                }
                WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED,
                WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED,
                WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED,
                WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT -> {
                    logger.warn("[workflowId={}] Workflow ended with status: {}", workflowId, executionStatus)
                    CheckStatusQueryResponse(
                        workflowId = workflowId,
                        status = WorkflowStatus.FAILED,
                        progress = progress,
                        result = null
                    )
                }
                else -> {
                    CheckStatusQueryResponse(
                        workflowId = workflowId,
                        status = WorkflowStatus.RUNNING,
                        progress = progress,
                        result = null
                    )
                }
            }

        } catch (e: Exception) {
            logger.warn("[workflowId={}] Workflow not found: {}", workflowId, e.message)
            return CheckStatusQueryResponse(
                workflowId = workflowId,
                status = WorkflowStatus.NOT_FOUND,
                progress = null,
                result = null
            )
        }
    }
}
