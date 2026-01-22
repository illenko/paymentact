package com.example.paymentact.model

data class GatewayInfo(
    val paymentId: String,
    val gatewayName: String
)

data class GatewayResult(
    val gateway: String,
    val successfulPaymentIds: List<String>,
    val failedChunks: List<FailedChunk>
)

data class FailedChunk(
    val chunkIndex: Int,
    val paymentIds: List<String>,
    val error: String,
    val stage: String  // "IDB" or "PGI"
)