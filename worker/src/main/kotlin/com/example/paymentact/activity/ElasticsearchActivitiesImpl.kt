package com.example.paymentact.activity

import com.example.paymentact.config.ExternalServicesConfig
import com.example.paymentact.model.GatewayInfo
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class ElasticsearchActivitiesImpl(
    restClientBuilder: RestClient.Builder,
    private val externalServicesConfig: ExternalServicesConfig
) : ElasticsearchActivities {

    private val logger = LoggerFactory.getLogger(ElasticsearchActivitiesImpl::class.java)

    private val restClient: RestClient = restClientBuilder
        .baseUrl(externalServicesConfig.elasticsearch.url)
        .build()

    override fun getGatewayForPayment(paymentId: String): GatewayInfo {
        logger.info("Looking up gateway for payment: {}", paymentId)

        val response = restClient.get()
            .uri("/${externalServicesConfig.elasticsearch.index}/_doc/{paymentId}", paymentId)
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError) { _, _ ->
                throw RuntimeException("Payment not found in Elasticsearch: $paymentId")
            }
            .onStatus(HttpStatusCode::is5xxServerError) { _, _ ->
                throw RuntimeException("Elasticsearch server error for payment: $paymentId")
            }
            .body(ElasticsearchResponse::class.java)

        val gatewayName = response?.source?.gatewayName
            ?: throw RuntimeException("Gateway not found for payment: $paymentId")

        logger.info("Found gateway {} for payment {}", gatewayName, paymentId)
        return GatewayInfo(paymentId = paymentId, gatewayName = gatewayName)
    }

    private data class ElasticsearchResponse(
        val _id: String?,
        val _source: PaymentDocument?
    ) {
        val source: PaymentDocument? get() = _source
    }

    private data class PaymentDocument(
        val paymentId: String?,
        val gatewayName: String?
    )
}
