package com.example.paymentact.model

data class FailedChunk(
    val chunkIndex: Int,
    val paymentIds: List<String>,
    val error: String,
    val stage: String  // "IDB" or "PGI"
)
