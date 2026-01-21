package com.example.paymentact.activity

import com.example.paymentact.config.ExternalServicesConfig
import com.example.paymentact.exception.IdbFacadeException
import com.example.paymentact.exception.PgiGatewayException
import io.temporal.activity.Activity
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
        val activityInfo = Activity.getExecutionContext().info
        logger.info("[workflowId={}, activityId={}] Calling IDB facade for gateway {} with {} payments: {}",
            activityInfo.workflowId, activityInfo.activityId, gateway, paymentIds.size, paymentIds)

        idbRestClient.post()
            .uri("/api/v1/payments/notify")
            .contentType(MediaType.APPLICATION_JSON)
            .body(IdbNotifyRequest(gatewayName = gateway, paymentIds = paymentIds))
            .retrieve()
            .onStatus(HttpStatusCode::isError) { _, response ->
                throw IdbFacadeException(gateway, paymentIds, "HTTP ${response.statusCode}")
            }
            .toBodilessEntity()

        logger.info("[workflowId={}, activityId={}] Successfully notified IDB facade for gateway {}",
            activityInfo.workflowId, activityInfo.activityId, gateway)
    }

    override fun callPgiGateway(gateway: String, paymentId: String) {
        val activityInfo = Activity.getExecutionContext().info
        logger.info("[workflowId={}, activityId={}] Calling PGI gateway for payment {} on gateway {}",
            activityInfo.workflowId, activityInfo.activityId, paymentId, gateway)

        pgiRestClient.post()
            .uri("/api/v1/payments/{paymentId}/check-status", paymentId)
            .header("X-Gateway-Name", gateway)
            .retrieve()
            .onStatus(HttpStatusCode::isError) { _, response ->
                throw PgiGatewayException(gateway, paymentId, "HTTP ${response.statusCode}")
            }
            .toBodilessEntity()

        logger.info("[workflowId={}, activityId={}] Successfully triggered PGI status check for payment {}",
            activityInfo.workflowId, activityInfo.activityId, paymentId)
    }

    private data class IdbNotifyRequest(
        val gatewayName: String,
        val paymentIds: List<String>
    )
}
