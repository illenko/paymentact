package com.example.paymentact.config

import com.example.paymentact.activity.ElasticsearchActivities
import com.example.paymentact.activity.PaymentGatewayActivities
import com.example.paymentact.workflow.GatewayWorkflowImpl
import com.example.paymentact.workflow.PaymentStatusCheckWorkflowImpl
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.Worker
import io.temporal.worker.WorkerFactory
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TemporalConfig(
    @Value("\${temporal.service-address}") private val serviceAddress: String,
    @Value("\${temporal.namespace}") private val namespace: String,
    @Value("\${temporal.task-queue}") private val taskQueue: String,
    private val elasticsearchActivities: ElasticsearchActivities,
    private val paymentGatewayActivities: PaymentGatewayActivities
) {

    private val logger = LoggerFactory.getLogger(TemporalConfig::class.java)

    private lateinit var workerFactory: WorkerFactory

    @Bean
    fun workflowServiceStubs(): WorkflowServiceStubs {
        val options = WorkflowServiceStubsOptions.newBuilder()
            .setTarget(serviceAddress)
            .build()

        return WorkflowServiceStubs.newServiceStubs(options)
    }

    @Bean
    fun workflowClient(workflowServiceStubs: WorkflowServiceStubs): WorkflowClient {
        val options = WorkflowClientOptions.newBuilder()
            .setNamespace(namespace)
            .build()

        return WorkflowClient.newInstance(workflowServiceStubs, options)
    }

    @Bean
    fun workerFactory(workflowClient: WorkflowClient): WorkerFactory {
        workerFactory = WorkerFactory.newInstance(workflowClient)
        return workerFactory
    }

    @PostConstruct
    fun startWorker() {
        logger.info("Starting Temporal worker on task queue: {}", taskQueue)

        val worker: Worker = workerFactory.newWorker(taskQueue)

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

        workerFactory.start()

        logger.info("Temporal worker started successfully")
    }

    @PreDestroy
    fun stopWorker() {
        logger.info("Stopping Temporal worker")
        workerFactory.shutdown()
    }
}
