package com.example.paymentact.workflow

import com.example.paymentact.model.CheckStatusResult
import com.example.paymentact.model.PaymentStatusCheckInput
import com.example.paymentact.model.ProgressInfo
import io.temporal.workflow.QueryMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface PaymentStatusCheckWorkflow {

    @WorkflowMethod
    fun checkPaymentStatuses(input: PaymentStatusCheckInput): CheckStatusResult

    @QueryMethod
    fun getProgress(): ProgressInfo
}
