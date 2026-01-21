# Temporal Concepts for Developers

This document explains the core Temporal concepts used in this project.

## What is Temporal?

Temporal is a durable execution platform that makes applications fault-tolerant by preserving the state of long-running workflows. If your application crashes, Temporal automatically resumes execution from exactly where it left off.

## Core Concepts

### 1. Workflow

A **Workflow** is a durable function that orchestrates activities and other workflows. Key characteristics:

- **Deterministic**: Must produce the same result given the same inputs
- **Long-running**: Can run for seconds, days, or even years
- **Fault-tolerant**: Automatically resumes after failures

```kotlin
@WorkflowInterface
interface PaymentStatusCheckWorkflow {

    @WorkflowMethod
    fun checkPaymentStatuses(input: PaymentStatusCheckInput): CheckStatusResult

    @QueryMethod
    fun getProgress(): ProgressInfo
}
```

**Important Rules for Workflows:**
- Never use `Thread.sleep()` - use `Workflow.sleep()`
- Never use `Random` - use `Workflow.newRandom()`
- Never make HTTP calls directly - use Activities
- Never use mutable static variables
- Never use system time - use `Workflow.currentTimeMillis()`

### 2. Activity

An **Activity** is a function that performs a single, well-defined action (like making an API call). Activities can:

- Make network calls
- Access databases
- Perform I/O operations
- Be retried automatically on failure

```kotlin
@ActivityInterface
interface ElasticsearchActivities {

    @ActivityMethod
    fun getGatewayForPayment(paymentId: String): GatewayInfo
}
```

**Activity Implementation:**
```kotlin
@Component
class ElasticsearchActivitiesImpl(...) : ElasticsearchActivities {

    override fun getGatewayForPayment(paymentId: String): GatewayInfo {
        // This can make HTTP calls, access databases, etc.
        return restClient.get()
            .uri("/payments/_doc/{paymentId}", paymentId)
            .retrieve()
            .body(...)
    }
}
```

### 3. Worker

A **Worker** is a service that hosts Workflow and Activity implementations. It polls Temporal Server for tasks and executes them.

```kotlin
val worker = factory.newWorker(taskQueue)

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
```

### 4. Task Queue

A **Task Queue** is a lightweight, dynamically allocated queue for routing tasks to workers. Multiple workers can listen on the same task queue for scalability.

```yaml
temporal:
  task-queue: payment-status-check
```

### 5. Workflow Client

The **Workflow Client** is used to start, query, and signal workflows from your application code.

```kotlin
// Start a workflow asynchronously
val workflow = workflowClient.newWorkflowStub(PaymentStatusCheckWorkflow::class.java, options)
WorkflowClient.start(workflow::checkPaymentStatuses, input)

// Query a running workflow
val progress = workflow.getProgress()
```

## Advanced Concepts Used in This Project

### Child Workflows

A workflow can spawn **Child Workflows** to break down complex logic. Child workflows:

- Have their own lifecycle
- Can be retried independently
- Help organize code into logical units

```kotlin
val childOptions = ChildWorkflowOptions.newBuilder()
    .setWorkflowId("$workflowId-gateway-$gateway")
    .build()

val childWorkflow = Workflow.newChildWorkflowStub(
    GatewayWorkflow::class.java,
    childOptions
)

// Execute child workflow
val result = childWorkflow.processGateway(gateway, chunks)
```

### Async Execution

Use `Async.function` to execute activities or child workflows in parallel:

```kotlin
// Start multiple activities in parallel
val promises = paymentIds.map { paymentId ->
    Async.function {
        esActivities.getGatewayForPayment(paymentId)
    }
}

// Wait for all to complete
promises.forEach { promise ->
    val result = promise.get()
}
```

### Query Methods

**Query Methods** allow you to inspect workflow state without affecting execution:

```kotlin
@QueryMethod
fun getProgress(): ProgressInfo {
    return ProgressInfo(
        totalPayments = totalPayments,
        chunksCompleted = chunksCompleted,
        currentPhase = currentPhase
    )
}
```

### Retry Policies

Activities automatically retry on failure according to the configured retry policy:

```kotlin
ActivityOptions.newBuilder()
    .setStartToCloseTimeout(Duration.ofSeconds(30))
    .setRetryOptions(
        RetryOptions.newBuilder()
            .setMaximumAttempts(3)
            .setInitialInterval(Duration.ofSeconds(1))
            .setBackoffCoefficient(2.0)
            .setMaximumInterval(Duration.ofSeconds(10))
            .build()
    )
    .build()
```

| Parameter | Description |
|-----------|-------------|
| `maximumAttempts` | Max retry count (including first attempt) |
| `initialInterval` | First retry delay |
| `backoffCoefficient` | Multiplier for subsequent delays |
| `maximumInterval` | Cap on retry delay |

## Workflow Execution Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                     Temporal Server                              │
│  ┌─────────────────┐    ┌─────────────────┐                     │
│  │ Workflow History │    │   Task Queues   │                     │
│  └─────────────────┘    └─────────────────┘                     │
└───────────────────────────────┬─────────────────────────────────┘
                                │
        ┌───────────────────────┼───────────────────────┐
        │                       │                       │
        ▼                       ▼                       ▼
┌───────────────┐       ┌───────────────┐       ┌───────────────┐
│   Worker 1    │       │   Worker 2    │       │   Worker N    │
│  (Workflows)  │       │  (Workflows)  │       │  (Workflows)  │
│  (Activities) │       │  (Activities) │       │  (Activities) │
└───────────────┘       └───────────────┘       └───────────────┘
```

## Project-Specific Architecture

```
PaymentStatusCheckWorkflow (Parent)
    │
    ├── Step 1: ES Lookups (parallel activities)
    │       └── ElasticsearchActivities.getGatewayForPayment()
    │
    ├── Step 2: Group by gateway, create chunks
    │
    └── Step 3: Spawn child workflows (parallel)
            │
            └── GatewayWorkflow (per gateway)
                    │
                    ├── For each chunk (sequential):
                    │       ├── PaymentGatewayActivities.callIdbFacade()
                    │       └── PaymentGatewayActivities.callPgiGateway() (per payment)
                    │
                    └── Return GatewayResult
```

## Key Benefits Demonstrated

1. **Fault Tolerance**: If a worker crashes mid-execution, another worker picks up exactly where it left off
2. **Visibility**: Query workflow progress at any time via `getProgress()`
3. **Automatic Retries**: Failed HTTP calls are retried automatically
4. **Scalability**: Add more workers to handle higher load
5. **Long-Running Operations**: Workflows can run for extended periods without timeouts

## Running Temporal Locally

```bash
# Start Temporal server with Docker
docker run -d --name temporal \
  -p 7233:7233 \
  -p 8233:8233 \
  temporalio/auto-setup:latest

# Access Temporal Web UI
open http://localhost:8233
```

## Useful Commands

```bash
# List workflows
temporal workflow list

# Describe a specific workflow
temporal workflow describe --workflow-id payment-check-xxx

# Query workflow state
temporal workflow query \
  --workflow-id payment-check-xxx \
  --query-type getProgress
```

## Further Reading

- [Temporal Documentation](https://docs.temporal.io/)
- [Temporal Kotlin SDK](https://github.com/temporalio/sdk-java)
- [Workflow Determinism](https://docs.temporal.io/workflows#deterministic-constraints)
