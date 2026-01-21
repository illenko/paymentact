package com.example.paymentact.model

data class ProgressInfo(
    val totalPayments: Int,
    val gatewaysIdentified: Int,
    val chunksTotal: Int,
    val chunksCompleted: Int,
    val chunksFailed: Int,
    val currentPhase: String = "UNKNOWN"
)
