package com.example.paymentact.model

data class GatewayResult(
    val gateway: String,
    val successfulPaymentIds: List<String>,
    val failedChunks: List<FailedChunk>
)
