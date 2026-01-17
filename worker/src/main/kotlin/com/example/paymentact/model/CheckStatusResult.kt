package com.example.paymentact.model

data class CheckStatusResult(
    val successful: Map<String, List<String>>,      // gateway -> paymentIds
    val failed: Map<String, List<FailedChunk>>,     // gateway -> failed chunks
    val gatewayLookupFailed: List<String>           // paymentIds where ES lookup failed
)
