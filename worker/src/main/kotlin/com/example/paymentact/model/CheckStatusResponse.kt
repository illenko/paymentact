package com.example.paymentact.model

data class CheckStatusStartResponse(
    val workflowId: String,
    val status: String = "STARTED"
)

data class CheckStatusQueryResponse(
    val workflowId: String,
    val status: WorkflowStatus,
    val progress: ProgressInfo?,
    val result: CheckStatusResult?
)

enum class WorkflowStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    NOT_FOUND
}
