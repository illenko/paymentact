package com.example.paymentact.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "payment-check")
data class PaymentCheckConfig(
    val elasticsearch: ElasticsearchConfig = ElasticsearchConfig(),
    val gateway: GatewayConfig = GatewayConfig(),
    val retry: RetryConfig = RetryConfig()
)

data class ElasticsearchConfig(
    val maxParallelQueries: Int = 10
)

data class GatewayConfig(
    val maxPaymentsPerChunk: Int = 5
)

data class RetryConfig(
    val maxAttempts: Int = 3,
    val timeoutSeconds: Long = 5
)
