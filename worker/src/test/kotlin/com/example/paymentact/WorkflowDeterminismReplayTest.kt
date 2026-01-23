package com.example.paymentact

import com.example.paymentact.workflow.GatewayWorkflowImpl
import com.example.paymentact.workflow.PaymentStatusCheckWorkflowImpl
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.GlobalDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.testing.WorkflowReplayer
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test

class WorkflowDeterminismReplayTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setup() {
            val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
            val dataConverter = DefaultDataConverter.newDefaultInstance()
                .withPayloadConverterOverrides(JacksonJsonPayloadConverter(objectMapper))
            GlobalDataConverter.register(dataConverter)
        }
    }

    @Test
    fun `PaymentStatusCheckWorkflow replays correctly`() {
        val history = loadHistory("executions/payment-check-execution.json")
        WorkflowReplayer.replayWorkflowExecution(
            history,
            PaymentStatusCheckWorkflowImpl::class.java
        )
    }

    @Test
    fun `GatewayWorkflow replays correctly`() {
        val history = loadHistory("executions/payment-check-gateway-execution.json")
        WorkflowReplayer.replayWorkflowExecution(
            history,
            GatewayWorkflowImpl::class.java
        )
    }

    private fun loadHistory(resourcePath: String): String {
        return this::class.java.classLoader
            .getResource(resourcePath)!!
            .readText()
    }
}