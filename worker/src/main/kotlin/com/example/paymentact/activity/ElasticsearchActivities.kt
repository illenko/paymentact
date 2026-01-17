package com.example.paymentact.activity

import com.example.paymentact.model.GatewayInfo
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

@ActivityInterface
interface ElasticsearchActivities {

    @ActivityMethod
    fun getGatewayForPayment(paymentId: String): GatewayInfo
}
