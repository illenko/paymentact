package com.example.paymentact.activity

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

@ActivityInterface
interface PaymentGatewayActivities {

    @ActivityMethod
    fun callIdbFacade(gateway: String, paymentIds: List<String>)

    @ActivityMethod
    fun callPgiGateway(gateway: String, paymentId: String)
}
