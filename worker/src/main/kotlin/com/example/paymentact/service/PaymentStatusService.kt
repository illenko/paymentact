package com.example.paymentact.service

import com.example.paymentact.model.CheckStatusQueryResponse
import com.example.paymentact.model.CheckStatusResult
import com.example.paymentact.model.CheckStatusStartResponse
import com.example.paymentact.model.ProgressInfo
import com.example.paymentact.model.WorkflowStatus
import com.example.paymentact.workflow.PaymentStatusCheckWorkflow
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class PaymentStatusService(
    private val workflowClient: WorkflowClient,
    @Value("\${temporal.task-queue}") private val taskQueue: String
) {

    private val logger = LoggerFactory.getLogger(PaymentStatusService::class.java)

    fun startPaymentStatusCheck(paymentIds: List<String>): CheckStatusStartResponse {
        val workflowId = "payment-check-${UUID.randomUUID()}"

        logger.info("Starting payment status check workflow {} for {} payments", workflowId, paymentIds.size)

        val options = WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(taskQueue)
            .build()

        val workflow = workflowClient.newWorkflowStub(PaymentStatusCheckWorkflow::class.java, options)

        // Start workflow asynchronously
        WorkflowClient.start(workflow::checkPaymentStatuses, paymentIds)

        logger.info("Started workflow {}", workflowId)

        return CheckStatusStartResponse(workflowId = workflowId)
    }

    fun getWorkflowStatus(workflowId: String): CheckStatusQueryResponse {
        logger.info("Querying workflow status for {}", workflowId)

        try {
            val workflowStub = workflowClient.newUntypedWorkflowStub(workflowId)
            val description = workflowStub.query("__stack_trace", String::class.java)

            // Get workflow execution status
            val execution = workflowClient.newUntypedWorkflowStub(workflowId)

            // Try to query progress
            val progress = try {
                val typedStub = workflowClient.newWorkflowStub(
                    PaymentStatusCheckWorkflow::class.java,
                    workflowId
                )
                typedStub.getProgress()
            } catch (e: Exception) {
                logger.warn("Could not query progress for workflow {}: {}", workflowId, e.message)
                null
            }

            // Check if workflow is completed
            val describe = workflowClient.workflowServiceStubs.blockingStub()
                .describeWorkflowExecution(
                    io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest.newBuilder()
                        .setNamespace(workflowClient.options.namespace)
                        .setExecution(
                            io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                                .setWorkflowId(workflowId)
                                .build()
                        )
                        .build()
                )

            val executionStatus = describe.workflowExecutionInfo.status

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
                    // Get the result
                    val result = try {
                        workflowStub.getResult(CheckStatusResult::class.java)
                    } catch (e: Exception) {
                        logger.error("Failed to get result for completed workflow {}: {}", workflowId, e.message)
                        null
                    }

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
            logger.error("Workflow not found: {}", workflowId, e)
            return CheckStatusQueryResponse(
                workflowId = workflowId,
                status = WorkflowStatus.NOT_FOUND,
                progress = null,
                result = null
            )
        }
    }
}
