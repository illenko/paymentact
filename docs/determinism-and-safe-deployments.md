# Determinism and Safe Deployments in Temporal

This document explains workflow determinism, why it matters, and how to safely deploy changes to workflows and activities in production.

## Table of Contents

1. [What is Determinism?](#what-is-determinism)
2. [Why Determinism Matters](#why-determinism-matters)
3. [Common Determinism Violations](#common-determinism-violations)
4. [Modifying Activities (Safe)](#modifying-activities-safe)
5. [Modifying Workflows (Dangerous)](#modifying-workflows-dangerous)
6. [Versioning with Workflow.getVersion()](#versioning-with-workflowgetversion)
7. [Patching Strategy](#patching-strategy)
8. [Safe Deployment Patterns](#safe-deployment-patterns)
9. [Migration Strategies](#migration-strategies)

---

## What is Determinism?

A **deterministic** function always produces the same output given the same input, with no side effects.

```kotlin
// DETERMINISTIC - same input always gives same output
fun add(a: Int, b: Int): Int = a + b

// NON-DETERMINISTIC - output varies
fun randomNumber(): Int = Random.nextInt()
fun currentTime(): Long = System.currentTimeMillis()
fun fetchFromApi(): String = httpClient.get(url)
```

**Temporal Workflow Requirement:** Workflow code MUST be deterministic because Temporal replays workflow history to rebuild state after failures.

---

## Why Determinism Matters

### The Replay Model

```
Original Execution:                    Replay After Crash:
─────────────────────                  ─────────────────────
1. Start workflow                      1. Start workflow
2. Call activity A → result "foo"      2. Call activity A → (from history: "foo")
3. Call activity B → result "bar"      3. Call activity B → (from history: "bar")
4. if (result == "bar") {              4. if (result == "bar") {  ← MUST match!
5.   Call activity C                   5.   Call activity C → (from history)
6. }                                   6. }
7. Return result                       7. Return result
```

**During replay:**
- Temporal doesn't re-execute activities
- It reads results from history
- Workflow code runs again to rebuild state
- Code MUST make the same decisions as before

### What Happens on Non-Determinism

```
Original:                              Replay (with modified code):
─────────────────────                  ─────────────────────
1. if (config.featureEnabled) {        1. if (config.featureEnabled) {
2.   activity.doA()  ← executed        2.   // featureEnabled changed!
3. }                                   3.   // skips doA()
4. activity.doB()                      4. activity.doB()
                                            ↑
                                       History expects doA() here!

                                       💥 NON-DETERMINISM ERROR
                                       Workflow fails with:
                                       "NonDeterministicException"
```

---

## Common Determinism Violations

### ❌ DON'T: Use System Time

```kotlin
// BAD - time changes on replay
val deadline = System.currentTimeMillis() + 3600000

// GOOD - Temporal's deterministic time
val deadline = Workflow.currentTimeMillis() + 3600000
```

### ❌ DON'T: Use Random

```kotlin
// BAD - different value on replay
val id = UUID.randomUUID()

// GOOD - Temporal's deterministic random
val id = Workflow.randomUUID()
```

### ❌ DON'T: Use Thread.sleep

```kotlin
// BAD - not tracked in history
Thread.sleep(5000)

// GOOD - creates a timer event in history
Workflow.sleep(Duration.ofSeconds(5))
```

### ❌ DON'T: Make Network Calls

```kotlin
// BAD - result varies, not in history
val config = httpClient.get("/config")

// GOOD - use an activity (result stored in history)
val config = configActivities.fetchConfig()
```

### ❌ DON'T: Use Mutable Static State

```kotlin
// BAD - shared state between workflow instances
companion object {
    var counter = 0  // Different on replay!
}

// GOOD - use workflow instance state
private var counter = 0
```

### ❌ DON'T: Iterate Over HashMap

```kotlin
// BAD - iteration order not guaranteed
for ((key, value) in hashMap) {
    activities.process(key)  // Order may differ on replay!
}

// GOOD - use LinkedHashMap or sort keys
for ((key, value) in linkedHashMap) {
    activities.process(key)
}
// OR
for (key in hashMap.keys.sorted()) {
    activities.process(key, hashMap[key]!!)
}
```

---

## Modifying Activities (Safe)

**Activities are safe to modify** because their results are stored in history. The activity code itself is not replayed.

### Safe Activity Changes

```kotlin
// BEFORE
class ElasticsearchActivitiesImpl : ElasticsearchActivities {
    override fun getGatewayForPayment(paymentId: String): GatewayInfo {
        return restClient.get().uri("/payments/$paymentId").retrieve()...
    }
}

// AFTER - Safe to deploy!
class ElasticsearchActivitiesImpl : ElasticsearchActivities {
    override fun getGatewayForPayment(paymentId: String): GatewayInfo {
        // Changed implementation - added caching, logging, different endpoint
        logger.info("Looking up payment: $paymentId")
        val cached = cache.get(paymentId)
        if (cached != null) return cached

        return restClient.get().uri("/v2/payments/$paymentId").retrieve()...
    }
}
```

**Why safe?**
- Running workflows have activity results in history
- New activity code only affects NEW activity executions
- Replaying workflows use historical results, not new code

### Activity Signature Changes (Careful!)

```kotlin
// BEFORE
fun getGatewayForPayment(paymentId: String): GatewayInfo

// AFTER - Adding parameter with default is SAFE
fun getGatewayForPayment(paymentId: String, includeMetadata: Boolean = false): GatewayInfo

// AFTER - Changing return type is DANGEROUS
fun getGatewayForPayment(paymentId: String): GatewayInfoV2  // 💥 Breaks deserialization!
```

**Safe signature changes:**
- Add optional parameters with defaults
- Add new methods

**Unsafe signature changes:**
- Change return type
- Remove parameters
- Rename method (it's a different activity!)

---

## Modifying Workflows (Dangerous)

**Workflow code changes are dangerous** because the code is replayed from history.

### Dangerous Changes

```kotlin
// BEFORE
override fun checkPaymentStatuses(input: Input): Result {
    val gateways = lookupGateways(input.paymentIds)
    val results = processGateways(gateways)
    return aggregateResults(results)
}

// AFTER - DANGEROUS! Changes execution order
override fun checkPaymentStatuses(input: Input): Result {
    validateInput(input)  // 💥 New activity call - not in history!
    val gateways = lookupGateways(input.paymentIds)
    val results = processGateways(gateways)
    sendNotification(results)  // 💥 New activity call!
    return aggregateResults(results)
}
```

### What Breaks

| Change | Impact |
|--------|--------|
| Add activity call | History mismatch - workflow fails |
| Remove activity call | History mismatch - workflow fails |
| Reorder activity calls | History mismatch - workflow fails |
| Change activity arguments | May cause issues depending on serialization |
| Add/remove child workflow | History mismatch - workflow fails |
| Change conditional logic | May take different branch than history |

---

## Versioning with Workflow.getVersion()

Temporal provides `Workflow.getVersion()` to safely introduce changes to running workflows.

### Basic Usage

```kotlin
override fun checkPaymentStatuses(input: Input): Result {
    val version = Workflow.getVersion(
        "add-validation",      // Change ID (unique identifier)
        Workflow.DEFAULT_VERSION,  // Min supported version
        1                      // Max (current) version
    )

    if (version >= 1) {
        // New code path - only for new executions
        validateInput(input)
    }

    // Original code continues...
    val gateways = lookupGateways(input.paymentIds)
    return processGateways(gateways)
}
```

### How It Works

```
Existing workflow (started before deploy):
──────────────────────────────────────────
History has no "add-validation" marker
  → getVersion() returns DEFAULT_VERSION (-1)
  → Skips validation
  → Follows original code path ✓

New workflow (started after deploy):
──────────────────────────────────────────
First execution, no history yet
  → getVersion() returns 1 (max version)
  → Records version 1 in history
  → Runs validation
  → On replay: reads version 1 from history ✓
```

### Multiple Versions Over Time

```kotlin
override fun checkPaymentStatuses(input: Input): Result {
    // Version 1: Added validation
    val v1 = Workflow.getVersion("add-validation", Workflow.DEFAULT_VERSION, 2)
    if (v1 >= 1) {
        validateInput(input)
    }

    val gateways = lookupGateways(input.paymentIds)

    // Version 2: Added deduplication
    val v2 = Workflow.getVersion("add-dedup", Workflow.DEFAULT_VERSION, 1)
    if (v2 >= 1) {
        deduplicateGateways(gateways)
    }

    return processGateways(gateways)
}
```

### Removing Old Version Code

Once ALL workflows using old version have completed:

```kotlin
// BEFORE: Supporting both versions
val version = Workflow.getVersion("add-validation", Workflow.DEFAULT_VERSION, 1)
if (version >= 1) {
    validateInput(input)
}

// AFTER: All old workflows completed, bump minimum version
val version = Workflow.getVersion("add-validation", 1, 1)  // Min is now 1
validateInput(input)  // Always runs

// LATER: Remove versioning entirely (if no workflows from this era exist)
validateInput(input)
```

---

## Patching Strategy

### The Patch Pattern

For complex changes, use a systematic approach:

```kotlin
// Step 1: Add patch check
val useNewLogic = Workflow.getVersion("new-chunking-logic", Workflow.DEFAULT_VERSION, 1) >= 1

// Step 2: Branch based on version
val chunks = if (useNewLogic) {
    // New implementation
    smartChunking(payments, config.dynamicChunkSize)
} else {
    // Old implementation (keep for running workflows)
    payments.chunked(5)
}

// Step 3: Continue with common code
processChunks(chunks)
```

### Real Example: Changing Chunk Size

```kotlin
// Original code
val chunks = payments.chunked(5)

// After change - WRONG (breaks running workflows)
val chunks = payments.chunked(10)  // 💥 Different chunk boundaries!

// After change - CORRECT (using versioning)
val version = Workflow.getVersion("chunk-size-v2", Workflow.DEFAULT_VERSION, 1)
val chunkSize = if (version >= 1) 10 else 5
val chunks = payments.chunked(chunkSize)
```

---

## Safe Deployment Patterns

### Pattern 1: Blue-Green with Task Queues

```
                    ┌─────────────────┐
                    │ Temporal Server │
                    └────────┬────────┘
                             │
              ┌──────────────┴──────────────┐
              │                             │
    ┌─────────┴─────────┐         ┌────────┴────────┐
    │ Task Queue: v1    │         │ Task Queue: v2  │
    └─────────┬─────────┘         └────────┬────────┘
              │                             │
    ┌─────────┴─────────┐         ┌────────┴────────┐
    │ Workers v1        │         │ Workers v2      │
    │ (old code)        │         │ (new code)      │
    └───────────────────┘         └─────────────────┘
```

**Steps:**
1. Deploy new workers on new task queue (`payment-check-v2`)
2. Update client to start NEW workflows on v2 queue
3. Old workers continue processing existing workflows on v1
4. Once v1 queue is drained, decommission v1 workers

```kotlin
// Client configuration
val taskQueue = if (featureFlag.useV2) {
    "payment-check-v2"
} else {
    "payment-check-v1"
}
```

### Pattern 2: Rolling Deploy with Versioning

```
Timeline:
─────────────────────────────────────────────────────────────────────

T0: All workers on version 1
    ┌────────┐ ┌────────┐ ┌────────┐
    │ W1 v1  │ │ W2 v1  │ │ W3 v1  │
    └────────┘ └────────┘ └────────┘

T1: Deploy starts, mixed versions (safe with getVersion())
    ┌────────┐ ┌────────┐ ┌────────┐
    │ W1 v2  │ │ W2 v1  │ │ W3 v1  │
    └────────┘ └────────┘ └────────┘

T2: Deploy continues
    ┌────────┐ ┌────────┐ ┌────────┐
    │ W1 v2  │ │ W2 v2  │ │ W3 v1  │
    └────────┘ └────────┘ └────────┘

T3: Deploy complete
    ┌────────┐ ┌────────┐ ┌────────┐
    │ W1 v2  │ │ W2 v2  │ │ W3 v2  │
    └────────┘ └────────┘ └────────┘
```

**Requirements:**
- Code must use `Workflow.getVersion()` for all changes
- Both old and new code paths must exist during transition

### Pattern 3: Worker Versioning (Build ID Based)

Temporal 1.21+ supports build ID based versioning:

```kotlin
// Worker registration with build ID
val worker = factory.newWorker(
    taskQueue,
    WorkerOptions.newBuilder()
        .setBuildId("v2.3.1")
        .setUseBuildIdForVersioning(true)
        .build()
)
```

```bash
# Tell Temporal which build IDs are compatible
temporal task-queue update-build-ids add \
  --task-queue payment-check \
  --build-id v2.3.1 \
  --existing-compatible-build-id v2.3.0
```

---

## Migration Strategies

### Strategy 1: Wait for Drain

**Best for:** Infrequent, short-running workflows

```
1. Stop starting new workflows (or redirect to new queue)
2. Wait for all existing workflows to complete
3. Deploy new code
4. Resume normal operations
```

### Strategy 2: Terminate and Restart

**Best for:** Workflows that can be safely restarted

```kotlin
// Script to migrate workflows
fun migrateWorkflows() {
    val oldWorkflows = listRunningWorkflows("payment-check")

    for (workflow in oldWorkflows) {
        // Get current state
        val progress = queryProgress(workflow.id)

        // Terminate old workflow
        terminateWorkflow(workflow.id, "Migration to v2")

        // Start new workflow with state
        startWorkflowV2(workflow.input, progress.completedItems)
    }
}
```

### Strategy 3: Gradual Migration with Feature Flags

```kotlin
// Week 1: 10% new logic
val useNewLogic = Workflow.getVersion("v2-logic", DEFAULT, 1) >= 1
    && Workflow.newRandom().nextInt(100) < 10

// Week 2: 50% new logic
val useNewLogic = Workflow.getVersion("v2-logic", DEFAULT, 1) >= 1
    && Workflow.newRandom().nextInt(100) < 50

// Week 3: 100% new logic
val useNewLogic = Workflow.getVersion("v2-logic", DEFAULT, 1) >= 1
```

---

## Quick Reference: Safe vs Unsafe Changes

### ✅ Always Safe

| Change | Why Safe |
|--------|----------|
| Activity implementation changes | Results from history |
| Add logging to activities | No effect on history |
| Change activity retry policy | Only affects new attempts |
| Add new activity methods | Old code doesn't call them |
| Bug fix in activity | Old results preserved |

### ⚠️ Safe with Versioning

| Change | How to Make Safe |
|--------|------------------|
| Add new activity call | `if (getVersion() >= 1) activity.new()` |
| Change branching logic | Version both branches |
| Modify loop bounds | Version the loop |
| Add child workflow | Version the spawn |

### ❌ Never Safe (Requires Migration)

| Change | Why Unsafe |
|--------|------------|
| Change activity return type | Deserialization fails |
| Rename activity method | Treated as different activity |
| Remove activity call without versioning | History mismatch |
| Change workflow method signature | Breaks existing stubs |

---

## Checklist Before Deploying Workflow Changes

```
□ Are there running workflows that use the old code?
  → If yes, use Workflow.getVersion()

□ Did I add/remove/reorder any activity calls?
  → If yes, wrap in version check

□ Did I change any conditional logic that affects activity calls?
  → If yes, version the condition

□ Did I change loop iterations that contain activities?
  → If yes, version the loop bounds

□ Did I modify the workflow method signature?
  → If yes, consider new workflow type or task queue

□ Did I change activity method signatures?
  → Adding optional params is safe
  → Changing return type requires migration

□ Have I tested replay with existing workflow histories?
  → Use Temporal's replay testing framework
```

---

## Testing for Determinism

```kotlin
// Temporal provides replay testing
@Test
fun `workflow replays correctly after changes`() {
    val history = WorkflowHistoryLoader.readHistory("workflow_history.json")

    WorkflowReplayer.replayWorkflowExecution(
        history,
        PaymentStatusCheckWorkflowImpl::class.java
    )
    // If this doesn't throw, replay is safe
}
```

Save workflow histories before deploying:
```bash
temporal workflow show \
  --workflow-id payment-check-xxx \
  --output json > workflow_history.json
```
