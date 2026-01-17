package com.example.paymentact.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "external-services")
data class ExternalServicesConfig(
    val elasticsearch: ElasticsearchServiceConfig = ElasticsearchServiceConfig(),
    val idbFacade: ServiceConfig = ServiceConfig(),
    val pgiGateway: ServiceConfig = ServiceConfig()
)

data class ElasticsearchServiceConfig(
    val url: String = "http://localhost:9200",
    val index: String = "payments"
)

data class ServiceConfig(
    val url: String = "http://localhost:8080"
)
