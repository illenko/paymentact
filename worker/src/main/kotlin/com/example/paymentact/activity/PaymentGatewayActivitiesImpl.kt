package com.example.paymentact.activity

import com.example.paymentact.config.ExternalServicesConfig
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class PaymentGatewayActivitiesImpl(
    restClientBuilder: RestClient.Builder,
    private val externalServicesConfig: ExternalServicesConfig
) : PaymentGatewayActivities {

    private val logger = LoggerFactory.getLogger(PaymentGatewayActivitiesImpl::class.java)

    private val idbRestClient: RestClient = restClientBuilder
        .baseUrl(externalServicesConfig.idbFacade.url)
        .build()

    private val pgiRestClient: RestClient = restClientBuilder
        .baseUrl(externalServicesConfig.pgiGateway.url)
        .build()

    override fun callIdbFacade(gateway: String, paymentIds: List<String>) {
        logger.info("Calling IDB facade for gateway {} with {} payments", gateway, paymentIds.size)

        idbRestClient.post()
            .uri("/api/v1/payments/notify")
            .contentType(MediaType.APPLICATION_JSON)
            .body(IdbNotifyRequest(gatewayName = gateway, paymentIds = paymentIds))
            .retrieve()
            .onStatus(HttpStatusCode::isError) { _, response ->
                throw RuntimeException("IDB facade call failed for gateway $gateway: ${response.statusCode}")
            }
            .toBodilessEntity()

        logger.info("Successfully notified IDB facade for gateway {} with payments: {}", gateway, paymentIds)
    }

    override fun callPgiGateway(gateway: String, paymentId: String) {
        logger.info("Calling PGI gateway for payment {} on gateway {}", paymentId, gateway)

        pgiRestClient.post()
            .uri("/api/v1/payments/{paymentId}/check-status", paymentId)
            .header("X-Gateway-Name", gateway)
            .retrieve()
            .onStatus(HttpStatusCode::isError) { _, response ->
                throw RuntimeException("PGI gateway call failed for payment $paymentId: ${response.statusCode}")
            }
            .toBodilessEntity()

        logger.info("Successfully triggered PGI status check for payment {} on gateway {}", paymentId, gateway)
    }

    private data class IdbNotifyRequest(
        val gatewayName: String,
        val paymentIds: List<String>
    )
}
