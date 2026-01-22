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

data class CheckStatusResult(
    val successful: Map<String, List<String>>,      // gateway -> paymentIds
    val failed: Map<String, List<FailedChunk>>,     // gateway -> failed chunks
    val gatewayLookupFailed: List<String>           // paymentIds where ES lookup failed
)

enum class WorkflowStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    NOT_FOUND
}
