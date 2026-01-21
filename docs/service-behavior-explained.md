# Payment Status Check Service - Behavior Explained

This document explains the internal behavior of the payment status check service, focusing on the async patterns, loops, and the worker/client architecture.

## Table of Contents

1. [Worker vs Client - Why Combined?](#worker-vs-client---why-combined)
2. [Understanding the Async Patterns](#understanding-the-async-patterns)
3. [The Loops Explained](#the-loops-explained)
4. [Promise Execution Model](#promise-execution-model)
5. [Why It Looks Strange](#why-it-looks-strange)

---

## Worker vs Client - Why Combined?

### Traditional Separation

In a typical Temporal setup, you have two separate applications:

```
┌─────────────────────┐         ┌─────────────────────┐
│      CLIENT         │         │       WORKER        │
│  (REST API, etc.)   │         │  (Workflow/Activity │
│                     │         │   implementations)  │
│  - Starts workflows │         │                     │
│  - Queries status   │         │  - Executes logic   │
│  - No Temporal code │         │  - Polls for tasks  │
└─────────────────────┘         └─────────────────────┘
         │                               │
         └───────── Temporal Server ─────┘
```

**Why separate?**
- Scale workers independently from API servers
- Deploy workflow changes without API downtime
- Different resource requirements (CPU vs I/O bound)

### Our Combined Approach

```
┌─────────────────────────────────────────┐
│         COMBINED APPLICATION            │
│                                         │
│  ┌─────────────────┐ ┌───────────────┐  │
│  │  REST Controller │ │    Worker    │  │
│  │  (Client role)   │ │  (Executor)  │  │
│  └────────┬─────────┘ └───────┬──────┘  │
│           │                   │         │
│           └─── WorkflowClient ┘         │
└─────────────────────────────────────────┘
                    │
            Temporal Server
```

**Why combined here?**
- Simpler deployment for demo/small scale
- Single codebase to maintain
- Both need access to same domain models
- Can be split later if needed

**The code separation:**

```kotlin
// CLIENT ROLE: PaymentStatusService.kt
// - Uses WorkflowClient to START workflows
// - Uses WorkflowClient to QUERY workflows
// - Does NOT execute workflow logic

fun startPaymentStatusCheck(paymentIds: List<String>): CheckStatusStartResponse {
    val workflow = workflowClient.newWorkflowStub(...)
    WorkflowClient.start(workflow::checkPaymentStatuses, input)  // Just starts, doesn't wait
    return CheckStatusStartResponse(workflowId = workflowId)
}

// WORKER ROLE: TemporalConfig.kt
// - Registers workflow implementations
// - Registers activity implementations
// - Polls Temporal server for tasks

worker.registerWorkflowImplementationTypes(
    PaymentStatusCheckWorkflowImpl::class.java,  // Actual execution logic
    GatewayWorkflowImpl::class.java
)
```

---

## Understanding the Async Patterns

### The "Strange" Looking Code

```kotlin
val promises = batch.map { paymentId ->
    Async.function {
        esActivities.getGatewayForPayment(paymentId)
    }
}

promises.forEachIndexed { index, promise ->
    val result = promise.get()  // This blocks!
}
```

**Why does this look weird?**

In regular async programming (coroutines, CompletableFuture), you'd do:
```kotlin
// Regular async - all run truly in parallel, results come back whenever
val futures = items.map { async { doSomething(it) } }
val results = futures.awaitAll()
```

But Temporal's `Async.function` is different - it's **deterministic replay-safe**.

### What Actually Happens

```
Timeline of Execution:

First Run (no history):
─────────────────────────────────────────────────────────────────────────
T0: Workflow starts
T1: Async.function { activity1 } → Schedules activity, returns Promise<A>
T2: Async.function { activity2 } → Schedules activity, returns Promise<B>
T3: Async.function { activity3 } → Schedules activity, returns Promise<C>
T4: promise1.get() → BLOCKS until activity1 completes
T5: promise2.get() → Activity2 might already be done, returns immediately
T6: promise3.get() → Activity3 might already be done, returns immediately
─────────────────────────────────────────────────────────────────────────

The activities run IN PARALLEL on potentially different workers!
The .get() calls just wait for results.
```

### Replay Scenario (after crash)

```
Replay (with history showing activity1, activity2, activity3 all completed):
─────────────────────────────────────────────────────────────────────────
T0: Workflow replays from history
T1: Async.function { activity1 } → Sees history, returns completed Promise<A>
T2: Async.function { activity2 } → Sees history, returns completed Promise<B>
T3: Async.function { activity3 } → Sees history, returns completed Promise<C>
T4: promise1.get() → Returns immediately (from history)
T5: promise2.get() → Returns immediately (from history)
T6: promise3.get() → Returns immediately (from history)
─────────────────────────────────────────────────────────────────────────

NO activities actually execute during replay - results come from history!
```

---

## The Loops Explained

### Loop 1: Batched ES Lookups

```kotlin
// PaymentStatusCheckWorkflowImpl.kt

paymentIds.chunked(config.maxParallelEsQueries).forEach { batch ->
    //     ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
    //     Split 100 payments into batches of 10

    val promises = batch.map { paymentId ->
        Async.function {
            esActivities.getGatewayForPayment(paymentId)
        }
    }
    //  ^^ All 10 activities scheduled simultaneously

    promises.forEachIndexed { index, promise ->
        val result = promise.get()  // Wait for each result
        // Process result...
    }
    //  ^^ After ALL 10 complete, move to next batch
}
```

**Visual representation:**

```
100 Payment IDs
      │
      ▼
┌─────────────────────────────────────────────────────────────┐
│ Batch 1 (IDs 1-10)                                          │
│   ┌───┐ ┌───┐ ┌───┐ ┌───┐ ┌───┐ ┌───┐ ┌───┐ ┌───┐ ┌───┐ ┌───┐│
│   │ES1│ │ES2│ │ES3│ │ES4│ │ES5│ │ES6│ │ES7│ │ES8│ │ES9│ │E10││
│   └───┘ └───┘ └───┘ └───┘ └───┘ └───┘ └───┘ └───┘ └───┘ └───┘│
│         ─────────── PARALLEL ───────────                     │
└─────────────────────────────────────────────────────────────┘
                          │
                     wait for all
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│ Batch 2 (IDs 11-20)                                         │
│   ... same pattern ...                                       │
└─────────────────────────────────────────────────────────────┘
                          │
                         ...
```

**Why batch instead of all parallel?**
- Prevents overwhelming Elasticsearch with 1000 simultaneous queries
- Provides backpressure
- Configurable via `maxParallelEsQueries`

### Loop 2: Child Workflows per Gateway

```kotlin
// PaymentStatusCheckWorkflowImpl.kt

for ((gateway, chunks) in chunksByGateway) {
    val childWorkflow = Workflow.newChildWorkflowStub(...)
    val promise = Async.function {
        childWorkflow.processGateway(gateway, chunks)
    }
    promises.add(promise)
}
// ^^ All child workflows started in parallel

return promises.mapIndexed { index, promise ->
    promise.get()  // Wait for each gateway to complete
}
```

**Visual representation:**

```
Payments grouped by gateway:
  - Gateway A: [p1, p2, p3, p4, p5, p6, p7]  → 2 chunks
  - Gateway B: [p8, p9, p10]                  → 1 chunk
  - Gateway C: [p11, p12, p13, p14, p15]      → 1 chunk

                    Parent Workflow
                          │
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
   ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
   │ Child WF A  │ │ Child WF B  │ │ Child WF C  │
   │ (Gateway A) │ │ (Gateway B) │ │ (Gateway C) │
   └─────────────┘ └─────────────┘ └─────────────┘
          │               │               │
          ▼               ▼               ▼
      2 chunks        1 chunk         1 chunk
    (sequential)    (sequential)    (sequential)

   ──────────────── PARALLEL ────────────────
```

### Loop 3: Sequential Chunks in Child Workflow

```kotlin
// GatewayWorkflowImpl.kt

chunks.forEachIndexed { chunkIndex, chunkPaymentIds ->
    // Step A: IDB (batch call)
    activities.callIdbFacade(gateway, chunkPaymentIds)

    // Step B: PGI (one by one)
    for (paymentId in chunkPaymentIds) {
        activities.callPgiGateway(gateway, paymentId)
    }
}
```

**Why sequential within gateway?**
- Prevents overloading a single gateway
- Maintains order guarantees if needed
- Gateway might have rate limits

```
Gateway A Child Workflow:

Chunk 1: [p1, p2, p3, p4, p5]
    │
    ├── IDB Facade (batch: p1,p2,p3,p4,p5) ─────────────┐
    │                                                    │ Sequential
    ├── PGI p1 → PGI p2 → PGI p3 → PGI p4 → PGI p5 ────┘
    │
    ▼ (wait for chunk 1 to complete)

Chunk 2: [p6, p7]
    │
    ├── IDB Facade (batch: p6,p7)
    │
    └── PGI p6 → PGI p7
```

---

## Promise Execution Model

### Temporal Promise vs Java Future

| Aspect | Java CompletableFuture | Temporal Promise |
|--------|----------------------|------------------|
| Execution | Runs on thread pool | Runs on Temporal workers |
| Persistence | Lost on crash | Survives crashes |
| Replay | N/A | Replays from history |
| Blocking | `future.get()` blocks thread | `promise.get()` suspends workflow |

### The Magic of `promise.get()`

```kotlin
val promise = Async.function { activity.doSomething() }
// At this point: activity is SCHEDULED but maybe not started

val result = promise.get()
// At this point: workflow SUSPENDS until activity completes
```

**What "suspends" means:**
1. Workflow state is saved to Temporal server
2. Worker is free to process other workflows
3. When activity completes, workflow is resumed
4. No thread is blocked waiting!

### Promise.allOf - Waiting for Multiple

```kotlin
// If you want to wait for ALL promises before processing ANY results:
val promises: List<Promise<Result>> = items.map {
    Async.function { activity.process(it) }
}

Promise.allOf(promises).get()  // Wait for ALL to complete

// Now all results are available
promises.forEach { it.get() }  // These return immediately
```

---

## Why It Looks Strange

### 1. Blocking Looks Wrong

```kotlin
// This LOOKS like it would be slow:
promises.forEach { promise ->
    val result = promise.get()  // Blocking call in a loop?!
}
```

**But it's not slow because:**
- All activities were scheduled in parallel BEFORE the loop
- Each `.get()` just waits for an already-running activity
- If activity N finishes before N-1, no problem - it's cached

### 2. Try-Catch Inside Async

```kotlin
Async.function {
    try {
        Result.success(activity.doSomething())
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

**Why wrap in Result?**
- Temporal retries failed activities automatically
- After max retries, exception propagates
- Wrapping in Result lets us handle failures gracefully without failing the whole workflow

### 3. No Kotlin Coroutines

```kotlin
// You might expect:
suspend fun checkPaymentStatuses() = coroutineScope {
    val results = paymentIds.map { async { lookup(it) } }.awaitAll()
}

// But instead we have:
fun checkPaymentStatuses(): CheckStatusResult {
    val promises = paymentIds.map { Async.function { lookup(it) } }
    promises.map { it.get() }
}
```

**Why no coroutines?**
- Temporal has its own execution model
- Coroutines are not deterministic (thread scheduling varies)
- Temporal needs to replay code identically
- `Async.function` is Temporal's "coroutine equivalent"

### 4. Activities in Loops

```kotlin
// This looks like it would schedule activities one at a time:
for (paymentId in paymentIds) {
    activities.callPgiGateway(gateway, paymentId)  // Sync call!
}
```

**This IS intentional sequential execution:**
- Sometimes you WANT sequential (rate limiting, ordering)
- For parallel, you MUST use `Async.function`

---

## Execution Summary

```
┌─────────────────────────────────────────────────────────────────────┐
│                    COMPLETE EXECUTION FLOW                          │
└─────────────────────────────────────────────────────────────────────┘

API Request: POST /payments/check-status
    │
    ▼
PaymentStatusService.startPaymentStatusCheck()
    │
    ├── Creates WorkflowClient stub
    ├── WorkflowClient.start() ──────────────────────┐
    │                                                 │
    └── Returns workflowId immediately               │
                                                     ▼
                                            Temporal Server
                                                     │
                                            schedules workflow
                                                     │
                                                     ▼
                                            Worker picks up task
                                                     │
                                                     ▼
                              PaymentStatusCheckWorkflowImpl.checkPaymentStatuses()
                                                     │
    ┌────────────────────────────────────────────────┴────────────────┐
    │                                                                  │
    │  Phase 1: ES Lookups (batched parallel)                         │
    │  ┌──────────────────────────────────────────────────────────┐   │
    │  │ Batch 1: 10 activities ═══════════════╗                  │   │
    │  │                                       ║ parallel         │   │
    │  │ Batch 2: 10 activities ═══════════════╝                  │   │
    │  │ ...                                                      │   │
    │  └──────────────────────────────────────────────────────────┘   │
    │                           │                                      │
    │                           ▼                                      │
    │  Phase 2: Group by gateway, create chunks                       │
    │                           │                                      │
    │                           ▼                                      │
    │  Phase 3: Child workflows (parallel across gateways)            │
    │  ┌──────────────────────────────────────────────────────────┐   │
    │  │                                                          │   │
    │  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐      │   │
    │  │  │ Gateway A   │  │ Gateway B   │  │ Gateway C   │      │   │
    │  │  │             │  │             │  │             │      │   │
    │  │  │ Chunk1      │  │ Chunk1      │  │ Chunk1      │      │   │
    │  │  │  ├─IDB      │  │  ├─IDB      │  │  ├─IDB      │      │   │
    │  │  │  └─PGI×5    │  │  └─PGI×3    │  │  └─PGI×4    │      │   │
    │  │  │      │      │  │             │  │             │      │   │
    │  │  │      ▼      │  │             │  │             │      │   │
    │  │  │ Chunk2      │  │             │  │             │      │   │
    │  │  │  ├─IDB      │  │             │  │             │      │   │
    │  │  │  └─PGI×2    │  │             │  │             │      │   │
    │  │  └─────────────┘  └─────────────┘  └─────────────┘      │   │
    │  │       ║                 ║                 ║              │   │
    │  │       ╚═════════════════╩═════════════════╝              │   │
    │  │                    parallel                              │   │
    │  └──────────────────────────────────────────────────────────┘   │
    │                           │                                      │
    │                           ▼                                      │
    │  Phase 4: Aggregate results                                     │
    │                           │                                      │
    └───────────────────────────┴──────────────────────────────────────┘
                                │
                                ▼
                        CheckStatusResult
                                │
                    stored in Temporal history
                                │
                                ▼
            API: GET /payments/check-status/{workflowId}
                                │
                                ▼
                    Returns result to client
```
