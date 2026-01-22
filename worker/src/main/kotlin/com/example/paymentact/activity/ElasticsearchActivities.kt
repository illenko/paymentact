package com.example.paymentact.activity

import com.example.paymentact.config.ExternalServicesConfig
import com.example.paymentact.exception.ElasticsearchException
import com.example.paymentact.exception.PaymentNotFoundException
import com.example.paymentact.model.GatewayInfo
import io.temporal.activity.Activity
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@ActivityInterface
interface ElasticsearchActivities {
    @ActivityMethod
    fun getGatewayForPayment(paymentId: String): GatewayInfo
}

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
        val activityInfo = Activity.getExecutionContext().info
        logger.info("[workflowId={}, activityId={}] Looking up gateway for payment: {}",
            activityInfo.workflowId, activityInfo.activityId, paymentId)

        val response = restClient.get()
            .uri("/${externalServicesConfig.elasticsearch.index}/_doc/{paymentId}", paymentId)
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError) { _, _ ->
                throw PaymentNotFoundException(paymentId)
            }
            .onStatus(HttpStatusCode::is5xxServerError) { _, response ->
                throw ElasticsearchException(paymentId, "Server error: ${response.statusCode}")
            }
            .body(ElasticsearchResponse::class.java)

        val gatewayName = response?.source?.gatewayName
            ?: throw PaymentNotFoundException(paymentId)

        logger.info("[workflowId={}, activityId={}] Found gateway {} for payment {}",
            activityInfo.workflowId, activityInfo.activityId, gatewayName, paymentId)
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
