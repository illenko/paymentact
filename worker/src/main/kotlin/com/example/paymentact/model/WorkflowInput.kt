package com.example.paymentact.model

data class PaymentStatusCheckInput(
    val paymentIds: List<String>,
    val config: WorkflowConfig = WorkflowConfig()
)

data class WorkflowConfig(
    val maxParallelEsQueries: Int = 10,
    val maxPaymentsPerChunk: Int = 5,
    val activityTimeoutSeconds: Long = 30,
    val maxRetryAttempts: Int = 3
)
