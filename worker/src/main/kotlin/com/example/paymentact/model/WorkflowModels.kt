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

data class ProgressInfo(
    val totalPayments: Int,
    val gatewaysIdentified: Int,
    val chunksTotal: Int,
    val chunksCompleted: Int,
    val chunksFailed: Int,
    val currentPhase: String = "UNKNOWN"
)

data class ChunkProgress(
    val totalChunks: Int,
    val completedChunks: Int,
    val currentChunkIndex: Int
)