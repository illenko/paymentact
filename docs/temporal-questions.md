# Temporal Questions & Deep Dive

> Tricky questions developers might ask about Temporal, with detailed explanations and examples.

---

## Table of Contents

1. [Replay Mechanism: How Workers Know What to Execute](#1-replay-mechanism-how-workers-know-what-to-execute)
2. [Task Queues: Workflows and Activities Together](#2-task-queues-workflows-and-activities-together)
3. [How Promise.get() Retrieves Results Across Workers](#3-how-promiseget-retrieves-results-across-workers)
4. [Determinism & Replay Questions](#4-determinism--replay-questions)
5. [Versioning & Deployments Questions](#5-versioning--deployments-questions)
6. [Async/Promise Execution Model Questions](#6-asyncpromise-execution-model-questions)
7. [Architecture & Design Questions](#7-architecture--design-questions)
8. [Failure Handling & Recovery Questions](#8-failure-handling--recovery-questions)
9. [Scalability & Production Questions](#9-scalability--production-questions)
10. [Tricky Edge Cases](#10-tricky-edge-cases)
11. [Signals & Signal Handling](#11-signals--signal-handling)
12. [Continue-As-New & History Limits](#12-continue-as-new--history-limits)
13. [Activity Heartbeats](#13-activity-heartbeats)
14. [Cancellation Patterns](#14-cancellation-patterns)
15. [Local Activities vs Regular Activities](#15-local-activities-vs-regular-activities)
16. [Side Effects & Mutable Side Effects](#16-side-effects--mutable-side-effects)
17. [Sticky Execution & Workflow Caching](#17-sticky-execution--workflow-caching)
18. [Timers, Deadlines & Await Patterns](#18-timers-deadlines--await-patterns)
19. [Testing Workflows](#19-testing-workflows)
20. [Worker Tuning & Performance](#20-worker-tuning--performance)
21. [Child Workflow vs Activity Decision](#21-child-workflow-vs-activity-decision)

---

## 1. Replay Mechanism: How Workers Know What to Execute

This is one of the most important concepts to understand deeply.

### The Core Question

**Q: How does a worker know during replay whether to re-execute an activity or reuse the result from history?**

### The Answer: Event Matching

When workflow code executes, Temporal's SDK tracks the **event sequence number**. Each activity call is matched against the corresponding event in history.

```
Your Workflow Code                     Event History (stored in Temporal)
─────────────────                      ─────────────────────────────────
                                       Event 1: WorkflowExecutionStarted
                                       Event 2: WorkflowTaskScheduled
                                       Event 3: WorkflowTaskStarted
                                       Event 4: WorkflowTaskCompleted
val result1 = activity.esLookup()  →   Event 5: ActivityTaskScheduled (ES lookup)
                                       Event 6: ActivityTaskStarted
                                       Event 7: ActivityTaskCompleted (result: "stripe")
val result2 = activity.idbCall()   →   Event 8: ActivityTaskScheduled (IDB call)
                                       Event 9: ActivityTaskStarted
                                       Event 10: ActivityTaskCompleted (result: success)
```

### First Execution vs Replay

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           FIRST EXECUTION                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Workflow code runs:                                                        │
│                                                                             │
│  val result = activities.esLookup(paymentId)                                │
│       │                                                                     │
│       ▼                                                                     │
│  SDK checks: Is there a matching ActivityTaskCompleted event?               │
│       │                                                                     │
│       ▼                                                                     │
│  NO (history is empty/no matching event)                                    │
│       │                                                                     │
│       ▼                                                                     │
│  SDK actions:                                                               │
│    1. Records ActivityTaskScheduled command                                 │
│    2. Suspends workflow (yields control back to Temporal)                   │
│    3. Temporal server creates Activity Task in task queue                   │
│    4. A worker picks up activity task and EXECUTES the actual code         │
│    5. Result is saved to history as ActivityTaskCompleted                   │
│    6. Workflow task is scheduled to continue                                │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                              REPLAY                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Workflow code runs (same code, from the beginning):                        │
│                                                                             │
│  val result = activities.esLookup(paymentId)                                │
│       │                                                                     │
│       ▼                                                                     │
│  SDK checks: Is there a matching ActivityTaskCompleted event?               │
│       │                                                                     │
│       ▼                                                                     │
│  YES (Event 7: ActivityTaskCompleted with result "stripe")                  │
│       │                                                                     │
│       ▼                                                                     │
│  SDK actions:                                                               │
│    1. Returns the result directly from history ("stripe")                   │
│    2. Does NOT schedule any activity                                        │
│    3. Does NOT execute any activity code                                    │
│    4. Workflow code continues immediately                                   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Detailed Example: Payment Workflow Replay

```kotlin
// PaymentStatusCheckWorkflowImpl.kt
override fun checkPaymentStatuses(input: CheckStatusInput): CheckStatusResult {
    // Step 1: ES lookups
    val gatewayMap = mutableMapOf<String, MutableList<String>>()

    input.paymentIds.forEach { paymentId ->
        val gateway = esActivities.getGatewayForPayment(paymentId)  // Activity call
        gatewayMap.getOrPut(gateway) { mutableListOf() }.add(paymentId)
    }

    // Step 2: Process each gateway
    gatewayMap.forEach { (gateway, payments) ->
        gatewayActivities.processGateway(gateway, payments)  // Activity call
    }

    return CheckStatusResult(success = true)
}
```

**Scenario: Worker crashes after 2 ES lookups, replays on new worker**

```
Original execution (Worker 1):
───────────────────────────────────────────────────────────────────────
Event 1:  WorkflowExecutionStarted
Event 2:  WorkflowTaskCompleted
Event 3:  ActivityTaskScheduled   {activityType: "getGatewayForPayment", input: "pay_001"}
Event 4:  ActivityTaskCompleted   {result: "stripe"}
Event 5:  ActivityTaskScheduled   {activityType: "getGatewayForPayment", input: "pay_002"}
Event 6:  ActivityTaskCompleted   {result: "adyen"}
          ← CRASH HERE, Worker 1 dies
───────────────────────────────────────────────────────────────────────

Replay (Worker 2 picks up):
───────────────────────────────────────────────────────────────────────
Code executes:  esActivities.getGatewayForPayment("pay_001")
SDK:            Finds Event 4 (ActivityTaskCompleted) → returns "stripe" ✓
                NO activity executed

Code executes:  esActivities.getGatewayForPayment("pay_002")
SDK:            Finds Event 6 (ActivityTaskCompleted) → returns "adyen" ✓
                NO activity executed

Code executes:  esActivities.getGatewayForPayment("pay_003")
SDK:            No matching event in history → SCHEDULE NEW ACTIVITY
                Activity actually executes on a worker
───────────────────────────────────────────────────────────────────────
```

### The Matching Algorithm

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    HOW SDK MATCHES ACTIVITY CALLS TO HISTORY                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  For each activity call in workflow code:                                   │
│                                                                             │
│  1. SDK maintains an "event pointer" (starts at beginning of history)       │
│                                                                             │
│  2. When code calls activity:                                               │
│     ┌─────────────────────────────────────────────────────────────────┐    │
│     │ Does next event match this activity?                             │    │
│     │   - Same activity type?                                          │    │
│     │   - Same input parameters?                                       │    │
│     └─────────────────────────────────────────────────────────────────┘    │
│           │                    │                                            │
│          YES                  NO                                            │
│           │                    │                                            │
│           ▼                    ▼                                            │
│     ┌───────────────┐   ┌─────────────────────────────────────────────┐    │
│     │ Return result │   │ Are we past the end of history?              │    │
│     │ from history  │   └─────────────────────────────────────────────┘    │
│     │ (no execute)  │         │                    │                        │
│     └───────────────┘        YES                  NO                        │
│                               │                    │                        │
│                               ▼                    ▼                        │
│                    ┌─────────────────┐   ┌─────────────────────────┐       │
│                    │ Schedule new    │   │ NonDeterministicError!  │       │
│                    │ activity        │   │ Code doesn't match      │       │
│                    │ (first time)    │   │ history                 │       │
│                    └─────────────────┘   └─────────────────────────┘       │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Why NonDeterministicException Happens

```kotlin
// Original code (v1):
override fun checkPaymentStatuses(input: CheckStatusInput): CheckStatusResult {
    val gateway = esActivities.getGatewayForPayment(input.paymentIds[0])
    gatewayActivities.processGateway(gateway, input.paymentIds)
    return CheckStatusResult(success = true)
}

// Modified code (v2) - added a new activity call:
override fun checkPaymentStatuses(input: CheckStatusInput): CheckStatusResult {
    auditActivities.logStart(input.paymentIds)  // NEW LINE ADDED
    val gateway = esActivities.getGatewayForPayment(input.paymentIds[0])
    gatewayActivities.processGateway(gateway, input.paymentIds)
    return CheckStatusResult(success = true)
}
```

**What happens when v2 code replays a workflow started with v1:**

```
History (created by v1):                 v2 Code Execution:
────────────────────────                 ──────────────────────────────────
Event 1: WorkflowStarted
Event 2: WorkflowTaskCompleted
Event 3: ActivityScheduled               auditActivities.logStart()  ← NEW
         {type: "getGatewayForPayment"}        │
                                               ▼
                                         SDK: Expected "logStart" but found
                                              "getGatewayForPayment" in history
                                               │
                                               ▼
                                         ╔═══════════════════════════════════╗
                                         ║   NonDeterministicException!      ║
                                         ║   History mismatch detected       ║
                                         ╚═══════════════════════════════════╝
```

---

## 2. Task Queues: Workflows and Activities Together

### The Question

**Q: Does a task queue contain both workflow tasks and activity tasks?**

**A: Yes.** A single task queue can contain both types of tasks.

### Visual Representation

```
                         Task Queue: "payment-processing"
┌─────────────────────────────────────────────────────────────────────────────┐
│                                                                             │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐          │
│  │  WORKFLOW TASK   │  │  ACTIVITY TASK   │  │  ACTIVITY TASK   │   ...    │
│  │                  │  │                  │  │                  │          │
│  │  workflow-123    │  │  ES lookup       │  │  IDB call        │          │
│  │  "continue after │  │  paymentId:      │  │  gateway: stripe │          │
│  │   activity done" │  │  "pay_001"       │  │  payments: [..]  │          │
│  └──────────────────┘  └──────────────────┘  └──────────────────┘          │
│                                                                             │
│  Task Type Legend:                                                          │
│  ┌────────────────┐ = Workflow Task (orchestration decisions)               │
│  └────────────────┘                                                         │
│  ┌────────────────┐ = Activity Task (actual work execution)                 │
│  └────────────────┘                                                         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                     │
                     ┌───────────────┼───────────────┐
                     ▼               ▼               ▼
                ┌─────────┐    ┌─────────┐    ┌─────────┐
                │Worker 1 │    │Worker 2 │    │Worker 3 │
                │         │    │         │    │         │
                │ Handles │    │ Handles │    │ Handles │
                │  BOTH   │    │  BOTH   │    │  BOTH   │
                └─────────┘    └─────────┘    └─────────┘
```

### Task Types Explained

| Task Type | Purpose | When Created | What Executes |
|-----------|---------|--------------|---------------|
| **Workflow Task** | Make decisions, schedule activities | Workflow start, activity completion, signal received, timer fired | Your workflow code (`checkPaymentStatuses()`) |
| **Activity Task** | Execute actual work | When workflow code schedules an activity | Your activity code (`esLookup()`, `idbCall()`) |

### Worker Registration

```kotlin
// TemporalConfig.kt
@Bean
fun workerFactory(
    workflowClient: WorkflowClient,
    esActivities: ElasticsearchActivities,
    gatewayActivities: GatewayActivities
): WorkerFactory {
    val factory = WorkerFactory.newInstance(workflowClient)

    // Create worker for task queue
    val worker = factory.newWorker("payment-processing")

    // Register WORKFLOW implementations (handles Workflow Tasks)
    worker.registerWorkflowImplementationTypes(
        PaymentStatusCheckWorkflowImpl::class.java,
        GatewayWorkflowImpl::class.java
    )

    // Register ACTIVITY implementations (handles Activity Tasks)
    worker.registerActivitiesImplementations(
        esActivities,
        gatewayActivities
    )

    return factory
}
```

### Complete Task Flow Example

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  STEP 1: Client starts workflow                                              │
│                                                                             │
│  val workflow = workflowClient.newWorkflowStub(PaymentStatusCheckWorkflow)  │
│  WorkflowClient.start(workflow::checkPaymentStatuses, input)                │
│                                                                             │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  STEP 2: Temporal creates WORKFLOW TASK                                      │
│                                                                             │
│  Task Queue: ┌─────────────────────────────┐                                │
│              │ Workflow Task: workflow-123 │                                │
│              │ "Start workflow execution"  │                                │
│              └─────────────────────────────┘                                │
│                                                                             │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  STEP 3: Worker 1 picks up workflow task, runs workflow code                 │
│                                                                             │
│  fun checkPaymentStatuses(input):                                           │
│      val promise = Async.function {                                         │
│          esActivities.getGatewayForPayment("pay_001")  ← schedules activity │
│      }                                                                      │
│                                                                             │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  STEP 4: Temporal creates ACTIVITY TASK                                      │
│                                                                             │
│  Task Queue: ┌─────────────────────────────┐                                │
│              │ Activity Task               │                                │
│              │ type: "getGatewayForPayment"│                                │
│              │ input: "pay_001"            │                                │
│              └─────────────────────────────┘                                │
│                                                                             │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  STEP 5: Worker 2 (different worker!) picks up activity task                 │
│                                                                             │
│  Executes actual code:                                                      │
│      fun getGatewayForPayment(paymentId: String): String {                  │
│          return elasticsearchClient.query(paymentId).gateway  // Real I/O   │
│      }                                                                      │
│                                                                             │
│  Returns: "stripe"                                                          │
│                                                                             │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  STEP 6: Temporal saves result, creates new WORKFLOW TASK                    │
│                                                                             │
│  History updated:                                                           │
│    Event N: ActivityTaskCompleted {result: "stripe"}                        │
│                                                                             │
│  Task Queue: ┌─────────────────────────────┐                                │
│              │ Workflow Task: workflow-123 │                                │
│              │ "Continue after activity"   │                                │
│              └─────────────────────────────┘                                │
│                                                                             │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  STEP 7: Worker 3 (any worker!) picks up workflow task                       │
│                                                                             │
│  - Replays workflow from beginning                                          │
│  - Hits esActivities.getGatewayForPayment("pay_001")                        │
│  - Finds result in history → returns "stripe" (no activity execution)       │
│  - Continues to next line of code                                           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Separating Task Queues (Advanced)

You can use different task queues for workflows and activities:

```kotlin
// Worker A: Only workflows (lightweight, orchestration only)
val workflowWorker = factory.newWorker("payment-workflows")
workflowWorker.registerWorkflowImplementationTypes(
    PaymentStatusCheckWorkflowImpl::class.java
)

// Worker B: Only activities (can scale independently, resource-heavy)
val activityWorker = factory.newWorker("payment-activities")
activityWorker.registerActivitiesImplementations(
    esActivities,
    gatewayActivities
)
```

Then in workflow code:

```kotlin
// Activities use different task queue
private val activities = Workflow.newActivityStub(
    GatewayActivities::class.java,
    ActivityOptions.newBuilder()
        .setTaskQueue("payment-activities")  // Different queue!
        .setStartToCloseTimeout(Duration.ofSeconds(30))
        .build()
)
```

**Benefits of separation:**
- Scale activity workers independently
- Different resource profiles (CPU-heavy activities vs lightweight orchestration)
- Route specific activities to specialized workers

---

## 3. How Promise.get() Retrieves Results Across Workers

### The Question

**Q: If any worker can execute activities, how does the worker calling `promise.get()` retrieve the result?**

This seems like magic at first — Worker 1 schedules an activity, Worker 2 executes it, but somehow Worker 1 (or Worker 3!) gets the result. How?

### The Key Insight: Workers Don't Talk to Each Other

The result doesn't go directly from activity worker to workflow worker. It goes through **Temporal Server's history**.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     THE ACTUAL FLOW                                          │
└─────────────────────────────────────────────────────────────────────────────┘

Step 1: Worker 1 runs workflow, hits Async.function
─────────────────────────────────────────────────────
   Worker 1                         Temporal Server
   ────────                         ───────────────
   workflow code:
   val promise = Async.function {
       activity.esLookup("pay_001")
   }
        │
        ▼
   SDK says: "Schedule activity"
   SDK says: "I'm done for now"    ──────────►   Creates Activity Task
        │                                        in task queue
        ▼
   WORKFLOW TASK COMPLETES                       Saves: "Activity scheduled"
   Worker 1 is FREE                              to history
   (not waiting, not blocked!)


Step 2: Worker 2 picks up activity, executes, reports result
────────────────────────────────────────────────────────────
                                    Temporal Server              Worker 2
                                    ───────────────              ────────
                                    Activity Task ──────────────► Picks up task
                                    in queue
                                                                  Executes:
                                                                  esLookup("pay_001")
                                                                  returns "stripe"
                                                                       │
                                    Saves to history: ◄────────────────┘
                                    "Activity completed,
                                     result: stripe"
                                           │
                                           ▼
                                    Creates NEW Workflow Task
                                    "Hey, activity is done,
                                     continue the workflow"


Step 3: Worker 3 (any worker!) picks up workflow task, replays
──────────────────────────────────────────────────────────────
   Worker 3                         Temporal Server
   ────────                         ───────────────
   Picks up workflow task ◄──────── Workflow Task in queue
        │
        ▼
   REPLAYS workflow from beginning
   using history
        │
        ▼
   Code: val promise = Async.function { activity.esLookup("pay_001") }
        │
        ▼
   SDK checks history:
   "Is there ActivityTaskCompleted for esLookup(pay_001)?"
        │
        ▼
   YES! Result = "stripe"
   Returns "stripe" immediately
   (no activity scheduled)
        │
        ▼
   Code continues: promise.get()
        │
        ▼
   Returns "stripe" (already resolved from history)
```

### The Promise is Not a Real Future

In regular Java/Kotlin, a `Future` holds a reference to a running thread or callback. Temporal's `Promise` is different:

```
Java CompletableFuture:              Temporal Promise:
───────────────────────              ─────────────────
┌─────────────────────┐              ┌─────────────────────┐
│ Future              │              │ Promise             │
│                     │              │                     │
│ - Thread reference  │              │ - Activity type     │
│ - Callback queue    │              │ - Input parameters  │
│ - Result holder     │              │ - Event ID (if any) │
│                     │              │                     │
│ get() → wait for    │              │ get() → check       │
│         thread      │              │         history     │
└─────────────────────┘              └─────────────────────┘

Future.get():                        Promise.get():
  "Block until my thread finishes"     "Is there a matching event in history?"
                                         │           │
                                        YES          NO
                                         │           │
                                    Return it    Suspend workflow,
                                                 wait for new
                                                 workflow task
```

### The Restaurant Ticket Analogy

Think of it like a **ticket system**:

```
1. You (workflow) go to a restaurant, order food (schedule activity)
2. You get a ticket number (promise)
3. You LEAVE the restaurant (workflow task completes, worker is free)

4. Kitchen (any activity worker) prepares your food
5. Kitchen puts food on counter with ticket number (saves result to history)
6. Counter person calls your ticket (new workflow task created)

7. You (possibly different instance of you!) come back
8. You show ticket (replay hits promise.get())
9. You get your food (result from history)

The food doesn't teleport from kitchen to you.
It goes: Kitchen → Counter (history) → You
```

### What Happens Inside the SDK

```kotlin
// What you write:
val promise = Async.function { activities.esLookup("pay_001") }
// ... other code ...
val result = promise.get()  // How does this work?!

// What actually happens inside SDK:

// When Async.function is called:
class TemporalPromise {
    val activityType = "esLookup"
    val input = "pay_001"
    var eventId: Long? = null  // Will be set if found in history
    var cachedResult: String? = null

    fun schedule() {
        // Check history first
        val existingEvent = history.findActivityCompleted(activityType, input)
        if (existingEvent != null) {
            this.eventId = existingEvent.id
            this.cachedResult = existingEvent.result
            return  // Don't schedule, already done
        }
        // Not in history, schedule new activity
        commands.add(ScheduleActivityCommand(activityType, input))
    }
}

// When promise.get() is called:
fun get(): T {
    if (cachedResult != null) {
        return cachedResult  // Already have it from history
    }

    // Result not ready yet
    // This doesn't block a thread!
    // It tells Temporal: "I'm waiting, complete this workflow task"
    // Workflow state is saved, worker moves on to other work

    throw SuspendWorkflowException("Waiting for activity $activityType")

    // Later, when activity completes and workflow task resumes:
    // - Workflow replays
    // - Async.function finds result in history
    // - promise.get() returns immediately
}
```

### Common Misconceptions

| Misconception | Reality |
|---------------|---------|
| Worker 1 waits for Worker 2 | Worker 1 finishes its task and is free immediately |
| Result sent directly between workers | Result goes through Temporal Server history |
| Promise holds thread reference | Promise is just metadata (activity type + input) |
| `promise.get()` blocks a thread | `promise.get()` either returns from history or suspends workflow |
| Same worker must handle the whole workflow | Any worker can pick up any workflow/activity task |

### The Complete Picture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     WORKFLOW EXECUTION LIFECYCLE                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   Time    Worker 1         Temporal Server        Worker 2      Worker 3   │
│   ────    ────────         ───────────────        ────────      ────────   │
│                                                                             │
│   T0      Picks up         History:                                         │
│           workflow task    [WorkflowStarted]                                │
│           │                                                                 │
│   T1      Runs code:                                                        │
│           Async.function   ◄─── Adds to history:                            │
│           {activity()}          [ActivityScheduled]                         │
│           │                     Creates Activity Task                       │
│   T2      DONE ────────►   Activity Task ─────────► Picks up               │
│           (free to do           in queue            activity               │
│            other work)                              │                       │
│                                                     │                       │
│   T3                                                Executes                │
│                                                     esLookup()              │
│                                                     │                       │
│   T4                       ◄────────────────────────┘                       │
│                            Saves result:            DONE                    │
│                            [ActivityCompleted]                              │
│                            Creates Workflow Task                            │
│                                 │                                           │
│   T5                            └─────────────────────────────► Picks up    │
│                                                                 workflow    │
│                                                                 task        │
│   T6                                                            │           │
│                                                                 Replays:    │
│                                                                 Async.func  │
│                                                                 → history!  │
│                                                                 promise.get │
│                                                                 → "stripe"  │
│                                                                 │           │
│   T7                                                            Continues   │
│                                                                 workflow... │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

Key insight: Worker 1 never "waits". It completes its task at T2.
The result arrives via history, picked up by Worker 3 at T5-T6.
```

---

## 4. Determinism & Replay Questions

### Q: What happens if you iterate over a HashMap inside a workflow?

**A:** `HashMap` iteration order is not guaranteed. On replay (potentially different JVM), order may differ, causing activities to be scheduled in wrong order.

```kotlin
// WRONG - Non-deterministic
val gatewayMap = HashMap<String, List<String>>()
gatewayMap["stripe"] = listOf("pay_001")
gatewayMap["adyen"] = listOf("pay_002")

gatewayMap.forEach { (gateway, payments) ->
    activities.processGateway(gateway, payments)  // Order may vary on replay!
}

// CORRECT - Deterministic
val gatewayMap = LinkedHashMap<String, List<String>>()  // Preserves insertion order
// OR
gatewayMap.toSortedMap().forEach { (gateway, payments) ->
    activities.processGateway(gateway, payments)  // Consistent order
}
```

**What goes wrong:**

```
First execution:                    Replay (different JVM):
────────────────                    ────────────────────────
1. processGateway("stripe", ...)    1. processGateway("adyen", ...)  ← DIFFERENT!
2. processGateway("adyen", ...)     2. processGateway("stripe", ...)

History expects "stripe" first, but code calls "adyen" first
→ NonDeterministicException
```

### Q: Can you use Kotlin coroutines inside a Temporal workflow?

**A:** No. Coroutines use thread scheduling which varies across runs.

```kotlin
// WRONG - Non-deterministic
override fun checkPaymentStatuses(input: CheckStatusInput) = runBlocking {
    val results = input.paymentIds.map { paymentId ->
        async { activities.esLookup(paymentId) }  // Coroutine scheduling varies!
    }.awaitAll()
}

// CORRECT - Use Temporal's Async
override fun checkPaymentStatuses(input: CheckStatusInput): CheckStatusResult {
    val promises = input.paymentIds.map { paymentId ->
        Async.function { activities.esLookup(paymentId) }  // Temporal-managed
    }
    val results = promises.map { it.get() }
}
```

### Q: What's the difference between `Workflow.sleep()` and `Thread.sleep()`?

**A:** `Workflow.sleep()` creates a **Timer event** in history. `Thread.sleep()` just blocks the thread with no record.

```kotlin
// WRONG - Not recorded in history
Thread.sleep(5000)  // On replay, this causes 5-second delay again!

// CORRECT - Recorded as Timer event
Workflow.sleep(Duration.ofSeconds(5))  // On replay, skipped (timer already fired)
```

**History comparison:**

```
With Workflow.sleep():              With Thread.sleep():
──────────────────────              ────────────────────
Event 5: TimerStarted               (nothing recorded)
Event 6: TimerFired

On replay: Sees timer fired,        On replay: Actually sleeps again,
skips immediately                   wasting 5 seconds
```

### Q: Can I use `Random()` or `UUID.randomUUID()` in workflow code?

**A:** No. These produce different values on replay.

```kotlin
// WRONG - Different value on replay
val requestId = UUID.randomUUID().toString()

// CORRECT - Use Temporal's deterministic random
val requestId = Workflow.randomUUID().toString()

// CORRECT - Or generate in activity and pass to workflow
val requestId = activities.generateRequestId()  // Activity result is persisted
```

### Q: Can I call an external API directly from workflow code?

**A:** No. Network calls are non-deterministic (may fail, return different results).

```kotlin
// WRONG - Direct HTTP call in workflow
override fun checkPaymentStatuses(input: CheckStatusInput): CheckStatusResult {
    val response = httpClient.get("https://api.example.com/status")  // Non-deterministic!
}

// CORRECT - Wrap in activity
override fun checkPaymentStatuses(input: CheckStatusInput): CheckStatusResult {
    val response = activities.fetchExternalStatus()  // Activity handles I/O
}
```

---

## 5. Versioning & Deployments Questions

### Q: You deployed a change that adds a new activity. Running workflows fail. How to fix?

**A:** Use `Workflow.getVersion()` to branch between old and new code paths.

```kotlin
override fun checkPaymentStatuses(input: CheckStatusInput): CheckStatusResult {
    val version = Workflow.getVersion(
        "add-audit-logging",        // Change identifier
        Workflow.DEFAULT_VERSION,   // Min version (old code)
        1                           // Max version (new code)
    )

    if (version >= 1) {
        // New code path - only for workflows started after deployment
        auditActivities.logStart(input.paymentIds)
    }

    // Original code continues...
    val gateway = esActivities.getGatewayForPayment(input.paymentIds[0])
}
```

**How it works:**

```
Workflow started BEFORE deployment:
──────────────────────────────────
- No version marker in history
- getVersion() returns DEFAULT_VERSION (-1)
- Skips the new activity call
- Matches original history

Workflow started AFTER deployment:
─────────────────────────────────
- getVersion() records version 1 in history
- Executes the new activity call
- New history includes the activity
```

### Q: When can you safely remove versioning code?

**A:** Only after ALL workflows started on the old version have completed.

```kotlin
// Phase 1: Add versioning (both old and new workflows running)
val version = Workflow.getVersion("change-id", Workflow.DEFAULT_VERSION, 1)
if (version >= 1) {
    newActivity()
}
originalCode()

// Phase 2: All old workflows completed, bump minimum
val version = Workflow.getVersion("change-id", 1, 1)  // Min = Max = 1
newActivity()  // Can remove the if-check
originalCode()

// Phase 3: After sufficient time, remove versioning entirely
newActivity()
originalCode()
```

### Q: Is changing an activity's return type safe?

**A:** No. Historical results can't be deserialized into new type.

```kotlin
// Original activity
fun getGatewayInfo(paymentId: String): String  // Returns "stripe"

// Changed to return object - BREAKS REPLAY
fun getGatewayInfo(paymentId: String): GatewayInfo  // Can't deserialize "stripe" to GatewayInfo
```

**Safe approach:**

```kotlin
// Option 1: New activity with different name
fun getGatewayInfoV2(paymentId: String): GatewayInfo

// Option 2: Use versioning in workflow
val version = Workflow.getVersion("gateway-info-type", DEFAULT_VERSION, 1)
val gatewayInfo = if (version >= 1) {
    activities.getGatewayInfoV2(paymentId)
} else {
    val name = activities.getGatewayInfo(paymentId)
    GatewayInfo(name = name)  // Convert old format
}
```

---

## 6. Async/Promise Execution Model Questions

### Q: You call `promise.get()` in a loop - isn't that blocking and defeating parallelism?

**A:** No. All activities are scheduled before the loop. `.get()` just waits for already-running activities.

```kotlin
// This executes ALL activities in parallel
val promises = paymentIds.map { paymentId ->
    Async.function { activities.esLookup(paymentId) }  // All scheduled immediately
}

// This just collects results - activities are already running
promises.forEach { promise ->
    val result = promise.get()  // Waits for one that's already in progress
    process(result)
}
```

**Timeline visualization:**

```
T0: Schedule activity 1 ─────────────────────┐
T0: Schedule activity 2 ─────────────────┐   │
T0: Schedule activity 3 ──────────────┐  │   │
                                      │  │   │
                                      ▼  ▼   ▼
                              Activities run in PARALLEL
                                      │  │   │
T1: promise1.get() ←──────────────────┘  │   │  (Activity 1 done)
T2: promise2.get() ←─────────────────────┘   │  (Activity 2 done)
T3: promise3.get() ←─────────────────────────┘  (Activity 3 done)
```

### Q: What does `promise.get()` actually do - does it block a thread?

**A:** No. It **suspends** the workflow. The worker thread is freed to process other tasks.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         promise.get() INTERNALS                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. Workflow code hits promise.get()                                        │
│                                                                             │
│  2. SDK checks: Is the activity completed?                                  │
│           │                    │                                            │
│          YES                  NO                                            │
│           │                    │                                            │
│           ▼                    ▼                                            │
│     Return result        Workflow state saved to Temporal                   │
│     immediately          Worker thread released                             │
│                          (can process other workflows!)                     │
│                               │                                             │
│                               ▼                                             │
│                          When activity completes:                           │
│                          - Temporal creates Workflow Task                   │
│                          - A worker picks it up                             │
│                          - Replays to this point                            │
│                          - promise.get() returns result                     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Q: Difference between Temporal Promise and Java CompletableFuture?

| Aspect | Java CompletableFuture | Temporal Promise |
|--------|------------------------|------------------|
| **Execution** | Runs on thread pool | Runs on Temporal workers |
| **Persistence** | Lost on crash | Survives crashes (history) |
| **Replay** | N/A | Results from history |
| **Blocking** | `get()` blocks thread | `get()` suspends workflow |
| **Scheduling** | You manage threads | Temporal manages |

---

## 7. Architecture & Design Questions

### Q: Why process chunks sequentially within a gateway instead of in parallel?

**A:** Rate limiting and backpressure per gateway.

```
Parallel within gateway:                Sequential within gateway:
───────────────────────                 ────────────────────────────
Gateway Stripe receives:                Gateway Stripe receives:
  Chunk 1 ═══╗                            Chunk 1 ──────────────►
  Chunk 2 ═══╬══► 8 chunks at once!       Chunk 2 ──────────────►
  ...        ║    May overwhelm gateway   ...      One at a time
  Chunk 8 ═══╝                            Chunk 8 ──────────────►

Risk: Gateway rate limits, timeouts      Controlled load, predictable
```

### Q: Why child workflows per gateway instead of activities in parent?

**A:** Failure isolation and history management.

```kotlin
// With child workflows:
Gateway A fails → Child workflow A fails → Parent continues with B, C
Gateway B succeeds → Child workflow B completes
Gateway C succeeds → Child workflow C completes

Result: 2 out of 3 gateways succeeded, detailed failure info for A
```

```kotlin
// Without child workflows (all in parent):
Gateway A fails → Entire parent workflow affected
                  Must handle complex state management
                  History grows large with all activities
```

### Q: Why combined worker/client application instead of separate?

**A:** Simpler for current scale, can split later.

```
Current (Combined):                    Future (Separated):
──────────────────                     ─────────────────────
┌─────────────────────┐                ┌──────────────┐   ┌──────────────┐
│  REST API + Worker  │                │   REST API   │   │   Worker     │
│                     │                │   (Client)   │   │  (Executor)  │
│  - Single deploy    │       →        │              │   │              │
│  - Shared models    │                │  Scale: 2x   │   │  Scale: 10x  │
│  - Simple           │                └──────────────┘   └──────────────┘
└─────────────────────┘
                                       Split when: API and worker have
                                       different scaling requirements
```

---

## 8. Failure Handling & Recovery Questions

### Q: Worker crashes mid-workflow. What happens?

**A:** Another worker picks up, replays from history, continues.

```
Worker 1:                              Worker 2:
─────────                              ─────────
Execute activity 1 ✓
Execute activity 2 ✓
Execute activity 3 (in progress)
    ← CRASH                            Picks up workflow task
                                       Replay: activity 1 → result from history
                                       Replay: activity 2 → result from history
                                       Replay: activity 3 → result from history (completed before crash)
                                       OR
                                       → schedule activity 3 (if not completed)
                                       Continue with activity 4...
```

### Q: If IDB fails for a chunk, do you skip PGI for that chunk?

**A:** Yes. IDB is prerequisite for PGI.

```kotlin
chunks.forEach { chunk ->
    try {
        activities.callIdbFacade(gateway, chunk)  // Must succeed
        chunk.forEach { paymentId ->
            activities.callPgiGateway(gateway, paymentId)
        }
    } catch (e: Exception) {
        // IDB failed, record chunk as failed, skip PGI
        failedChunks.add(ChunkFailure(chunk, e.message))
    }
}
```

### Q: Activity keeps failing. What happens after max retries?

**A:** Exception propagates to workflow code.

```kotlin
// Activity options with retry policy
val options = ActivityOptions.newBuilder()
    .setStartToCloseTimeout(Duration.ofSeconds(30))
    .setRetryOptions(
        RetryOptions.newBuilder()
            .setMaximumAttempts(3)
            .build()
    )
    .build()

// In workflow code
try {
    val result = activities.riskyOperation()
} catch (e: ActivityFailure) {
    // After 3 attempts, exception reaches here
    // Workflow can handle gracefully (log, skip, compensate)
    logger.error("Activity failed after retries: ${e.message}")
    failedOperations.add(...)
}
```

---

## 9. Scalability & Production Questions

### Q: You add more workers - do running workflows get redistributed?

**A:** Workflows aren't "redistributed", but future tasks can go to any worker.

```
Before (2 workers):                    After (4 workers):
───────────────────                    ────────────────────
Worker 1: Workflows A, B polling       Worker 1: polling
Worker 2: Workflows C, D polling       Worker 2: polling
                                       Worker 3: polling  ← NEW
                                       Worker 4: polling  ← NEW

Workflow A's next activity task:       Workflow A's next activity task:
  → Goes to Worker 1 or 2                → Goes to Worker 1, 2, 3, or 4
```

### Q: How does the query method work without affecting workflow?

**A:** Queries are read-only, synchronous, don't create history events.

```kotlin
// In workflow implementation
@QueryMethod
fun getProgress(): ProgressInfo {
    return ProgressInfo(
        phase = currentPhase,           // Just reads current state
        completedChunks = completedCount,
        totalChunks = totalCount
    )
}

// Query execution:
// 1. Request goes to worker with cached workflow
// 2. Worker reads in-memory state
// 3. Returns immediately
// 4. NO history events created
// 5. NO workflow code execution triggered
```

---

## 10. Tricky Edge Cases

### Q: What if the same workflow ID is submitted twice?

**A:** Second attempt fails with `WorkflowExecutionAlreadyStartedError`.

```kotlin
// First call
WorkflowClient.start(workflow::process, input)  // Success, workflow-123 starts

// Second call with same ID
WorkflowClient.start(workflow::process, input)  // Throws WorkflowExecutionAlreadyStartedError
```

**Solutions:**

```kotlin
// Option 1: Use unique IDs
val workflowId = "payment-check-${UUID.randomUUID()}"

// Option 2: Check if exists first
try {
    workflowClient.newWorkflowStub(existingWorkflowId)
        .let { existing -> return existing.getResult() }  // Return existing result
} catch (e: WorkflowNotFoundException) {
    // Start new workflow
}

// Option 3: Use WorkflowIdReusePolicy
WorkflowOptions.newBuilder()
    .setWorkflowIdReusePolicy(
        WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY
    )
```

### Q: What happens during rolling deploy (some workers v1, some v2)?

**A:** With proper versioning, it's safe.

```
Worker v1:                             Worker v2:
──────────                             ──────────
Code without new activity              Code with versioned new activity:
                                       if (getVersion(...) >= 1) {
                                           newActivity()
                                       }

Workflow started on v1:                Workflow started on v2:
  - Has no version marker in history     - Has version=1 in history
  - v2 worker replays it                 - v1 worker can't replay it (no newActivity)
  - getVersion() returns DEFAULT         - But v1 won't pick it up if properly configured
  - Skips newActivity()
  - Works correctly!
```

### Q: What if Temporal Server goes down?

**A:** Processing pauses but no data is lost.

```
Temporal Server: DOWN
────────────────────
Workers: Can't poll for tasks (connection refused)
Running activities: May complete but can't report result
Workflows: Paused (no new workflow tasks)

Temporal Server: RECOVERED
──────────────────────────
Workers: Resume polling
Pending tasks: Delivered to workers
Activity results: Reported and saved
Workflows: Continue from where they left off
```

### Q: What's the difference between `startToCloseTimeout` and `scheduleToCloseTimeout`?

```
scheduleToCloseTimeout: Total time from scheduling to completion (including retries)
startToCloseTimeout: Time for a single attempt

┌────────────────────────────────────────────────────────────────────────────┐
│                        scheduleToCloseTimeout (60s)                         │
├────────────────────────────────────────────────────────────────────────────┤
│                                                                            │
│  ┌──────────────────┐   ┌──────────────────┐   ┌──────────────────┐       │
│  │ Attempt 1        │   │ Attempt 2        │   │ Attempt 3        │       │
│  │ startToClose:20s │   │ startToClose:20s │   │ startToClose:20s │       │
│  │ ──────────────►  │   │ ──────────────►  │   │ ──────────────►  │       │
│  │ FAILED           │   │ FAILED           │   │ SUCCESS          │       │
│  └──────────────────┘   └──────────────────┘   └──────────────────┘       │
│                                                                            │
│  T=0                    T=20s                  T=40s              T=50s    │
│  Activity scheduled     Retry 1               Retry 2            Complete │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘

If scheduleToCloseTimeout=60s and each attempt takes 25s:
- Attempt 1: fails at T=25s
- Attempt 2: fails at T=50s
- Attempt 3: would exceed 60s → scheduleToCloseTimeout exceeded, activity fails
```

---

## 11. Signals & Signal Handling

### Q: What is a Signal and how does it differ from a Query?

**A:** Signals modify workflow state; Queries only read it.

| Aspect | Signal | Query |
|--------|--------|-------|
| **Purpose** | Send data/commands to workflow | Read workflow state |
| **Modifies state** | Yes | No (read-only) |
| **Creates history event** | Yes (SignalReceived) | No |
| **Async/Sync** | Async (fire-and-forget) | Sync (waits for response) |
| **Survives replay** | Yes (in history) | N/A |

```kotlin
// Signal definition
@SignalMethod
fun cancelPayment(paymentId: String)  // No return value

// Query definition
@QueryMethod
fun getProgress(): ProgressInfo  // Returns data
```

### Q: What happens if a Signal arrives while the workflow is replaying?

**A:** The signal is recorded in history and will be processed after replay catches up.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     SIGNAL DURING REPLAY                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  History:                          Replay:                                  │
│  ─────────                         ────────                                 │
│  Event 1: WorkflowStarted          Execute code...                          │
│  Event 2: ActivityCompleted        ← Replay here                            │
│  Event 3: SignalReceived ←─────────── Signal arrives NOW                    │
│  Event 4: ActivityCompleted                                                 │
│                                                                             │
│  What happens:                                                              │
│  1. Signal is appended to history (Event 3)                                 │
│  2. Replay continues processing Events 1, 2                                 │
│  3. When replay reaches Event 3, signal handler is called                   │
│  4. Workflow continues with Event 4                                         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Q: Are Signals guaranteed to be processed in order?

**A:** Signals to the **same workflow** are processed in the order they're received by Temporal Server.

```kotlin
// Client sends:
workflow.signal1()  // T=0
workflow.signal2()  // T=1
workflow.signal3()  // T=2

// Workflow receives in order: signal1 → signal2 → signal3
// This is GUARANTEED for a single workflow
```

**But be careful with multiple workflows:**

```kotlin
// Signals to DIFFERENT workflows have no ordering guarantee
workflowA.signal()  // May be processed after...
workflowB.signal()  // ...this one
```

### Q: Can a Signal handler call activities?

**A:** Yes, but it's tricky. The signal handler runs in the workflow thread.

```kotlin
// WORKS but be careful:
@SignalMethod
fun processUpdate(data: String) {
    // This schedules an activity (non-blocking)
    Async.function { activities.processData(data) }
}

// DANGEROUS - blocking in signal handler:
@SignalMethod
fun processUpdate(data: String) {
    // This blocks the workflow until activity completes
    // Other signals will queue up
    activities.processData(data)  // Synchronous call
}
```

**Best practice:** Set a flag in the signal handler, process in main workflow loop:

```kotlin
private val pendingUpdates = mutableListOf<String>()

@SignalMethod
fun queueUpdate(data: String) {
    pendingUpdates.add(data)  // Just record it
}

override fun run() {
    while (!done) {
        Workflow.await { pendingUpdates.isNotEmpty() || done }

        while (pendingUpdates.isNotEmpty()) {
            val update = pendingUpdates.removeFirst()
            activities.processData(update)  // Process in main flow
        }
    }
}
```

### Q: What happens if you send a Signal to a completed workflow?

**A:** The signal is rejected with `WorkflowNotOpenError`.

```kotlin
try {
    workflowStub.sendSignal(data)
} catch (e: WorkflowNotFoundException) {
    // Workflow doesn't exist
} catch (e: WorkflowNotOpenError) {
    // Workflow already completed/failed/cancelled
}
```

---

## 12. Continue-As-New & History Limits

### Q: What is the history size limit and why does it matter?

**A:** Temporal has a default limit of ~50,000 events (configurable). Large histories cause:

- Slower replay times
- More memory usage
- Potential workflow failures if limit exceeded

```
History size over time:
─────────────────────────────────────────────────────────────
Events
50K │                                          ╱ LIMIT HIT
    │                                        ╱   Workflow fails!
40K │                                      ╱
    │                                    ╱
30K │                                  ╱
    │                                ╱
20K │                              ╱
    │                            ╱
10K │                          ╱
    │────────────────────────╱
    └─────────────────────────────────────────────────────────
    0                                                    Time
```

### Q: What is Continue-As-New and when should you use it?

**A:** Continue-As-New completes the current workflow and starts a fresh one with new history.

```kotlin
override fun processItems(items: List<String>, processedCount: Int) {
    var count = processedCount

    items.forEach { item ->
        activities.processItem(item)
        count++

        // Check if we should continue-as-new
        if (Workflow.getInfo().historyLength > 10_000) {
            // Start fresh workflow with remaining items
            val remaining = items.drop(count - processedCount)
            Workflow.continueAsNew(remaining, count)
            // This line never executes - workflow ends here
        }
    }
}
```

**Use Continue-As-New for:**

| Scenario | Why |
|----------|-----|
| Long-running loops | Prevent history growth |
| Periodic/cron workflows | Fresh start each iteration |
| Entity workflows | Process events indefinitely |
| Large batch processing | Reset after N items |

### Q: What happens to workflow state during Continue-As-New?

**A:** Everything is lost except what you explicitly pass as parameters.

```kotlin
// WRONG - state is lost
class MyWorkflowImpl : MyWorkflow {
    private var importantState = mutableMapOf<String, Int>()  // LOST!

    override fun run() {
        // ... do work, modify importantState ...

        Workflow.continueAsNew()  // importantState is gone!
    }
}

// CORRECT - pass state explicitly
class MyWorkflowImpl : MyWorkflow {
    override fun run(state: WorkflowState) {
        val newState = state.copy()

        // ... do work, modify newState ...

        Workflow.continueAsNew(newState)  // State preserved
    }
}
```

### Q: How do Signals and Queries work across Continue-As-New?

**A:** The workflow ID stays the same, so clients can still signal/query. But pending signals at the moment of continue-as-new are passed to the new run.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     CONTINUE-AS-NEW SIGNAL HANDLING                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Run 1 (workflow-123):                                                      │
│    Event 1-10000: ... processing ...                                        │
│    Event 10001: Signal "update-A" received                                  │
│    Event 10002: ContinueAsNewInitiated                                      │
│    ───────────────────────────────────────────                              │
│                                                                             │
│  Run 2 (workflow-123, new run):                                             │
│    Event 1: WorkflowStarted (fresh history!)                                │
│    Event 2: Signal "update-A" delivered (carried over)                      │
│    ... continues processing ...                                             │
│                                                                             │
│  Client perspective:                                                        │
│    - Same workflow ID works                                                 │
│    - Can query current state                                                │
│    - Signals go to latest run                                               │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Q: What is the difference between Continue-As-New and Child Workflow?

| Aspect | Continue-As-New | Child Workflow |
|--------|-----------------|----------------|
| **Workflow ID** | Same | Different |
| **History** | Fresh (old is archived) | Separate from parent |
| **Parent-child relationship** | No parent | Has parent |
| **Use case** | Reset same logical workflow | Decompose into sub-tasks |
| **Failure handling** | Fresh start | Can affect parent |

---

## 13. Activity Heartbeats

### Q: What are Activity Heartbeats and why do we need them?

**A:** Heartbeats are periodic "I'm still alive" signals from long-running activities.

```
Without heartbeats:                   With heartbeats:
─────────────────────                 ────────────────────
Activity starts...                    Activity starts...
  │                                     │
  │ (doing work)                        ├── heartbeat (T=5s)
  │                                     │
  │ (worker crashes!)                   ├── heartbeat (T=10s)
  │                                     │
  │                                     │ (worker crashes!)
  │                                     │
  │                                     X no heartbeat at T=15s
  │
  ▼                                     ▼
Timeout after 5 minutes               Detected in ~10 seconds!
(scheduleToCloseTimeout)              (heartbeatTimeout)
```

### Q: How do you implement heartbeats?

```kotlin
@ActivityImpl
class FileProcessingActivitiesImpl : FileProcessingActivities {

    override fun processLargeFile(filePath: String): ProcessingResult {
        val file = File(filePath)
        val lines = file.readLines()
        var processedLines = 0

        lines.forEach { line ->
            processLine(line)
            processedLines++

            // Send heartbeat with progress
            Activity.getExecutionContext().heartbeat(
                HeartbeatDetails(processedLines, lines.size)
            )
        }

        return ProcessingResult(processedLines)
    }
}
```

**Activity options:**

```kotlin
val activityOptions = ActivityOptions.newBuilder()
    .setStartToCloseTimeout(Duration.ofHours(1))
    .setHeartbeatTimeout(Duration.ofSeconds(30))  // Must heartbeat every 30s
    .build()
```

### Q: What happens when heartbeat timeout is exceeded?

**A:** Temporal considers the activity "stuck" and schedules a retry (if retries are configured).

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     HEARTBEAT TIMEOUT SCENARIO                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Worker 1:                         Temporal Server:                         │
│  ─────────                         ────────────────                         │
│  T=0s:  Start activity                                                      │
│  T=5s:  heartbeat ─────────────►   "Activity alive"                         │
│  T=10s: heartbeat ─────────────►   "Activity alive"                         │
│  T=15s: (stuck in infinite loop)                                            │
│  T=20s: (no heartbeat)                                                      │
│  T=25s: (no heartbeat)                                                      │
│  ...                               T=40s: "No heartbeat for 30s!"           │
│                                           ↓                                 │
│                                    Mark activity as TIMED_OUT               │
│                                    Schedule retry on any worker             │
│                                           ↓                                 │
│  Worker 2:                                                                  │
│  ─────────                                                                  │
│  T=41s: Start activity (retry)                                              │
│  ...                                                                        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Q: How can you resume from heartbeat details after a retry?

**A:** Activity can read the last heartbeat details to skip already-processed work.

```kotlin
override fun processLargeFile(filePath: String): ProcessingResult {
    // Check if this is a retry with previous progress
    val lastHeartbeat = Activity.getExecutionContext()
        .getHeartbeatDetails(HeartbeatDetails::class.java)
        .orElse(null)

    val startLine = lastHeartbeat?.processedLines ?: 0

    val file = File(filePath)
    val lines = file.readLines()
    var processedLines = startLine

    // Skip already processed lines
    lines.drop(startLine).forEach { line ->
        processLine(line)
        processedLines++

        Activity.getExecutionContext().heartbeat(
            HeartbeatDetails(processedLines, lines.size)
        )
    }

    return ProcessingResult(processedLines)
}
```

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     HEARTBEAT RESUME FLOW                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Attempt 1 (Worker 1):                                                      │
│    Process line 1... heartbeat(1)                                           │
│    Process line 2... heartbeat(2)                                           │
│    Process line 3... heartbeat(3)                                           │
│    CRASH!                                                                   │
│                                                                             │
│  Temporal saves: lastHeartbeat = {processedLines: 3}                        │
│                                                                             │
│  Attempt 2 (Worker 2):                                                      │
│    getHeartbeatDetails() → {processedLines: 3}                              │
│    Skip lines 1, 2, 3                                                       │
│    Process line 4... heartbeat(4)                                           │
│    Process line 5... heartbeat(5)                                           │
│    DONE!                                                                    │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Q: Should every activity heartbeat?

**A:** No. Only long-running activities (> 30 seconds) need heartbeats.

| Activity Duration | Heartbeat Needed? |
|-------------------|-------------------|
| < 10 seconds | No |
| 10-60 seconds | Optional (for faster failure detection) |
| > 60 seconds | Yes |
| Minutes to hours | Definitely yes |

---

## 14. Cancellation Patterns

### Q: How does workflow cancellation work?

**A:** When a workflow is cancelled, a `CancellationException` is thrown into the workflow code.

```kotlin
override fun processOrder(orderId: String): OrderResult {
    try {
        activities.reserveInventory(orderId)
        activities.chargePayment(orderId)
        activities.shipOrder(orderId)
        return OrderResult.SUCCESS
    } catch (e: CancellationException) {
        // Workflow was cancelled!
        // Clean up / compensate
        throw e  // Re-throw to complete cancellation
    }
}
```

### Q: How do you handle cleanup during cancellation?

**A:** Use a `CancellationScope` to run cleanup logic even when cancelled.

```kotlin
override fun processOrder(orderId: String): OrderResult {
    var inventoryReserved = false
    var paymentCharged = false

    try {
        activities.reserveInventory(orderId)
        inventoryReserved = true

        activities.chargePayment(orderId)
        paymentCharged = true

        activities.shipOrder(orderId)
        return OrderResult.SUCCESS

    } catch (e: CancellationException) {
        // Run cleanup in a detached scope (won't be cancelled)
        Workflow.newDetachedCancellationScope {
            if (paymentCharged) {
                activities.refundPayment(orderId)
            }
            if (inventoryReserved) {
                activities.releaseInventory(orderId)
            }
        }.run()

        throw e
    }
}
```

### Q: What happens to running activities when a workflow is cancelled?

**A:** By default, Temporal requests cancellation of running activities, but activities must check for it.

```kotlin
// Activity that handles cancellation
override fun longRunningActivity(): Result {
    for (i in 1..1000) {
        // Check if cancellation was requested
        Activity.getExecutionContext().heartbeat(i)

        // If cancelled, heartbeat throws CancellationException
        // (only if activity is configured to do so)

        doSomeWork(i)
    }
}
```

**Activity cancellation behavior:**

```kotlin
val activityOptions = ActivityOptions.newBuilder()
    .setStartToCloseTimeout(Duration.ofMinutes(10))
    .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
    // Options:
    // - TRY_CANCEL: Request cancellation, don't wait (default)
    // - WAIT_CANCELLATION_COMPLETED: Wait for activity to acknowledge
    // - ABANDON: Don't even try to cancel
    .build()
```

### Q: What's the difference between cancellation and termination?

| Aspect | Cancellation | Termination |
|--------|--------------|-------------|
| **Graceful** | Yes | No |
| **Cleanup possible** | Yes | No |
| **Exception thrown** | CancellationException | Nothing (just stops) |
| **History event** | WorkflowCancellationRequested | WorkflowTerminated |
| **Use case** | User-initiated cancel | Admin force-stop |

```kotlin
// Cancel (graceful)
workflowStub.cancel()

// Terminate (forceful)
workflowClient.newUntypedWorkflowStub(workflowId)
    .terminate("Admin force stop")
```

---

## 15. Local Activities vs Regular Activities

### Q: What is a Local Activity?

**A:** A Local Activity executes in the same worker process as the workflow, without scheduling through the Temporal server.

```
Regular Activity:                    Local Activity:
─────────────────                    ────────────────
Workflow (Worker 1)                  Workflow (Worker 1)
      │                                    │
      ▼                                    ▼
Schedule activity ─────►             Execute directly
      │              Temporal        (same process)
      │              Server                │
      ▼                │                   │
Any worker picks ◄─────┘                   │
up and executes                            │
      │                                    │
      ▼                                    ▼
Report result ─────────►             Return result
                       Temporal      (no network)
                       Server
```

### Q: When should you use Local Activities?

| Use Local Activity When | Use Regular Activity When |
|-------------------------|---------------------------|
| Very short operations (< 5s) | Long-running operations |
| Low latency required | Need to survive worker failures |
| High frequency calls | CPU-intensive work |
| Simple operations | Work distribution across workers |

**Examples:**

```kotlin
// GOOD for Local Activity:
// - Input validation
// - Simple transformations
// - Quick cache lookups
// - Formatting data

// BAD for Local Activity:
// - HTTP calls to external services
// - Database writes
// - File processing
// - Anything that might fail/retry
```

### Q: What are the trade-offs of Local Activities?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     LOCAL ACTIVITY TRADE-OFFS                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Advantages:                                                                │
│  ───────────                                                                │
│  ✓ Lower latency (no server round-trip)                                     │
│  ✓ Less load on Temporal server                                             │
│  ✓ Smaller history (batched into single event)                              │
│                                                                             │
│  Disadvantages:                                                             │
│  ──────────────                                                             │
│  ✗ No automatic load balancing across workers                               │
│  ✗ If worker dies, local activity is lost (retried from scratch)            │
│  ✗ No visibility in Temporal UI for individual local activities             │
│  ✗ Limited timeout (should complete quickly)                                │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Q: How do you define a Local Activity?

```kotlin
// Same interface as regular activity
interface ValidationActivities {
    fun validateInput(input: OrderInput): ValidationResult
}

// In workflow, use LocalActivityOptions:
private val localActivities = Workflow.newLocalActivityStub(
    ValidationActivities::class.java,
    LocalActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(5))
        .build()
)

// Usage is identical:
override fun processOrder(input: OrderInput): OrderResult {
    val validation = localActivities.validateInput(input)  // Local!

    if (validation.isValid) {
        regularActivities.processPayment(input)  // Regular activity
    }
}
```

---

## 16. Side Effects & Mutable Side Effects

### Q: What is `Workflow.sideEffect()` and when do you use it?

**A:** `sideEffect()` lets you execute non-deterministic code (like generating UUIDs) safely within a workflow.

```kotlin
// WRONG - Non-deterministic!
val requestId = UUID.randomUUID().toString()  // Different on replay!

// CORRECT - Using sideEffect
val requestId = Workflow.sideEffect(String::class.java) {
    UUID.randomUUID().toString()
}
// Result is recorded in history, same value on replay
```

### Q: How does sideEffect differ from an Activity?

| Aspect | sideEffect | Activity |
|--------|------------|----------|
| **Execution** | In workflow thread | On any worker |
| **Retries** | No retries | Configurable retries |
| **Timeout** | No timeout | Configurable timeout |
| **History event** | SideEffectRecorded | ActivityScheduled/Completed |
| **Use case** | UUID, random, timestamps | I/O, external calls |

```kotlin
// Use sideEffect for:
val uuid = Workflow.sideEffect(String::class.java) { UUID.randomUUID().toString() }
val random = Workflow.sideEffect(Int::class.java) { Random.nextInt(100) }
val timestamp = Workflow.sideEffect(Long::class.java) { System.currentTimeMillis() }

// Use Activity for:
val user = activities.fetchUser(userId)  // HTTP call
val result = activities.processPayment(data)  // External service
```

### Q: What is `mutableSideEffect()` and how is it different?

**A:** `mutableSideEffect()` can be called multiple times and only records a new value if it changed.

```kotlin
// Regular sideEffect - called once, recorded once
val initialTimestamp = Workflow.sideEffect(Long::class.java) {
    System.currentTimeMillis()
}

// Mutable sideEffect - can update over time
while (!done) {
    val currentConfig = Workflow.mutableSideEffect(
        "config-version",
        Config::class.java,
        { oldConfig, newConfig -> oldConfig != newConfig }  // Equality check
    ) {
        configService.getCurrentConfig()  // Re-evaluated each time
    }

    processWithConfig(currentConfig)
    Workflow.sleep(Duration.ofMinutes(1))
}
```

**When to use:**

| `sideEffect` | `mutableSideEffect` |
|--------------|---------------------|
| One-time value needed | Value may change over workflow lifetime |
| UUID generation | Configuration that updates |
| Initial timestamp | Feature flags |

---

## 17. Sticky Execution & Workflow Caching

### Q: What is Sticky Execution?

**A:** Sticky execution keeps a workflow cached on the same worker to avoid full replay every time.

```
Without Sticky Execution:            With Sticky Execution (default):
─────────────────────────            ────────────────────────────────
Activity completes                   Activity completes
      │                                    │
      ▼                                    ▼
Workflow task created                Workflow task created
      │                                    │
      ▼                                    ▼
Any worker picks up                  Same worker picks up (sticky)
      │                                    │
      ▼                                    ▼
FULL REPLAY from event 1             Continue from cached state
(could be 10,000 events!)            (no replay needed!)
      │                                    │
      ▼                                    ▼
Continue workflow                    Continue workflow
```

### Q: How does the sticky queue work?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     STICKY TASK QUEUE MECHANISM                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Worker 1                          Temporal Server                          │
│  ─────────                         ───────────────                          │
│  Has workflow-123 cached           Task Queues:                             │
│                                      │                                      │
│                                      ├── "payment-processing" (normal)      │
│                                      │     └── Any worker polls this        │
│                                      │                                      │
│                                      └── "worker-1-sticky-xyz" (sticky)     │
│                                            └── Only Worker 1 polls this     │
│                                                                             │
│  When workflow-123 needs to continue:                                       │
│    1. Server puts task in sticky queue first                                │
│    2. Worker 1 has ~10s to pick it up                                       │
│    3. If Worker 1 picks up → no replay (cached)                             │
│    4. If timeout → task moves to normal queue                               │
│       → Any worker picks up → full replay                                   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Q: How does Temporal Server know which worker has the workflow cached?

**A:** The worker **tells** Temporal Server after each workflow task completion. This is tracked via the sticky queue assignment.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│              HOW TEMPORAL SERVER TRACKS STICKY ASSIGNMENT                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Step 1: Worker completes workflow task                                     │
│  ──────────────────────────────────────                                     │
│                                                                             │
│  Worker 1 finishes workflow task for workflow-123                           │
│          │                                                                  │
│          ▼                                                                  │
│  Worker 1 sends response to Temporal Server:                                │
│  {                                                                          │
│    "workflowTaskCompleted": true,                                           │
│    "commands": [...],                                                       │
│    "stickyAttributes": {                                                    │
│      "workerTaskQueue": "payment-processing-worker1-abc123-sticky",         │
│      "scheduleToStartTimeout": "5s"                                         │
│    }                                                                        │
│  }                                                                          │
│          │                                                                  │
│          ▼                                                                  │
│  Temporal Server records in sticky assignment table:                        │
│  ┌─────────────────────────────────────────────────────┐                   │
│  │  workflow-123 → sticky queue: worker1-abc123-sticky │                   │
│  └─────────────────────────────────────────────────────┘                   │
│                                                                             │
│                                                                             │
│  Step 2: Next task (or query) for this workflow                             │
│  ──────────────────────────────────────────────────                         │
│                                                                             │
│  Activity completes (or query arrives)                                      │
│          │                                                                  │
│          ▼                                                                  │
│  Temporal checks: "Does workflow-123 have a sticky assignment?"             │
│          │                                                                  │
│          ▼                                                                  │
│  YES → Put task in "worker1-abc123-sticky" queue                            │
│        (only Worker 1 polls this private queue)                             │
│          │                                                                  │
│          ▼                                                                  │
│  Worker 1 picks up → Uses cached state → Fast!                              │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Q: How are queries routed to the correct worker?

**A:** Queries follow the same sticky routing as workflow tasks.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         QUERY ROUTING DECISION                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Client sends query for workflow-123                                        │
│                │                                                            │
│                ▼                                                            │
│  Temporal Server checks sticky assignment table:                            │
│    workflow-123 → "worker1-abc123-sticky"                                   │
│                │                                                            │
│                ▼                                                            │
│          Has sticky assignment?                                             │
│                │                                                            │
│         ┌──────┴──────┐                                                     │
│        YES            NO                                                    │
│         │              │                                                    │
│         ▼              ▼                                                    │
│  Send query to      Send query to                                           │
│  Worker 1's         normal queue                                            │
│  sticky queue       (any worker)                                            │
│         │              │                                                    │
│         ▼              ▼                                                    │
│  Worker 1           Random worker                                           │
│  responds           must REPLAY first                                       │
│  (cached, fast)     (slow)                                                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Key insight:** Queries are **NOT random** — Temporal actively tracks which worker last handled each workflow and routes queries to that worker first.

### Q: What happens if the sticky worker is dead but Temporal doesn't know yet?

**A:** Temporal uses a **timeout fallback** mechanism.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    STICKY TIMEOUT FALLBACK                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  T0: Query arrives for workflow-123                                         │
│          │                                                                  │
│          ▼                                                                  │
│  T0: Temporal sends to Worker 1's sticky queue                              │
│          │                                                                  │
│          ▼                                                                  │
│  T0-T5: Waiting for Worker 1 to respond...                                  │
│         (Worker 1 is actually dead, but Temporal doesn't know)              │
│          │                                                                  │
│          ▼                                                                  │
│  T5: STICKY TIMEOUT (default 5 seconds)                                     │
│      Worker 1 didn't pick up in time                                        │
│          │                                                                  │
│          ▼                                                                  │
│  T5: Temporal CLEARS sticky assignment:                                     │
│      workflow-123 → (no sticky)                                             │
│          │                                                                  │
│          ▼                                                                  │
│  T5: Resend query to NORMAL queue                                           │
│          │                                                                  │
│          ▼                                                                  │
│  T5+: Any available worker picks up                                         │
│          │                                                                  │
│          ▼                                                                  │
│  Worker 2 REPLAYS entire history and responds                               │
│          │                                                                  │
│          ▼                                                                  │
│  Temporal records new sticky assignment:                                    │
│      workflow-123 → worker2-sticky                                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Q: What is the performance impact of cached vs uncached queries?

**A:** The difference can be dramatic, especially for workflows with large histories.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    QUERY PERFORMANCE: CACHED vs UNCACHED                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Example: Workflow with 10,000 events                                       │
│                                                                             │
│  CACHED query (sticky worker responds):                                     │
│    └── Read from memory: ~10ms                                              │
│                                                                             │
│  UNCACHED query (any worker, must replay):                                  │
│    ├── Load 10,000 events from DB: ~100ms                                   │
│    ├── Deserialize events: ~50ms                                            │
│    ├── Replay workflow code: ~200ms (10,000 SDK calls)                      │
│    └── Total: ~350ms+                                                       │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  Example: Workflow with 50,000 events (near limit)                          │
│                                                                             │
│  CACHED query:                                                              │
│    └── Read from memory: ~10ms                                              │
│                                                                             │
│  UNCACHED query:                                                            │
│    ├── Load 50,000 events: ~500ms                                           │
│    ├── Deserialize: ~250ms                                                  │
│    ├── Replay: ~1-2 seconds                                                 │
│    └── Total: 2-3 seconds!                                                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

Summary:
─────────────────────────────────────────────────────────────────────────
│ Scenario                    │ Cache Status │ Latency        │
─────────────────────────────────────────────────────────────────────────
│ Query right after activity  │ Likely cached│ Fast (~10ms)   │
│ Query after worker restart  │ Not cached   │ Slow (100ms+)  │
│ Query after long idle       │ May evict    │ Slow           │
│ Query with 50K events       │ Not cached   │ Very slow (s)  │
─────────────────────────────────────────────────────────────────────────
```

### Q: When does sticky execution fail and what happens?

**A:** Sticky execution fails when:

1. **Worker dies/restarts** - Cache is lost
2. **Cache eviction** - Too many workflows, LRU eviction
3. **Sticky timeout** - Worker too slow to pick up task
4. **Deployment** - New worker version

```
Sticky failure scenario:
────────────────────────
T0: Workflow-123 running on Worker 1, cached
T1: Worker 1 crashes
T2: Activity completes, workflow task created
T3: Server puts task in Worker 1's sticky queue
T4: 10 seconds pass, no response from Worker 1
T5: Task moved to normal queue
T6: Worker 2 picks up task
T7: Worker 2 does FULL REPLAY (no cache)
T8: Workflow-123 now cached on Worker 2
```

### Q: How do you tune the workflow cache?

```kotlin
val workerOptions = WorkerOptions.newBuilder()
    .setMaxConcurrentWorkflowTaskExecutionSize(200)  // Max concurrent workflow tasks
    .setStickyQueueScheduleToStartTimeout(Duration.ofSeconds(5))  // Sticky timeout
    .build()

// WorkerFactory level settings affect cache size
val workerFactoryOptions = WorkerFactoryOptions.newBuilder()
    .setWorkflowCacheSize(600)  // Number of cached workflows
    .build()
```

**Cache eviction impact:**

```
Cache size: 100 workflows
Active workflows: 500

Result:
  - Only 100 can be sticky at a time
  - Other 400 will replay when continued
  - LRU eviction kicks out least recently used

Symptoms of too-small cache:
  - High replay counts in metrics
  - Slower workflow task completion
  - Higher CPU usage on workers
```

---

## 18. Timers, Deadlines & Await Patterns

### Q: What's the difference between `Workflow.sleep()` and `Workflow.await()`?

| `Workflow.sleep()` | `Workflow.await()` |
|--------------------|--------------------|
| Waits for fixed duration | Waits for condition to become true |
| Always waits full duration | Returns immediately if condition is true |
| Simple timer | Condition-based waiting |

```kotlin
// sleep - wait exactly 5 minutes
Workflow.sleep(Duration.ofMinutes(5))

// await - wait until condition is true
Workflow.await { orderApproved || orderCancelled }

// await with timeout - wait for condition OR timeout
val approved = Workflow.await(
    Duration.ofMinutes(30)
) { orderApproved }
// returns false if timeout, true if condition met
```

### Q: How do you implement a deadline pattern?

```kotlin
override fun processOrderWithDeadline(orderId: String): OrderResult {
    val deadline = Workflow.currentTimeMillis() + Duration.ofHours(24).toMillis()

    // Start async processing
    val paymentPromise = Async.function { activities.processPayment(orderId) }
    val shippingPromise = Async.function { activities.prepareShipping(orderId) }

    // Wait for both OR deadline
    val completed = Workflow.await(Duration.ofHours(24)) {
        paymentPromise.isCompleted && shippingPromise.isCompleted
    }

    if (!completed) {
        // Deadline exceeded
        Workflow.newDetachedCancellationScope {
            activities.cancelOrder(orderId)
            activities.refundIfNeeded(orderId)
        }.run()
        return OrderResult.DEADLINE_EXCEEDED
    }

    return OrderResult.SUCCESS
}
```

### Q: How do you implement a polling pattern with backoff?

```kotlin
override fun waitForExternalApproval(requestId: String): ApprovalResult {
    var attempts = 0
    val maxAttempts = 10

    while (attempts < maxAttempts) {
        val status = activities.checkApprovalStatus(requestId)

        when (status) {
            "APPROVED" -> return ApprovalResult.APPROVED
            "REJECTED" -> return ApprovalResult.REJECTED
            "PENDING" -> {
                attempts++
                // Exponential backoff: 1min, 2min, 4min, 8min...
                val waitTime = Duration.ofMinutes(1L shl (attempts - 1).coerceAtMost(4))
                Workflow.sleep(waitTime)
            }
        }
    }

    return ApprovalResult.TIMEOUT
}
```

### Q: How do you handle a timeout for a child workflow?

```kotlin
val childOptions = ChildWorkflowOptions.newBuilder()
    .setWorkflowId("child-${Workflow.getInfo().workflowId}")
    .setWorkflowExecutionTimeout(Duration.ofHours(1))  // Total time
    .setWorkflowRunTimeout(Duration.ofMinutes(30))     // Per run (before continue-as-new)
    .setWorkflowTaskTimeout(Duration.ofSeconds(10))    // Per task
    .build()

val childWorkflow = Workflow.newChildWorkflowStub(
    ChildWorkflow::class.java,
    childOptions
)

try {
    val result = childWorkflow.process(data)
} catch (e: ChildWorkflowFailure) {
    if (e.cause is TimeoutFailure) {
        // Child workflow timed out
        handleTimeout()
    }
}
```

---

## 19. Testing Workflows

### Q: How do you unit test a Temporal workflow?

**A:** Use `TestWorkflowEnvironment` for isolated testing with time control.

```kotlin
class PaymentWorkflowTest {
    private lateinit var testEnv: TestWorkflowEnvironment
    private lateinit var worker: Worker
    private lateinit var workflowClient: WorkflowClient

    @BeforeEach
    fun setup() {
        testEnv = TestWorkflowEnvironment.newInstance()
        worker = testEnv.newWorker("payment-queue")

        // Register workflow
        worker.registerWorkflowImplementationTypes(PaymentWorkflowImpl::class.java)

        // Mock activities
        val mockActivities = mock<PaymentActivities>()
        whenever(mockActivities.processPayment(any())).thenReturn(PaymentResult.SUCCESS)
        worker.registerActivitiesImplementations(mockActivities)

        workflowClient = testEnv.workflowClient
        testEnv.start()
    }

    @Test
    fun `test successful payment`() {
        val workflow = workflowClient.newWorkflowStub(
            PaymentWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue("payment-queue")
                .build()
        )

        val result = workflow.processPayment("order-123")

        assertEquals(PaymentResult.SUCCESS, result)
    }

    @AfterEach
    fun teardown() {
        testEnv.close()
    }
}
```

### Q: How do you test workflows with timers (sleep/await)?

**A:** Use time skipping to avoid waiting for real time.

```kotlin
@Test
fun `test workflow with 24h timeout`() {
    val workflow = workflowClient.newWorkflowStub(
        ApprovalWorkflow::class.java,
        WorkflowOptions.newBuilder()
            .setTaskQueue("approval-queue")
            .build()
    )

    // Start workflow async
    WorkflowClient.start(workflow::waitForApproval, "request-123")

    // Skip time by 24 hours (instant, no real waiting)
    testEnv.sleep(Duration.ofHours(24))

    // Workflow should have timed out
    val result = workflow.getResult()
    assertEquals(ApprovalResult.TIMEOUT, result)
}
```

### Q: How do you test signal handling?

```kotlin
@Test
fun `test signal triggers processing`() {
    val workflow = workflowClient.newWorkflowStub(
        OrderWorkflow::class.java,
        WorkflowOptions.newBuilder()
            .setTaskQueue("order-queue")
            .build()
    )

    // Start workflow
    val execution = WorkflowClient.start(workflow::processOrder, "order-123")

    // Send signal
    workflow.approveOrder("manager-1")

    // Get result
    val result = workflowClient
        .newUntypedWorkflowStub(execution.workflowId)
        .getResult(OrderResult::class.java)

    assertEquals(OrderStatus.APPROVED, result.status)
}
```

### Q: How do you test activity retries?

```kotlin
@Test
fun `test activity retries on failure`() {
    val mockActivities = mock<PaymentActivities>()

    // Fail twice, succeed on third attempt
    whenever(mockActivities.processPayment(any()))
        .thenThrow(RuntimeException("Connection failed"))
        .thenThrow(RuntimeException("Connection failed"))
        .thenReturn(PaymentResult.SUCCESS)

    worker.registerActivitiesImplementations(mockActivities)

    val workflow = workflowClient.newWorkflowStub(PaymentWorkflow::class.java, options)
    val result = workflow.processPayment("order-123")

    assertEquals(PaymentResult.SUCCESS, result)
    verify(mockActivities, times(3)).processPayment(any())  // Called 3 times
}
```

---

## 20. Worker Tuning & Performance

### Q: What are the key worker configuration options?

```kotlin
val workerOptions = WorkerOptions.newBuilder()
    // Concurrent execution limits
    .setMaxConcurrentActivityExecutionSize(100)      // Max parallel activities
    .setMaxConcurrentWorkflowTaskExecutionSize(50)   // Max parallel workflow tasks
    .setMaxConcurrentLocalActivityExecutionSize(100) // Max parallel local activities

    // Polling configuration
    .setMaxConcurrentActivityTaskPollers(5)          // Threads polling for activities
    .setMaxConcurrentWorkflowTaskPollers(2)          // Threads polling for workflows

    // Sticky execution
    .setStickyQueueScheduleToStartTimeout(Duration.ofSeconds(5))

    .build()
```

### Q: How do you diagnose worker performance issues?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     PERFORMANCE TROUBLESHOOTING                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Symptom                          │  Likely Cause              │  Fix       │
│  ────────────────────────────────────────────────────────────────────────── │
│  High workflow task latency       │  Cache too small           │  ↑ cache   │
│                                   │  (lots of replays)         │            │
│  ────────────────────────────────────────────────────────────────────────── │
│  Activity tasks queuing up        │  MaxConcurrent too low     │  ↑ limit   │
│                                   │  Not enough workers        │  + workers │
│  ────────────────────────────────────────────────────────────────────────── │
│  Worker OOM                       │  Cache too large           │  ↓ cache   │
│                                   │  Too many concurrent       │  ↓ limits  │
│  ────────────────────────────────────────────────────────────────────────── │
│  Sticky miss rate high            │  Worker restarts           │  Fix code  │
│                                   │  Cache eviction            │  ↑ cache   │
│  ────────────────────────────────────────────────────────────────────────── │
│  Temporal server high latency     │  Too many small activities │  Batch or  │
│                                   │  High polling rate         │  local act │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Q: When should you scale horizontally (more workers) vs vertically (bigger workers)?

| Scale Horizontally (More Workers) | Scale Vertically (Bigger Workers) |
|-----------------------------------|-----------------------------------|
| Activity-bound workloads | Workflow-bound workloads (complex replay) |
| Need redundancy | Memory-constrained (larger cache) |
| Variable load patterns | Consistent high load |
| Different activity types | Single workflow type dominates |

```
Horizontal scaling:                  Vertical scaling:
───────────────────                  ───────────────────

┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐     ┌───────────────────┐
│ W1  │ │ W2  │ │ W3  │ │ W4  │     │                   │
│small│ │small│ │small│ │small│     │    Big Worker     │
└─────┘ └─────┘ └─────┘ └─────┘     │   (more memory,   │
                                     │    larger cache)  │
Activity throughput: 4x              └───────────────────┘
Redundancy: High
                                     Workflow throughput: 1x
                                     Cache: Large (less replay)
```

---

## 21. Child Workflow vs Activity Decision

### Q: When should you use a Child Workflow instead of an Activity?

| Use Child Workflow | Use Activity |
|--------------------|--------------|
| Need independent retry/timeout | Simple request-response |
| Long-running sub-process | Short operation (< 1 min) |
| Has its own lifecycle | Stateless operation |
| Needs signals/queries | Just needs to return result |
| Failure should be isolated | Failure can propagate |
| Has multiple steps internally | Single atomic operation |

### Q: Decision flowchart

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     CHILD WORKFLOW VS ACTIVITY DECISION                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Does the operation need its own retry/timeout policy?                      │
│       │                                                                     │
│      YES ──────────► Consider CHILD WORKFLOW                                │
│       │                                                                     │
│      NO                                                                     │
│       │                                                                     │
│       ▼                                                                     │
│  Does it have multiple steps that need orchestration?                       │
│       │                                                                     │
│      YES ──────────► CHILD WORKFLOW                                         │
│       │                                                                     │
│      NO                                                                     │
│       │                                                                     │
│       ▼                                                                     │
│  Does it need to receive signals or respond to queries?                     │
│       │                                                                     │
│      YES ──────────► CHILD WORKFLOW                                         │
│       │                                                                     │
│      NO                                                                     │
│       │                                                                     │
│       ▼                                                                     │
│  Is it a long-running process (minutes to hours)?                           │
│       │                                                                     │
│      YES ──────────► CHILD WORKFLOW (with heartbeats if activity)           │
│       │                                                                     │
│      NO                                                                     │
│       │                                                                     │
│       ▼                                                                     │
│  ACTIVITY is probably the right choice                                      │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Q: What are the trade-offs?

```
Child Workflow:                      Activity:
───────────────                      ─────────
✓ Independent lifecycle              ✓ Simpler
✓ Can receive signals                ✓ Less overhead
✓ Own retry policy                   ✓ Easier to test
✓ Cleaner failure isolation          ✓ Lower latency
✓ Can continue-as-new                ✓ Direct result return

✗ More overhead                      ✗ No internal orchestration
✗ Separate history                   ✗ No signal support
✗ More complex setup                 ✗ Shared retry policy
✗ Parent-child relationship          ✗ No queries
```

### Q: Example comparison

```kotlin
// ACTIVITY approach - simple HTTP call
interface PaymentActivities {
    fun chargeCard(paymentId: String, amount: Long): ChargeResult
}

// Usage in workflow:
val result = activities.chargeCard("pay-123", 5000)

// ───────────────────────────────────────────────────────────

// CHILD WORKFLOW approach - complex payment process
interface PaymentWorkflow {
    @WorkflowMethod
    fun processPayment(paymentId: String, amount: Long): PaymentResult

    @SignalMethod
    fun cancelPayment()

    @QueryMethod
    fun getPaymentStatus(): PaymentStatus
}

// Implementation has multiple steps:
class PaymentWorkflowImpl : PaymentWorkflow {
    override fun processPayment(paymentId: String, amount: Long): PaymentResult {
        activities.validatePayment(paymentId)
        activities.reserveFunds(paymentId, amount)

        // Wait for external approval (could take hours)
        val approved = Workflow.await(Duration.ofHours(24)) { approvalReceived }

        if (approved) {
            activities.captureFunds(paymentId)
            activities.sendReceipt(paymentId)
        } else {
            activities.releaseFunds(paymentId)
        }

        return result
    }
}

// Usage as child workflow:
val childPayment = Workflow.newChildWorkflowStub(PaymentWorkflow::class.java, options)
val promise = Async.function { childPayment.processPayment("pay-123", 5000) }

// Can query child's status:
val status = childPayment.getPaymentStatus()

// Can cancel child:
childPayment.cancelPayment()
```