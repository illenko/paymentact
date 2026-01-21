package com.example.paymentact.config

import com.example.paymentact.activity.ElasticsearchActivities
import com.example.paymentact.activity.PaymentGatewayActivities
import com.example.paymentact.workflow.GatewayWorkflowImpl
import com.example.paymentact.workflow.PaymentStatusCheckWorkflowImpl
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.Worker
import io.temporal.worker.WorkerFactory
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TemporalConfig(
    @Value("\${temporal.service-address}") private val serviceAddress: String,
    @Value("\${temporal.namespace}") private val namespace: String,
    @Value("\${temporal.task-queue}") private val taskQueue: String
) {

    private val logger = LoggerFactory.getLogger(TemporalConfig::class.java)

    private var workerFactory: WorkerFactory? = null

    @Bean
    fun temporalDataConverter(): DataConverter {
        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        return DefaultDataConverter.newDefaultInstance()
            .withPayloadConverterOverrides(JacksonJsonPayloadConverter(objectMapper))
    }

    @Bean
    fun workflowServiceStubs(): WorkflowServiceStubs {
        logger.info("Connecting to Temporal at {}", serviceAddress)
        val options = WorkflowServiceStubsOptions.newBuilder()
            .setTarget(serviceAddress)
            .build()
        return WorkflowServiceStubs.newServiceStubs(options)
    }

    @Bean
    fun workflowClient(workflowServiceStubs: WorkflowServiceStubs, temporalDataConverter: DataConverter): WorkflowClient {
        val options = WorkflowClientOptions.newBuilder()
            .setNamespace(namespace)
            .setDataConverter(temporalDataConverter)
            .build()
        return WorkflowClient.newInstance(workflowServiceStubs, options)
    }

    @Bean
    fun workerFactory(
        workflowClient: WorkflowClient,
        elasticsearchActivities: ElasticsearchActivities,
        paymentGatewayActivities: PaymentGatewayActivities
    ): WorkerFactory {
        logger.info("Starting Temporal worker on task queue: {}", taskQueue)

        val factory = WorkerFactory.newInstance(workflowClient)
        val worker: Worker = factory.newWorker(taskQueue)

        // Register workflows
        worker.registerWorkflowImplementationTypes(
            PaymentStatusCheckWorkflowImpl::class.java,
            GatewayWorkflowImpl::class.java
        )

        // Register activities
        worker.registerActivitiesImplementations(
            elasticsearchActivities,
            paymentGatewayActivities
        )

        factory.start()
        logger.info("Temporal worker started successfully")

        this.workerFactory = factory
        return factory
    }

    @PreDestroy
    fun stopWorker() {
        logger.info("Stopping Temporal worker")
        workerFactory?.shutdown()
    }
}