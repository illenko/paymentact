# Temporal Workflow Engine: A Hands-On Technical Demo

> A comprehensive guide for understanding Temporal through a real-world payment processing example.

---

## Table of Contents

1. [The Business Problem](#1-the-business-problem)
2. [Why This Problem is Hard](#2-why-this-problem-is-hard)
3. [Introducing Temporal](#3-introducing-temporal)
4. [Core Concepts Deep Dive](#4-core-concepts-deep-dive)
5. [Designing Our Solution](#5-designing-our-solution)
6. [How Temporal Executes Our Workflow](#6-how-temporal-executes-our-workflow)
7. [Failure Scenarios and Recovery](#7-failure-scenarios-and-recovery)
8. [Production Deployment Guide](#8-production-deployment-guide)
9. [Quick Reference](#9-quick-reference)

---

## 1. The Business Problem

### The Task

We need to build a payment status check service that:

1. Accepts a list of payment IDs (potentially hundreds)
2. For each payment, queries Elasticsearch to find which payment gateway handles it
3. Groups payments by gateway
4. For each gateway, calls two external services in sequence:
   - **IDB Facade**: Batch notification (up to 5 payments per call)
   - **PGI Gateway**: Individual status check (one payment at a time)
5. Returns aggregated results showing successes and failures

### The Constraints

| Constraint | Requirement |
|------------|-------------|
| ES Load | Max 10 concurrent Elasticsearch queries |
| Gateway Load | Max 5 payments per batch to avoid overloading gateways |
| Processing Order | IDB must complete before PGI for each batch |
| Failure Isolation | One gateway's failure shouldn't affect others |
| Observability | Must be able to query progress at any time |
| Reliability | Must complete even if our service restarts |

### Visual Overview of the Business Flow

```
INPUT: [pay_001, pay_002, pay_003, ... pay_100]
                           │
                           ▼
              ┌────────────────────────┐
              │   1. ES LOOKUP PHASE   │
              │   (find gateway for    │
              │    each payment)       │
              │   Max 10 parallel      │
              └────────────────────────┘
                           │
         ┌─────────────────┼─────────────────┐
         ▼                 ▼                 ▼
    ┌─────────┐       ┌─────────┐       ┌─────────┐
    │ Stripe  │       │  Adyen  │       │ PayPal  │
    │ 40 pays │       │ 35 pays │       │ 25 pays │
    └─────────┘       └─────────┘       └─────────┘
         │                 │                 │
         ▼                 ▼                 ▼
    ┌─────────┐       ┌─────────┐       ┌─────────┐
    │ Chunk   │       │ Chunk   │       │ Chunk   │
    │ into 5s │       │ into 5s │       │ into 5s │
    └─────────┘       └─────────┘       └─────────┘
         │                 │                 │
         ▼                 ▼                 ▼
   8 chunks of 5     7 chunks of 5     5 chunks of 5
         │                 │                 │
         ▼                 ▼                 ▼
    ┌─────────────────────────────────────────────┐
    │        2. GATEWAY PROCESSING PHASE          │
    │                                             │
    │  For each chunk:                            │
    │    ├── Call IDB Facade (batch of 5)        │
    │    └── Call PGI Gateway (one by one)       │
    │                                             │
    │  Gateways process IN PARALLEL              │
    │  Chunks within gateway are SEQUENTIAL      │
    └─────────────────────────────────────────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │   3. AGGREGATE PHASE   │
              │   Collect all results  │
              └────────────────────────┘
                           │
                           ▼
OUTPUT: {
  successful: { stripe: [...], adyen: [...], paypal: [...] },
  failed: { stripe: [{chunk: 3, error: "..."}] },
  lookupFailed: [pay_099, pay_100]
}
```

---

## 2. Why This Problem is Hard

### Challenge 1: Controlled Parallelism

We need parallelism at multiple levels with different limits:

```
                    PARALLELISM REQUIREMENTS
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│  ES Lookups:     ══════════  (max 10 parallel)             │
│                  ══════════                                 │
│                                                             │
│  Gateways:       ═══  ═══  ═══  (all parallel)             │
│                                                             │
│  Chunks/Gateway: ─→─→─→─→─→  (sequential within gateway)   │
│                                                             │
│  PGI Calls:      ─→─→─→─→─→  (sequential within chunk)     │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Challenge 2: Partial Failure Handling

What happens when things fail?

```
Scenario: Processing 3 gateways, Adyen's IDB call fails

WITHOUT proper handling:              WITH proper handling:
─────────────────────────             ─────────────────────
Stripe: ✓ ✓ ✓ ✓ ✓                    Stripe: ✓ ✓ ✓ ✓ ✓
Adyen:  ✗ (IDB fails)                Adyen:  ✗ (recorded as failed)
PayPal: ??? (never runs)             PayPal: ✓ ✓ ✓ (continues!)

Result: Complete failure              Result: Partial success
        Lost progress                         Detailed failure info
```

### Challenge 3: Long-Running Operations

With 100 payments across 3 gateways:
- ES lookups: ~10 seconds (100 calls, 10 parallel)
- IDB + PGI calls: ~60 seconds per gateway
- Total: Could be several minutes

**What if our service crashes at minute 2?**

```
Traditional approach:                 With Temporal:
─────────────────────                 ─────────────────────
Minute 0: Start processing            Minute 0: Start processing
Minute 1: 50% complete                Minute 1: 50% complete (saved)
Minute 2: CRASH                       Minute 2: CRASH
Minute 2: Restart from 0%             Minute 2: Resume from 50%
          ↑ Lost all progress                   ↑ No progress lost
```

### Challenge 4: Observability

How do we answer: "What's the status of workflow X?"

```
┌─────────────────────────────────────────────────────────────┐
│  Required visibility:                                       │
│                                                             │
│  • How many payments total?                                 │
│  • How many gateways identified?                            │
│  • How many chunks created?                                 │
│  • How many chunks completed?                               │
│  • Current phase? (ES lookup / Gateway processing / Done)   │
│  • Which payments failed and why?                           │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. Introducing Temporal

### What is Temporal?

Temporal is a **durable execution platform**. It ensures your code runs to completion, even across failures, restarts, and deployments.

**Key insight**: Temporal doesn't execute your code directly. Instead, it orchestrates execution and persists state, while your code runs on Workers you control.

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           YOUR INFRASTRUCTURE                            │
│                                                                         │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                        YOUR APPLICATION                           │  │
│  │                                                                   │  │
│  │   ┌─────────────────┐              ┌─────────────────────────┐   │  │
│  │   │   REST API      │              │        WORKER           │   │  │
│  │   │   Controller    │              │                         │   │  │
│  │   │                 │              │  ┌───────────────────┐  │   │  │
│  │   │  • Starts       │              │  │ Workflow Impls    │  │   │  │
│  │   │    workflows    │              │  │ (your logic)      │  │   │  │
│  │   │  • Queries      │              │  └───────────────────┘  │   │  │
│  │   │    status       │              │  ┌───────────────────┐  │   │  │
│  │   │                 │              │  │ Activity Impls    │  │   │  │
│  │   └────────┬────────┘              │  │ (side effects)    │  │   │  │
│  │            │                       │  └───────────────────┘  │   │  │
│  │            │                       │           │             │   │  │
│  │            │   ┌───────────────────────────────┘             │   │  │
│  │            │   │                                             │   │  │
│  │            ▼   ▼                                             │   │  │
│  │   ┌─────────────────┐                                        │   │  │
│  │   │ Temporal Client │◄───────────── polls for tasks ─────────┘   │  │
│  │   └────────┬────────┘                                            │  │
│  │            │                                                     │  │
│  └────────────┼─────────────────────────────────────────────────────┘  │
│               │                                                        │
└───────────────┼────────────────────────────────────────────────────────┘
                │
                │ gRPC
                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         TEMPORAL SERVER                                  │
│                                                                         │
│   ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐        │
│   │  Frontend       │  │  History        │  │  Matching       │        │
│   │  Service        │  │  Service        │  │  Service        │        │
│   │                 │  │                 │  │                 │        │
│   │  • API gateway  │  │  • Persists     │  │  • Task queue   │        │
│   │  • Validation   │  │    workflow     │  │    management   │        │
│   │                 │  │    history      │  │  • Worker       │        │
│   │                 │  │  • Replay       │  │    routing      │        │
│   │                 │  │    decisions    │  │                 │        │
│   └─────────────────┘  └─────────────────┘  └─────────────────┘        │
│                                │                                        │
│                                ▼                                        │
│                    ┌─────────────────────┐                              │
│                    │     Database        │                              │
│                    │  (PostgreSQL/MySQL/ │                              │
│                    │   Cassandra)        │                              │
│                    └─────────────────────┘                              │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### The Execution Model: Temporal Does NOT Run Your Code

This is the most important concept to understand:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     HOW TEMPORAL EXECUTION WORKS                         │
│                                                                         │
│  1. Client sends "start workflow" to Temporal Server                    │
│                                                                         │
│  2. Temporal Server:                                                    │
│     • Creates workflow execution record                                 │
│     • Puts "workflow task" on task queue                                │
│     • Does NOT execute any code                                         │
│                                                                         │
│  3. Your Worker:                                                        │
│     • Polls task queue                                                  │
│     • Picks up workflow task                                            │
│     • Executes YOUR workflow code                                       │
│     • Reports "I need to run activity X" back to server                 │
│                                                                         │
│  4. Temporal Server:                                                    │
│     • Records "activity X scheduled" in history                         │
│     • Puts "activity task" on task queue                                │
│                                                                         │
│  5. Your Worker:                                                        │
│     • Picks up activity task                                            │
│     • Executes YOUR activity code (HTTP call, DB query, etc.)          │
│     • Reports result back to server                                     │
│                                                                         │
│  6. Temporal Server:                                                    │
│     • Records "activity X completed with result Y" in history           │
│     • Puts next "workflow task" on queue                                │
│                                                                         │
│  7. Cycle continues until workflow completes                            │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

**Key Takeaway**: Temporal Server is a coordinator and state store. All actual code execution happens on your Workers.

---

## 4. Core Concepts Deep Dive

### 4.1 Workflows

A **Workflow** is a function that orchestrates the execution of Activities and child Workflows. Think of it as a reliable, resumable program.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         WORKFLOW CHARACTERISTICS                         │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ DETERMINISTIC                                                    │   │
│  │                                                                  │   │
│  │ Given the same input, workflow must make the same decisions.    │   │
│  │ This enables replay after failures.                              │   │
│  │                                                                  │   │
│  │ ✗ Random.nextInt()           ✓ Workflow.newRandom().nextInt()   │   │
│  │ ✗ System.currentTimeMillis() ✓ Workflow.currentTimeMillis()     │   │
│  │ ✗ UUID.randomUUID()          ✓ Workflow.randomUUID()            │   │
│  │ ✗ Thread.sleep()             ✓ Workflow.sleep()                 │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ DURABLE                                                          │   │
│  │                                                                  │   │
│  │ Workflow state survives crashes. After restart, execution       │   │
│  │ continues from exactly where it left off.                        │   │
│  │                                                                  │   │
│  │ State includes:                                                  │   │
│  │   • Local variables                                              │   │
│  │   • Activity results                                             │   │
│  │   • Child workflow results                                       │   │
│  │   • Timer states                                                 │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ NO SIDE EFFECTS                                                  │   │
│  │                                                                  │   │
│  │ Workflows must not directly cause side effects.                 │   │
│  │ All side effects happen through Activities.                      │   │
│  │                                                                  │   │
│  │ ✗ httpClient.post(...)       ✓ activities.sendRequest(...)      │   │
│  │ ✗ database.save(...)         ✓ activities.saveToDatabase(...)   │   │
│  │ ✗ file.write(...)            ✓ activities.writeFile(...)        │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Activities

An **Activity** is a function that performs a single action with potential side effects. Activities are the only place where you interact with the outside world.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         ACTIVITY CHARACTERISTICS                         │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ CAN HAVE SIDE EFFECTS                                            │   │
│  │                                                                  │   │
│  │ Activities are where you:                                        │   │
│  │   • Make HTTP requests                                           │   │
│  │   • Query databases                                              │   │
│  │   • Send emails                                                  │   │
│  │   • Write files                                                  │   │
│  │   • Call external services                                       │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ AUTOMATICALLY RETRIED                                            │   │
│  │                                                                  │   │
│  │ When an activity fails, Temporal automatically retries it       │   │
│  │ according to the configured retry policy.                        │   │
│  │                                                                  │   │
│  │   Attempt 1: ──► fail                                           │   │
│  │       (wait 1s)                                                  │   │
│  │   Attempt 2: ──► fail                                           │   │
│  │       (wait 2s)                                                  │   │
│  │   Attempt 3: ──► success ✓                                      │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ RESULTS ARE PERSISTED                                            │   │
│  │                                                                  │   │
│  │ Once an activity completes, its result is stored in workflow    │   │
│  │ history. On replay, the result is retrieved from history -      │   │
│  │ the activity is NOT re-executed.                                 │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 4.3 Idempotency Requirement

Because activities may be retried, they should be **idempotent** when possible:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              IDEMPOTENCY                                 │
│                                                                         │
│  Definition: An operation is idempotent if executing it multiple       │
│  times produces the same result as executing it once.                  │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ NATURALLY IDEMPOTENT                                             │   │
│  │                                                                  │   │
│  │ • GET requests (reading data)                                    │   │
│  │ • SET operations (overwrite with same value)                     │   │
│  │ • DELETE by ID (deleting non-existent = no-op)                  │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ NEEDS IDEMPOTENCY KEY                                            │   │
│  │                                                                  │   │
│  │ • Payment processing (use transaction ID)                        │   │
│  │ • Creating records (use client-provided ID)                      │   │
│  │ • Sending notifications (dedupe by message ID)                   │   │
│  │                                                                  │   │
│  │ Pattern:                                                         │   │
│  │   if (alreadyProcessed(idempotencyKey)) {                       │   │
│  │       return previousResult                                      │   │
│  │   }                                                              │   │
│  │   result = doOperation()                                         │   │
│  │   markProcessed(idempotencyKey, result)                         │   │
│  │   return result                                                  │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 4.4 Execution Guarantees

Temporal provides **at-least-once** execution for activities:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         EXECUTION GUARANTEES                             │
│                                                                         │
│  WORKFLOW EXECUTION                                                     │
│  ─────────────────                                                      │
│  • Exactly-once semantics                                               │
│  • Workflow code runs as many times as needed (replay)                 │
│  • But makes the same decisions each time (determinism)                │
│  • Side effects (activities) execute exactly as recorded               │
│                                                                         │
│  ACTIVITY EXECUTION                                                     │
│  ──────────────────                                                     │
│  • At-least-once semantics                                              │
│  • Activity may execute multiple times due to:                          │
│      - Explicit retries on failure                                      │
│      - Worker crash during execution (Temporal doesn't know if         │
│        activity completed, so it retries)                               │
│      - Network issues causing timeout                                   │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ TIMELINE EXAMPLE: Worker crash during activity                   │   │
│  │                                                                  │   │
│  │   Worker A                    Temporal                           │   │
│  │      │                           │                               │   │
│  │      │── Start activity ────────►│                               │   │
│  │      │   (HTTP call)             │                               │   │
│  │      │                           │                               │   │
│  │      X   Worker A crashes        │                               │   │
│  │          HTTP call completed     │                               │   │
│  │          but result lost         │                               │   │
│  │                                  │                               │   │
│  │                        (timeout) │                               │   │
│  │                                  │                               │   │
│  │   Worker B                       │                               │   │
│  │      │◄── Retry activity ────────│                               │   │
│  │      │   (HTTP call again!)      │                               │   │
│  │      │                           │                               │   │
│  │      │── Complete ──────────────►│                               │   │
│  │                                                                  │   │
│  │   Result: HTTP endpoint called TWICE                             │   │
│  │   Solution: Endpoint must be idempotent                          │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 4.5 Determinism and Replay

This is the core mechanism that enables durability:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         THE REPLAY MECHANISM                             │
│                                                                         │
│  FIRST EXECUTION (no history)                                           │
│  ════════════════════════════                                           │
│                                                                         │
│  Workflow Code                    Recorded in History                   │
│  ─────────────                    ───────────────────                   │
│  1. esActivity.lookup("pay1")  → [ActivityScheduled: lookup("pay1")]   │
│     ... worker executes ...     → [ActivityCompleted: "stripe"]        │
│                                                                         │
│  2. esActivity.lookup("pay2")  → [ActivityScheduled: lookup("pay2")]   │
│     ... worker executes ...     → [ActivityCompleted: "adyen"]         │
│                                                                         │
│  3. if (gateway == "stripe")   → [Decision based on "stripe"]          │
│       processStripe()                                                   │
│                                                                         │
│                                                                         │
│  REPLAY AFTER CRASH (with history)                                      │
│  ═════════════════════════════════                                      │
│                                                                         │
│  Workflow Code                    Read from History                     │
│  ─────────────                    ─────────────────                     │
│  1. esActivity.lookup("pay1")  ← Returns "stripe" (from history)       │
│     ... NO execution ...          Activity NOT re-executed              │
│                                                                         │
│  2. esActivity.lookup("pay2")  ← Returns "adyen" (from history)        │
│     ... NO execution ...          Activity NOT re-executed              │
│                                                                         │
│  3. if (gateway == "stripe")   ← Must make SAME decision!              │
│       processStripe()              Code sees "stripe", takes same path  │
│                                                                         │
│                                                                         │
│  WHY DETERMINISM MATTERS                                                │
│  ═══════════════════════                                                │
│                                                                         │
│  If workflow code makes different decisions on replay:                  │
│                                                                         │
│  Original:                        Replay (with bug):                    │
│  ─────────                        ──────────────────                    │
│  1. lookup("pay1") → "stripe"    1. lookup("pay1") → "stripe" ✓        │
│  2. if ("stripe") → processA()   2. if (random()) → processB()  ✗     │
│                                     ↑                                   │
│                                     Different decision!                 │
│                                     History says "processA" was called  │
│                                     but code wants to call "processB"   │
│                                                                         │
│                                     💥 NON-DETERMINISM ERROR            │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 4.6 Why Temporal Survives Failures

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    FAILURE RECOVERY MECHANISM                            │
│                                                                         │
│  SCENARIO: Worker crashes after completing 2 of 3 activities           │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ BEFORE CRASH                                                     │   │
│  │                                                                  │   │
│  │ History in Temporal Server:                                      │   │
│  │ ┌──────────────────────────────────────────────────────────┐    │   │
│  │ │ 1. WorkflowExecutionStarted                               │    │   │
│  │ │ 2. WorkflowTaskCompleted                                  │    │   │
│  │ │ 3. ActivityTaskScheduled: lookup("pay1")                  │    │   │
│  │ │ 4. ActivityTaskCompleted: "stripe"                        │    │   │
│  │ │ 5. ActivityTaskScheduled: lookup("pay2")                  │    │   │
│  │ │ 6. ActivityTaskCompleted: "adyen"                         │    │   │
│  │ │ 7. ActivityTaskScheduled: processStripe(...)     ◄─ HERE │    │   │
│  │ └──────────────────────────────────────────────────────────┘    │   │
│  │                                                                  │   │
│  │ Worker A: Executing processStripe(...)                          │   │
│  │           X CRASH                                                │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ RECOVERY                                                         │   │
│  │                                                                  │   │
│  │ 1. Temporal detects Worker A is gone (heartbeat timeout)        │   │
│  │                                                                  │   │
│  │ 2. Temporal puts workflow task back on queue                    │   │
│  │                                                                  │   │
│  │ 3. Worker B picks up the task                                   │   │
│  │                                                                  │   │
│  │ 4. Worker B replays workflow from history:                      │   │
│  │    - lookup("pay1") → returns "stripe" from history             │   │
│  │    - lookup("pay2") → returns "adyen" from history              │   │
│  │    - processStripe() → activity was scheduled but not complete  │   │
│  │                        → RE-EXECUTE this activity               │   │
│  │                                                                  │   │
│  │ 5. Worker B continues from where Worker A left off              │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  KEY INSIGHT: Temporal Server stores WHAT happened.                    │
│               Workers can reconstruct state by replaying.              │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Designing Our Solution

### 5.1 Workflow Structure

We use a parent-child workflow pattern:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        WORKFLOW HIERARCHY                                │
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                   PaymentStatusCheckWorkflow                       │  │
│  │                        (Parent Workflow)                           │  │
│  │                                                                    │  │
│  │  Responsibilities:                                                 │  │
│  │  • Accept list of payment IDs                                      │  │
│  │  • Orchestrate ES lookups (parallel, limited concurrency)         │  │
│  │  • Group payments by gateway                                       │  │
│  │  • Spawn child workflow per gateway                                │  │
│  │  • Aggregate results                                               │  │
│  │  • Expose progress via query                                       │  │
│  │                                                                    │  │
│  │               │                    │                    │          │  │
│  │               ▼                    ▼                    ▼          │  │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐    │  │
│  │  │ GatewayWorkflow │  │ GatewayWorkflow │  │ GatewayWorkflow │    │  │
│  │  │    (Stripe)     │  │    (Adyen)      │  │    (PayPal)     │    │  │
│  │  │                 │  │                 │  │                 │    │  │
│  │  │ • Process all   │  │ • Process all   │  │ • Process all   │    │  │
│  │  │   chunks for    │  │   chunks for    │  │   chunks for    │    │  │
│  │  │   this gateway  │  │   this gateway  │  │   this gateway  │    │  │
│  │  │ • Sequential    │  │ • Sequential    │  │ • Sequential    │    │  │
│  │  │   within gateway│  │   within gateway│  │   within gateway│    │  │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────┘    │  │
│  │         │                    │                    │               │  │
│  │         └────────────────────┴────────────────────┘               │  │
│  │                              │                                     │  │
│  │                    Running in PARALLEL                             │  │
│  │                                                                    │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                                                         │
│  WHY CHILD WORKFLOWS?                                                   │
│  ────────────────────                                                   │
│  • Isolation: Stripe failure doesn't affect Adyen                      │
│  • Parallel execution across gateways                                  │
│  • Separate history per gateway (better performance)                   │
│  • Can query child workflow progress independently                     │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 5.2 Activity Design

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           ACTIVITIES                                     │
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ ElasticsearchActivities                                            │  │
│  │                                                                    │  │
│  │ getGatewayForPayment(paymentId: String): GatewayInfo              │  │
│  │                                                                    │  │
│  │   • Queries ES for payment document                                │  │
│  │   • Returns gateway name                                           │  │
│  │   • Idempotent: Read-only operation                               │  │
│  │                                                                    │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ PaymentGatewayActivities                                           │  │
│  │                                                                    │  │
│  │ callIdbFacade(gateway: String, paymentIds: List<String>): void    │  │
│  │                                                                    │  │
│  │   • Batch notification to IDB                                      │  │
│  │   • Should be idempotent on IDB side                              │  │
│  │                                                                    │  │
│  │ callPgiGateway(gateway: String, paymentId: String): void          │  │
│  │                                                                    │  │
│  │   • Triggers status check for single payment                       │  │
│  │   • PGI should handle duplicate triggers gracefully               │  │
│  │                                                                    │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 5.3 The Complete Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    COMPLETE EXECUTION FLOW                               │
│                                                                         │
│  API: POST /payments/check-status                                       │
│  Body: { paymentIds: [100 IDs] }                                       │
│                                                                         │
│                              │                                          │
│                              ▼                                          │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ PHASE 1: ES LOOKUPS                                                │  │
│  │                                                                    │  │
│  │ 100 payment IDs, max 10 parallel                                  │  │
│  │                                                                    │  │
│  │ Batch 1:  [ID 1-10]   ═══════════════  ──► ES Activities          │  │
│  │           wait for all 10                                          │  │
│  │                                                                    │  │
│  │ Batch 2:  [ID 11-20]  ═══════════════  ──► ES Activities          │  │
│  │           wait for all 10                                          │  │
│  │                                                                    │  │
│  │ ...continue until all 100 processed...                            │  │
│  │                                                                    │  │
│  │ Result: Map<PaymentId, Gateway>                                   │  │
│  │         pay_001 → stripe                                          │  │
│  │         pay_002 → adyen                                           │  │
│  │         pay_003 → stripe                                          │  │
│  │         ...                                                        │  │
│  │         pay_099 → FAILED (added to lookupFailed list)             │  │
│  │                                                                    │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                              │                                          │
│                              ▼                                          │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ PHASE 2: GROUP AND CHUNK                                           │  │
│  │                                                                    │  │
│  │ Group by gateway:                                                  │  │
│  │   stripe: [pay_001, pay_003, pay_007, ...] (40 payments)          │  │
│  │   adyen:  [pay_002, pay_005, pay_008, ...] (35 payments)          │  │
│  │   paypal: [pay_004, pay_006, pay_009, ...] (24 payments)          │  │
│  │                                                                    │  │
│  │ Chunk (max 5 per chunk):                                          │  │
│  │   stripe: 8 chunks                                                 │  │
│  │   adyen:  7 chunks                                                 │  │
│  │   paypal: 5 chunks                                                 │  │
│  │                                                                    │  │
│  │ (This is pure computation, no activities needed)                  │  │
│  │                                                                    │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                              │                                          │
│                              ▼                                          │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ PHASE 3: SPAWN CHILD WORKFLOWS                                     │  │
│  │                                                                    │  │
│  │    ┌─────────────────────────────────────────────────────────┐    │  │
│  │    │              RUNNING IN PARALLEL                         │    │  │
│  │    │                                                          │    │  │
│  │    │  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐     │    │  │
│  │    │  │ Stripe Child │ │ Adyen Child  │ │ PayPal Child │     │    │  │
│  │    │  │              │ │              │ │              │     │    │  │
│  │    │  │ 8 chunks     │ │ 7 chunks     │ │ 5 chunks     │     │    │  │
│  │    │  │ sequential   │ │ sequential   │ │ sequential   │     │    │  │
│  │    │  └──────────────┘ └──────────────┘ └──────────────┘     │    │  │
│  │    │                                                          │    │  │
│  │    └─────────────────────────────────────────────────────────┘    │  │
│  │                                                                    │  │
│  │    Inside each child (e.g., Stripe):                              │  │
│  │                                                                    │  │
│  │    Chunk 1: [pay_001, pay_003, pay_007, pay_012, pay_015]        │  │
│  │       │                                                           │  │
│  │       ├── IDB Facade (batch: 5 payments) ──────► Activity        │  │
│  │       │                                                           │  │
│  │       ├── PGI (pay_001) ───────────────────────► Activity        │  │
│  │       ├── PGI (pay_003) ───────────────────────► Activity        │  │
│  │       ├── PGI (pay_007) ───────────────────────► Activity        │  │
│  │       ├── PGI (pay_012) ───────────────────────► Activity        │  │
│  │       └── PGI (pay_015) ───────────────────────► Activity        │  │
│  │                  │                                                │  │
│  │                  ▼                                                │  │
│  │    Chunk 2: [pay_020, pay_025, pay_030, pay_035, pay_040]        │  │
│  │       │                                                           │  │
│  │       └── ... same pattern ...                                   │  │
│  │                                                                    │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                              │                                          │
│                              ▼                                          │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ PHASE 4: AGGREGATE                                                 │  │
│  │                                                                    │  │
│  │ Wait for all child workflows to complete                          │  │
│  │ Merge results into final response                                 │  │
│  │                                                                    │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                              │                                          │
│                              ▼                                          │
│  Result: CheckStatusResult                                              │
│  {                                                                      │
│    successful: { stripe: [...], adyen: [...], paypal: [...] },         │
│    failed: { stripe: [{chunk: 5, error: "IDB timeout"}] },             │
│    lookupFailed: [pay_099]                                              │
│  }                                                                      │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 6. How Temporal Executes Our Workflow

### 6.1 Detailed Execution Timeline

```
┌─────────────────────────────────────────────────────────────────────────┐
│              TEMPORAL EXECUTION TIMELINE (Simplified)                    │
│                                                                         │
│  Client          Temporal Server         Worker           External      │
│    │                   │                   │                  │         │
│    │ StartWorkflow     │                   │                  │         │
│    │──────────────────►│                   │                  │         │
│    │                   │                   │                  │         │
│    │  workflowId       │ Create execution  │                  │         │
│    │◄──────────────────│ Queue workflow    │                  │         │
│    │                   │ task              │                  │         │
│    │                   │                   │                  │         │
│    │                   │                   │ Poll             │         │
│    │                   │                   │◄────────────────►│         │
│    │                   │                   │                  │         │
│    │                   │   Workflow task   │                  │         │
│    │                   │──────────────────►│                  │         │
│    │                   │                   │                  │         │
│    │                   │                   │ Execute workflow │         │
│    │                   │                   │ code until       │         │
│    │                   │                   │ activity call    │         │
│    │                   │                   │                  │         │
│    │                   │ Schedule activity │                  │         │
│    │                   │ (ES lookup #1)    │                  │         │
│    │                   │◄──────────────────│                  │         │
│    │                   │                   │                  │         │
│    │                   │ Record in history │                  │         │
│    │                   │ Queue activity    │                  │         │
│    │                   │ task              │                  │         │
│    │                   │                   │                  │         │
│    │                   │   Activity task   │                  │         │
│    │                   │──────────────────►│                  │         │
│    │                   │                   │                  │         │
│    │                   │                   │ Execute activity │         │
│    │                   │                   │─────────────────►│ ES      │
│    │                   │                   │◄─────────────────│ Query   │
│    │                   │                   │                  │         │
│    │                   │ Activity complete │                  │         │
│    │                   │ result: "stripe"  │                  │         │
│    │                   │◄──────────────────│                  │         │
│    │                   │                   │                  │         │
│    │                   │ Record in history │                  │         │
│    │                   │ Queue workflow    │                  │         │
│    │                   │ task              │                  │         │
│    │                   │                   │                  │         │
│    │                   │   Workflow task   │                  │         │
│    │                   │──────────────────►│                  │         │
│    │                   │                   │                  │         │
│    │                   │                   │ Continue workflow│         │
│    │                   │                   │ (schedule next   │         │
│    │                   │                   │  activity...)    │         │
│    │                   │                   │                  │         │
│    │                    ... cycle continues ...                │         │
│    │                                                           │         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 6.2 What Gets Stored in History

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        WORKFLOW HISTORY                                  │
│                                                                         │
│  Each workflow execution has a complete history of events:              │
│                                                                         │
│  Event #  Type                           Data                           │
│  ───────  ────                           ────                           │
│  1        WorkflowExecutionStarted       input: {paymentIds: [...]}    │
│  2        WorkflowTaskScheduled                                         │
│  3        WorkflowTaskStarted            workerId: worker-1             │
│  4        WorkflowTaskCompleted          commands: [ScheduleActivity]   │
│  5        ActivityTaskScheduled          activityType: getGateway      │
│                                          input: "pay_001"               │
│  6        ActivityTaskStarted            workerId: worker-1             │
│  7        ActivityTaskCompleted          result: {gateway: "stripe"}   │
│  8        WorkflowTaskScheduled                                         │
│  9        WorkflowTaskStarted            workerId: worker-2             │
│  10       WorkflowTaskCompleted          commands: [ScheduleActivity]   │
│  11       ActivityTaskScheduled          activityType: callIdbFacade   │
│  ...      ...                            ...                            │
│  N        WorkflowExecutionCompleted     result: {successful: {...}}   │
│                                                                         │
│  ───────────────────────────────────────────────────────────────────── │
│                                                                         │
│  This history is:                                                       │
│  • Immutable (append-only)                                              │
│  • Persisted durably                                                    │
│  • Used for replay after failures                                       │
│  • Viewable in Temporal UI                                              │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 6.3 Parallel Execution with Async

```
┌─────────────────────────────────────────────────────────────────────────┐
│              HOW PARALLEL ACTIVITIES WORK                                │
│                                                                         │
│  Workflow Code:                                                         │
│  ──────────────                                                         │
│    val promises = batch.map { paymentId ->                              │
│        Async.function { esActivities.getGateway(paymentId) }           │
│    }                                                                    │
│    // At this point: all activities are SCHEDULED                       │
│                                                                         │
│    promises.forEach { it.get() }                                        │
│    // At this point: waiting for all to complete                        │
│                                                                         │
│                                                                         │
│  What Temporal Sees:                                                    │
│  ───────────────────                                                    │
│                                                                         │
│  1. Worker executes workflow code                                       │
│                                                                         │
│  2. Async.function() calls are collected as "commands"                  │
│     (no network calls yet)                                              │
│                                                                         │
│  3. When workflow "yields" (at .get()), commands sent to server:       │
│     Commands: [                                                         │
│       ScheduleActivityTask(getGateway, "pay_001"),                     │
│       ScheduleActivityTask(getGateway, "pay_002"),                     │
│       ScheduleActivityTask(getGateway, "pay_003"),                     │
│       ...                                                               │
│     ]                                                                   │
│                                                                         │
│  4. Server queues ALL activity tasks simultaneously                     │
│                                                                         │
│  5. Multiple workers can pick up different tasks:                       │
│                                                                         │
│     Worker A: executes getGateway("pay_001")                           │
│     Worker B: executes getGateway("pay_002")                           │
│     Worker A: executes getGateway("pay_003")                           │
│     ...                                                                 │
│                                                                         │
│  6. Results flow back to server, stored in history                      │
│                                                                         │
│  7. Once ALL complete, workflow task queued again                       │
│                                                                         │
│  8. Worker continues workflow from .get() calls                         │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Failure Scenarios and Recovery

### 7.1 Worker Crash Mid-Activity

```
┌─────────────────────────────────────────────────────────────────────────┐
│            SCENARIO: Worker crashes during activity execution            │
│                                                                         │
│  Timeline:                                                              │
│  ─────────                                                              │
│                                                                         │
│  T0: Worker A picks up activity task (callPgiGateway)                  │
│  T1: Worker A makes HTTP call to PGI                                   │
│  T2: PGI processes request, returns 200 OK                             │
│  T3: Worker A crashes BEFORE reporting success to Temporal             │
│                                                                         │
│                                                                         │
│  What Happens:                                                          │
│  ─────────────                                                          │
│                                                                         │
│  Temporal Server                      PGI Gateway                       │
│       │                                    │                            │
│       │ Activity scheduled                 │                            │
│       │ Start timeout timer                │                            │
│       │                                    │                            │
│       │                                    │ Received request           │
│       │                                    │ Processed ✓                │
│       │                                    │                            │
│       │ ... waiting for result ...         │                            │
│       │                                    │                            │
│       │ Timeout! No result received        │                            │
│       │                                    │                            │
│       │ Retry activity                     │                            │
│       │────────────────────────────────────►                            │
│       │                                    │ Received SAME request      │
│       │                                    │ (must be idempotent!)      │
│       │◄────────────────────────────────────                            │
│       │                                    │                            │
│       │ Activity complete                  │                            │
│                                                                         │
│                                                                         │
│  KEY POINTS:                                                            │
│  ───────────                                                            │
│  • PGI was called TWICE (at-least-once semantics)                      │
│  • PGI must handle duplicate requests gracefully                        │
│  • Workflow continues normally after retry succeeds                     │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 7.2 Temporal Server Restart

```
┌─────────────────────────────────────────────────────────────────────────┐
│            SCENARIO: Temporal Server restarts                            │
│                                                                         │
│  What's Preserved:                                                      │
│  ─────────────────                                                      │
│  • All workflow histories (in database)                                 │
│  • All pending timers                                                   │
│  • All scheduled tasks                                                  │
│                                                                         │
│  What Happens:                                                          │
│  ─────────────                                                          │
│                                                                         │
│  1. Server goes down                                                    │
│  2. Workers lose connection, stop polling                               │
│  3. Server comes back up                                                │
│  4. Server loads state from database                                    │
│  5. Workers reconnect, resume polling                                   │
│  6. Pending tasks are re-dispatched                                     │
│  7. Workflows continue from where they left off                         │
│                                                                         │
│                                                                         │
│  From Workflow Perspective:                                             │
│  ──────────────────────────                                             │
│  • Appears as a brief pause                                             │
│  • No state lost                                                        │
│  • No code changes needed                                               │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 7.3 Activity Failures with Retry

```
┌─────────────────────────────────────────────────────────────────────────┐
│            SCENARIO: External service temporarily unavailable            │
│                                                                         │
│  Configuration:                                                         │
│  ──────────────                                                         │
│  RetryOptions:                                                          │
│    maxAttempts: 3                                                       │
│    initialInterval: 1 second                                            │
│    backoffCoefficient: 2.0                                              │
│    maxInterval: 10 seconds                                              │
│                                                                         │
│                                                                         │
│  Timeline:                                                              │
│  ─────────                                                              │
│                                                                         │
│  T+0s:   Attempt 1 ───────► ES ───────► 503 Service Unavailable        │
│                                                                         │
│          (wait 1 second)                                                │
│                                                                         │
│  T+1s:   Attempt 2 ───────► ES ───────► 503 Service Unavailable        │
│                                                                         │
│          (wait 2 seconds = 1s × 2.0)                                    │
│                                                                         │
│  T+3s:   Attempt 3 ───────► ES ───────► 200 OK, result: "stripe"       │
│                                                                         │
│          Activity succeeds, workflow continues                          │
│                                                                         │
│                                                                         │
│  If All Retries Fail:                                                   │
│  ─────────────────────                                                  │
│                                                                         │
│  T+0s:   Attempt 1 ───────► 503                                        │
│  T+1s:   Attempt 2 ───────► 503                                        │
│  T+3s:   Attempt 3 ───────► 503                                        │
│                                                                         │
│          Activity fails with exception                                  │
│          Workflow code catches exception                                │
│          Payment added to "lookupFailed" list                           │
│          Workflow continues with remaining payments                     │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 7.4 Complete Failure Recovery Example

```
┌─────────────────────────────────────────────────────────────────────────┐
│            COMPLETE CRASH RECOVERY SCENARIO                              │
│                                                                         │
│  INITIAL STATE (before crash):                                          │
│  ──────────────────────────────                                         │
│                                                                         │
│  PaymentStatusCheckWorkflow running:                                    │
│  • 100 payments to process                                              │
│  • ES lookups complete (100/100)                                        │
│  • Grouped into 3 gateways                                              │
│  • Child workflows spawned                                              │
│                                                                         │
│  GatewayWorkflow (Stripe):                                              │
│  • 8 chunks total                                                       │
│  • 3 chunks complete                                                    │
│  • Chunk 4 in progress: IDB done, PGI for payment 3 of 5               │
│                                                                         │
│  GatewayWorkflow (Adyen):                                               │
│  • 7 chunks total                                                       │
│  • 2 chunks complete                                                    │
│  • Chunk 3 in progress: IDB in progress                                │
│                                                                         │
│  GatewayWorkflow (PayPal):                                              │
│  • 5 chunks total                                                       │
│  • 5 chunks complete ✓                                                  │
│                                                                         │
│                                                                         │
│  💥 ALL WORKERS CRASH                                                   │
│                                                                         │
│                                                                         │
│  RECOVERY (workers restart):                                            │
│  ───────────────────────────                                            │
│                                                                         │
│  1. Workers reconnect to Temporal                                       │
│                                                                         │
│  2. Parent workflow replays:                                            │
│     • ES lookups: results from history (no re-execution)               │
│     • Grouping: deterministic, same result                              │
│     • Child workflows: already spawned (from history)                   │
│     • Waits for child results                                           │
│                                                                         │
│  3. Stripe child replays:                                               │
│     • Chunks 1-3: complete (from history)                              │
│     • Chunk 4 IDB: complete (from history)                             │
│     • Chunk 4 PGI 1-2: complete (from history)                         │
│     • Chunk 4 PGI 3: WAS IN PROGRESS                                   │
│       → Temporal retries this activity                                  │
│       → PGI called again (must be idempotent)                          │
│     • Continues with PGI 4, 5, chunks 5-8                              │
│                                                                         │
│  4. Adyen child replays:                                                │
│     • Chunks 1-2: complete (from history)                              │
│     • Chunk 3 IDB: WAS IN PROGRESS                                     │
│       → Temporal retries this activity                                  │
│       → IDB called again (must be idempotent)                          │
│     • Continues normally                                                │
│                                                                         │
│  5. PayPal child replays:                                               │
│     • All complete from history                                         │
│     • Returns result immediately                                        │
│                                                                         │
│  6. Parent receives all child results, completes                        │
│                                                                         │
│                                                                         │
│  NET EFFECT:                                                            │
│  ───────────                                                            │
│  • Only 2 activities re-executed (the ones in progress)                │
│  • ~95% of work preserved                                               │
│  • Workflow completes successfully                                      │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```
---

## 8. Production Deployment Guide

### 8.1 Understanding Determinism Violations

The most common production issue is breaking determinism. Here's a comprehensive guide:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    DETERMINISM VIOLATIONS REFERENCE                      │
│                                                                         │
│  ❌ PROHIBITED IN WORKFLOW CODE                                         │
│  ──────────────────────────────                                         │
│                                                                         │
│  Time:                                                                  │
│    System.currentTimeMillis()     →  Workflow.currentTimeMillis()      │
│    LocalDateTime.now()            →  Use Workflow time methods          │
│    Instant.now()                  →  Use Workflow time methods          │
│                                                                         │
│  Randomness:                                                            │
│    Random.nextInt()               →  Workflow.newRandom().nextInt()     │
│    UUID.randomUUID()              →  Workflow.randomUUID()              │
│    Math.random()                  →  Workflow.newRandom().nextDouble()  │
│                                                                         │
│  Threading:                                                             │
│    Thread.sleep()                 →  Workflow.sleep()                   │
│    Thread.start()                 →  Use Async.function()               │
│    ExecutorService                →  Use Async.function()               │
│    CompletableFuture              →  Use Temporal Promise               │
│                                                                         │
│  I/O Operations:                                                        │
│    HTTP calls                     →  Use Activity                       │
│    Database queries               →  Use Activity                       │
│    File operations                →  Use Activity                       │
│    Environment variables          →  Pass as workflow input             │
│    System properties              →  Pass as workflow input             │
│                                                                         │
│  Collections:                                                           │
│    HashMap iteration              →  Use LinkedHashMap or sorted keys   │
│    HashSet iteration              →  Use LinkedHashSet or sorted        │
│                                                                         │
│  Logging:                                                               │
│    Logger.info(...)               →  Workflow.getLogger().info(...)     │
│    (Regular loggers work but      │  (Temporal logger handles replay    │
│     log during replay too)        │   correctly)                        │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 8.2 Versioning Workflow Changes

When you need to change workflow logic for running workflows:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    WORKFLOW VERSIONING WITH getVersion()                 │
│                                                                         │
│  SCENARIO: Adding validation step to existing workflow                  │
│                                                                         │
│  WRONG (breaks running workflows):                                      │
│  ─────────────────────────────────                                      │
│                                                                         │
│    fun checkPaymentStatuses(input: Input): Result {                    │
│        validateInput(input)  // ← NEW: breaks replay!                  │
│        val gateways = lookupGateways(input.paymentIds)                 │
│        return processGateways(gateways)                                 │
│    }                                                                    │
│                                                                         │
│    Why it breaks:                                                       │
│    - Running workflows have history without validateInput               │
│    - On replay, workflow expects next event to be lookupGateways       │
│    - But code now calls validateInput first                             │
│    - MISMATCH → NonDeterministicException                              │
│                                                                         │
│                                                                         │
│  CORRECT (using versioning):                                            │
│  ──────────────────────────                                             │
│                                                                         │
│    fun checkPaymentStatuses(input: Input): Result {                    │
│        val version = Workflow.getVersion(                               │
│            "add-validation",           // unique change ID              │
│            Workflow.DEFAULT_VERSION,   // min version (-1)              │
│            1                           // current version               │
│        )                                                                │
│                                                                         │
│        if (version >= 1) {                                              │
│            validateInput(input)                                         │
│        }                                                                 │
│                                                                         │
│        val gateways = lookupGateways(input.paymentIds)                 │
│        return processGateways(gateways)                                 │
│    }                                                                    │
│                                                                         │
│    How it works:                                                        │
│    - Old workflows (no version marker): getVersion returns -1           │
│      → skips validation → follows old path                              │
│    - New workflows: getVersion returns 1                                │
│      → records version in history → runs validation                     │
│    - Replay of new workflows: reads version from history                │
│      → correctly takes new path                                         │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 8.3 Schema Evolution

What happens when you change input/output types?

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    INPUT/OUTPUT SCHEMA CHANGES                           │
│                                                                         │
│  ACTIVITY RETURN TYPE CHANGES                                           │
│  ────────────────────────────                                           │
│                                                                         │
│  ✅ SAFE: Adding optional fields                                        │
│                                                                         │
│    Before: data class GatewayInfo(val paymentId: String,               │
│                                    val gateway: String)                 │
│                                                                         │
│    After:  data class GatewayInfo(val paymentId: String,               │
│                                    val gateway: String,                 │
│                                    val region: String? = null)  // NEW │
│                                                                         │
│    Old history deserializes correctly (region = null)                   │
│                                                                         │
│                                                                         │
│  ⚠️ RISKY: Removing fields                                              │
│                                                                         │
│    Before: data class GatewayInfo(val paymentId: String,               │
│                                    val gateway: String,                 │
│                                    val legacyField: String)            │
│                                                                         │
│    After:  data class GatewayInfo(val paymentId: String,               │
│                                    val gateway: String)                 │
│                                                                         │
│    Works if Jackson ignores unknown properties (default)                │
│    Configure: objectMapper.configure(                                   │
│        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)       │
│                                                                         │
│                                                                         │
│  ❌ DANGEROUS: Changing field types                                     │
│                                                                         │
│    Before: data class GatewayInfo(val paymentId: String,               │
│                                    val gateway: String)                 │
│                                                                         │
│    After:  data class GatewayInfo(val paymentId: String,               │
│                                    val gateway: GatewayEnum)  // BREAKS │
│                                                                         │
│    Old history has gateway as String                                    │
│    Cannot deserialize to GatewayEnum                                    │
│    💥 Workflow fails on replay                                          │
│                                                                         │
│                                                                         │
│  ❌ DANGEROUS: Renaming classes                                         │
│                                                                         │
│    Before: package com.example.model.GatewayInfo                       │
│    After:  package com.example.model.GatewayData  // BREAKS             │
│                                                                         │
│    History contains class name for deserialization                      │
│    Class not found → deserialization fails                              │
│                                                                         │
│                                                                         │
│  WORKFLOW INPUT CHANGES                                                 │
│  ──────────────────────                                                 │
│                                                                         │
│  Same rules apply. Input is stored in WorkflowExecutionStarted event.  │
│  Changing input type breaks replay of existing workflows.              │
│                                                                         │
│  Solution: Use versioned input classes or wrapper types                 │
│                                                                         │
│    data class PaymentStatusCheckInputV2(                               │
│        val paymentIds: List<String>,                                   │
│        val config: WorkflowConfig,                                      │
│        val newField: String? = null  // backwards compatible           │
│    )                                                                    │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 8.4 Safe Deployment Patterns

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    DEPLOYMENT STRATEGIES                                 │
│                                                                         │
│  STRATEGY 1: Task Queue Based Blue-Green                                │
│  ───────────────────────────────────────                                │
│                                                                         │
│                        Temporal Server                                  │
│                              │                                          │
│               ┌──────────────┴──────────────┐                          │
│               │                             │                          │
│     ┌─────────┴─────────┐         ┌────────┴────────┐                  │
│     │ payment-check-v1  │         │ payment-check-v2│                  │
│     │   (task queue)    │         │   (task queue)  │                  │
│     └─────────┬─────────┘         └────────┬────────┘                  │
│               │                             │                          │
│     ┌─────────┴─────────┐         ┌────────┴────────┐                  │
│     │   Workers v1      │         │   Workers v2    │                  │
│     │   (old code)      │         │   (new code)    │                  │
│     └───────────────────┘         └─────────────────┘                  │
│                                                                         │
│  Steps:                                                                 │
│  1. Deploy new workers listening on v2 queue                           │
│  2. Update client to start NEW workflows on v2                         │
│  3. Old workers continue processing v1 workflows                       │
│  4. Once v1 queue drains, decommission old workers                     │
│                                                                         │
│  Pros: Complete isolation, safe rollback                                │
│  Cons: Requires client change, longer transition                        │
│                                                                         │
│                                                                         │
│  STRATEGY 2: Rolling Deploy with Versioning                             │
│  ──────────────────────────────────────────                             │
│                                                                         │
│  Timeline:                                                              │
│  ─────────                                                              │
│                                                                         │
│  T0: All workers v1     [W1-v1] [W2-v1] [W3-v1]                        │
│  T1: Deploy starts      [W1-v2] [W2-v1] [W3-v1]   ← mixed              │
│  T2: Rolling continues  [W1-v2] [W2-v2] [W3-v1]   ← mixed              │
│  T3: Deploy complete    [W1-v2] [W2-v2] [W3-v2]                        │
│                                                                         │
│  Requirements:                                                          │
│  • All workflow changes must use Workflow.getVersion()                 │
│  • Both old and new code paths exist during transition                 │
│  • Any worker can process any workflow                                  │
│                                                                         │
│  Pros: Standard deployment, no client changes                           │
│  Cons: Requires careful versioning discipline                           │
│                                                                         │
│                                                                         │
│  STRATEGY 3: Drain and Deploy                                           │
│  ────────────────────────────                                           │
│                                                                         │
│  For breaking changes that can't be versioned:                          │
│                                                                         │
│  1. Stop accepting new workflows                                        │
│  2. Wait for all running workflows to complete                          │
│  3. Deploy new code                                                     │
│  4. Resume accepting workflows                                          │
│                                                                         │
│  Pros: Simple, no versioning needed                                     │
│  Cons: Downtime, not always feasible for long-running workflows        │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 8.5 Production Recipes

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    COMMON PRODUCTION RECIPES                             │
│                                                                         │
│  RECIPE 1: Adding a New Activity Call                                   │
│  ────────────────────────────────────                                   │
│                                                                         │
│    val version = Workflow.getVersion("notify-admin", DEFAULT_VERSION, 1)│
│    if (version >= 1) {                                                  │
│        notificationActivities.notifyAdmin(result)                       │
│    }                                                                    │
│                                                                         │
│                                                                         │
│  RECIPE 2: Removing an Activity Call                                    │
│  ───────────────────────────────────                                    │
│                                                                         │
│    // DON'T just delete the call!                                       │
│    // Existing workflows have it in history                             │
│                                                                         │
│    val version = Workflow.getVersion("remove-legacy", DEFAULT_VERSION, 1)│
│    if (version < 1) {                                                   │
│        // Old workflows still call this                                 │
│        legacyActivities.oldMethod()                                     │
│    }                                                                    │
│    // New workflows skip it                                             │
│                                                                         │
│                                                                         │
│  RECIPE 3: Changing Loop Bounds                                         │
│  ─────────────────────────────                                          │
│                                                                         │
│    val version = Workflow.getVersion("bigger-chunks", DEFAULT_VERSION, 1)│
│    val chunkSize = if (version >= 1) 10 else 5                         │
│    val chunks = payments.chunked(chunkSize)                            │
│                                                                         │
│                                                                         │
│  RECIPE 4: Changing Activity Timeout                                    │
│  ───────────────────────────────────                                    │
│                                                                         │
│    // Safe! Timeout only affects NEW activity executions                │
│    // Doesn't affect already-completed activities in history            │
│    ActivityOptions.newBuilder()                                         │
│        .setStartToCloseTimeout(Duration.ofSeconds(60))  // was 30      │
│        .build()                                                         │
│                                                                         │
│                                                                         │
│  RECIPE 5: Changing Retry Policy                                        │
│  ───────────────────────────────                                        │
│                                                                         │
│    // Safe! Retry policy only affects NEW activity executions           │
│    RetryOptions.newBuilder()                                            │
│        .setMaximumAttempts(5)  // was 3                                │
│        .build()                                                         │
│                                                                         │
│                                                                         │
│  RECIPE 6: Adding New Workflow Query                                    │
│  ───────────────────────────────────                                    │
│                                                                         │
│    // Safe! Queries don't affect history                                │
│    @QueryMethod                                                         │
│    fun getDetailedProgress(): DetailedProgressInfo {                   │
│        return DetailedProgressInfo(...)                                 │
│    }                                                                    │
│                                                                         │
│                                                                         │
│  RECIPE 7: Bug Fix in Activity                                          │
│  ─────────────────────────────                                          │
│                                                                         │
│    // Safe! Activity results are in history                             │
│    // Fix only affects NEW activity executions                          │
│    override fun getGatewayForPayment(paymentId: String): GatewayInfo { │
│        // Fixed bug here - doesn't affect replaying workflows          │
│        return fixedImplementation(paymentId)                            │
│    }                                                                    │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 8.6 Monitoring and Observability

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    MONITORING TEMPORAL IN PRODUCTION                     │
│                                                                         │
│  TEMPORAL UI                                                            │
│  ───────────                                                            │
│  • View all workflows: running, completed, failed                       │
│  • Inspect workflow history event by event                              │
│  • Query workflow state                                                 │
│  • Terminate stuck workflows                                            │
│  • View pending activities and retries                                  │
│                                                                         │
│  Access: http://localhost:8233 (local)                                  │
│                                                                         │
│                                                                         │
│  KEY METRICS TO MONITOR                                                 │
│  ──────────────────────                                                 │
│                                                                         │
│  Workflow Metrics:                                                      │
│  • workflow_completed_total       - successful completions              │
│  • workflow_failed_total          - workflow failures                   │
│  • workflow_canceled_total        - canceled workflows                  │
│  • workflow_execution_time        - end-to-end duration                 │
│                                                                         │
│  Activity Metrics:                                                      │
│  • activity_execution_time        - activity duration                   │
│  • activity_task_failed_total     - activity failures                   │
│  • activity_schedule_to_start     - queue wait time                     │
│                                                                         │
│  Worker Metrics:                                                        │
│  • worker_task_slots_available    - worker capacity                     │
│  • poller_count                   - active pollers                      │
│                                                                         │
│                                                                         │
│  ALERTS TO CONFIGURE                                                    │
│  ────────────────────                                                   │
│                                                                         │
│  • High workflow failure rate (> 1%)                                    │
│  • Activity retry rate spike                                            │
│  • Long schedule-to-start latency (> 5s)                               │
│  • History size approaching limit (50k events)                          │
│  • Worker count drop                                                    │
│                                                                         │
│                                                                         │
│  TEMPORAL CLI COMMANDS                                                  │
│  ─────────────────────                                                  │
│                                                                         │
│  # List running workflows                                               │
│  temporal workflow list --query "ExecutionStatus='Running'"            │
│                                                                         │
│  # Describe workflow                                                    │
│  temporal workflow describe -w <workflow-id>                           │
│                                                                         │
│  # View workflow history                                                │
│  temporal workflow show -w <workflow-id>                               │
│                                                                         │
│  # Query workflow                                                       │
│  temporal workflow query -w <workflow-id> --type getProgress           │
│                                                                         │
│  # Terminate workflow                                                   │
│  temporal workflow terminate -w <workflow-id> --reason "Manual stop"   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 8.7 Testing Workflows

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    TESTING TEMPORAL WORKFLOWS                            │
│                                                                         │
│  REPLAY TESTING (Critical for safe deployments)                         │
│  ──────────────────────────────────────────────                         │
│                                                                         │
│  Save production workflow histories:                                    │
│                                                                         │
│    temporal workflow show \                                             │
│      --workflow-id payment-check-xxx \                                  │
│      --output json > workflow_history.json                              │
│                                                                         │
│  Write replay test:                                                     │
│                                                                         │
│    @Test                                                                │
│    fun `workflow replays correctly after changes`() {                  │
│        val history = WorkflowHistoryLoader                             │
│            .readHistory("workflow_history.json")                        │
│                                                                         │
│        // This throws if replay fails                                   │
│        WorkflowReplayer.replayWorkflowExecution(                       │
│            history,                                                     │
│            PaymentStatusCheckWorkflowImpl::class.java                  │
│        )                                                                │
│    }                                                                    │
│                                                                         │
│  Run before EVERY deployment to catch determinism issues               │
│                                                                         │
│                                                                         │
│  UNIT TESTING WORKFLOWS                                                 │
│  ──────────────────────                                                 │
│                                                                         │
│  Use TestWorkflowEnvironment:                                          │
│                                                                         │
│    @Test                                                                │
│    fun `workflow processes payments correctly`() {                     │
│        val testEnv = TestWorkflowEnvironment.newInstance()             │
│        val worker = testEnv.newWorker(TASK_QUEUE)                      │
│                                                                         │
│        worker.registerWorkflowImplementationTypes(                      │
│            PaymentStatusCheckWorkflowImpl::class.java                  │
│        )                                                                │
│        worker.registerActivitiesImplementations(                        │
│            MockElasticsearchActivities(),                               │
│            MockPaymentGatewayActivities()                               │
│        )                                                                │
│                                                                         │
│        testEnv.start()                                                  │
│                                                                         │
│        val workflow = testEnv.workflowClient                           │
│            .newWorkflowStub(PaymentStatusCheckWorkflow::class.java,    │
│                WorkflowOptions.newBuilder()                             │
│                    .setTaskQueue(TASK_QUEUE)                            │
│                    .build()                                             │
│            )                                                            │
│                                                                         │
│        val result = workflow.checkPaymentStatuses(input)               │
│                                                                         │
│        assertThat(result.successful).hasSize(3)                        │
│        testEnv.close()                                                  │
│    }                                                                    │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 9. Quick Reference

### 9.1 Concept Summary

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         TEMPORAL CONCEPTS AT A GLANCE                    │
│                                                                         │
│  COMPONENT          WHAT IT IS                       WHERE IT RUNS      │
│  ─────────          ──────────                       ─────────────      │
│  Temporal Server    Orchestrator, state store        Your infra/Cloud   │
│  Worker             Executes workflows/activities    Your application   │
│  Workflow           Durable, deterministic function  On Worker          │
│  Activity           Side-effect operation            On Worker          │
│  Task Queue         Routes tasks to workers          Temporal Server    │
│  Workflow Client    Starts/queries workflows         Your application   │
│                                                                         │
│                                                                         │
│  TERM              MEANING                                              │
│  ────              ───────                                              │
│  Determinism       Same input → same decisions (for replay)            │
│  Replay            Re-executing workflow from history                   │
│  History           Immutable log of workflow events                     │
│  Durable           Survives failures, restarts, deployments            │
│  Idempotent        Safe to execute multiple times                       │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 9.2 What Goes Where

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    WORKFLOW vs ACTIVITY DECISION GUIDE                   │
│                                                                         │
│  PUT IN WORKFLOW:                   PUT IN ACTIVITY:                    │
│  ────────────────                   ────────────────                    │
│  • Orchestration logic              • HTTP calls                        │
│  • Decision making                  • Database queries                  │
│  • Data transformation              • File I/O                          │
│  • Loops and conditionals           • External service calls            │
│  • Spawning child workflows         • Sending emails/notifications      │
│  • Waiting for signals              • Any side effect                   │
│  • Progress tracking state          • Reading environment/config        │
│                                                                         │
│                                                                         │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │ RULE OF THUMB                                                   │    │
│  │                                                                 │    │
│  │ If it talks to the outside world → Activity                    │    │
│  │ If it's pure computation        → Workflow                      │    │
│  │                                                                 │    │
│  └────────────────────────────────────────────────────────────────┘    │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 9.3 Change Safety Matrix

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         CHANGE SAFETY MATRIX                             │
│                                                                         │
│  CHANGE TYPE                          SAFE?    SOLUTION                 │
│  ───────────                          ─────    ────────                 │
│                                                                         │
│  ACTIVITY CHANGES                                                       │
│  ────────────────                                                       │
│  Fix bug in activity logic            ✅ Yes   Just deploy              │
│  Change activity implementation       ✅ Yes   Just deploy              │
│  Add logging/metrics                  ✅ Yes   Just deploy              │
│  Change timeout/retry policy          ✅ Yes   Just deploy              │
│  Add optional parameter               ✅ Yes   Just deploy              │
│  Change return type                   ❌ No    New activity + version   │
│  Remove/rename activity               ❌ No    Version + deprecate      │
│                                                                         │
│  WORKFLOW CHANGES                                                       │
│  ────────────────                                                       │
│  Add new activity call                ⚠️ Need  Workflow.getVersion()    │
│  Remove activity call                 ⚠️ Need  Workflow.getVersion()    │
│  Reorder activity calls               ⚠️ Need  Workflow.getVersion()    │
│  Change loop iterations               ⚠️ Need  Workflow.getVersion()    │
│  Add/remove child workflow            ⚠️ Need  Workflow.getVersion()    │
│  Change conditional logic             ⚠️ Need  Workflow.getVersion()    │
│  Add query method                     ✅ Yes   Just deploy              │
│  Change workflow input type           ❌ No    New workflow type        │
│                                                                         │
│  INFRASTRUCTURE CHANGES                                                 │
│  ──────────────────────                                                 │
│  Add more workers                     ✅ Yes   Just deploy              │
│  Change task queue name               ❌ No    Blue-green deploy        │
│  Update Temporal Server               ✅ Yes   Follow upgrade guide     │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 9.4 Troubleshooting Guide

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         COMMON ISSUES & SOLUTIONS                        │
│                                                                         │
│  ISSUE: NonDeterministicException                                       │
│  ─────────────────────────────────                                      │
│  Cause: Workflow code changed incompatibly                              │
│  Fix:                                                                   │
│  1. Revert the change                                                   │
│  2. Add Workflow.getVersion() around new code                          │
│  3. Redeploy                                                            │
│                                                                         │
│                                                                         │
│  ISSUE: Activity keeps retrying forever                                 │
│  ───────────────────────────────────────                                │
│  Cause: External service down, or activity bug                          │
│  Fix:                                                                   │
│  1. Check external service health                                       │
│  2. Set reasonable maxAttempts in retry policy                         │
│  3. Add non-retryable exception types for permanent failures           │
│                                                                         │
│                                                                         │
│  ISSUE: Workflow stuck, not progressing                                 │
│  ──────────────────────────────────────                                 │
│  Cause: Waiting for activity, signal, or timer                          │
│  Debug:                                                                 │
│  1. Check workflow in Temporal UI                                       │
│  2. Look at pending tasks                                               │
│  3. Check if workers are running                                        │
│                                                                         │
│                                                                         │
│  ISSUE: "Workflow history too large" error                              │
│  ─────────────────────────────────────────                              │
│  Cause: Too many events (>50,000)                                       │
│  Fix:                                                                   │
│  1. Use ContinueAsNew to reset history                                 │
│  2. Break into smaller child workflows                                  │
│  3. Batch activities to reduce event count                              │
│                                                                         │
│                                                                         │
│  ISSUE: Workers not picking up tasks                                    │
│  ─────────────────────────────────────                                  │
│  Cause: Task queue mismatch or connection issues                        │
│  Debug:                                                                 │
│  1. Verify task queue name matches                                      │
│  2. Check worker logs for connection errors                             │
│  3. Verify Temporal Server is reachable                                 │
│                                                                         │
│                                                                         │
│  ISSUE: Deserialization errors on replay                                │
│  ───────────────────────────────────────                                │
│  Cause: Changed class structure incompatibly                            │
│  Fix:                                                                   │
│  1. Add missing fields with defaults                                    │
│  2. Configure Jackson to ignore unknown properties                      │
│  3. For major changes: new workflow type + migration                    │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 9.5 Architecture Diagram (Complete)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    PAYMENT STATUS CHECK - COMPLETE ARCHITECTURE          │
│                                                                         │
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                         CLIENT REQUEST                             │  │
│  │                                                                    │  │
│  │  POST /payments/check-status                                       │  │
│  │  { paymentIds: [100 IDs] }                                        │  │
│  └────────────────────────────────┬──────────────────────────────────┘  │
│                                   │                                      │
│                                   ▼                                      │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                         YOUR APPLICATION                           │  │
│  │                                                                    │  │
│  │   ┌──────────────────────┐       ┌───────────────────────────┐    │  │
│  │   │   REST Controller    │       │         WORKER            │    │  │
│  │   │                      │       │                           │    │  │
│  │   │  PaymentController   │       │  PaymentStatusCheckWF     │    │  │
│  │   │         │            │       │         │                 │    │  │
│  │   │         ▼            │       │         ▼                 │    │  │
│  │   │  PaymentService      │       │  GatewayWorkflow (child)  │    │  │
│  │   │         │            │       │         │                 │    │  │
│  │   │         │            │       │         ▼                 │    │  │
│  │   │  ┌──────▼──────┐     │       │  ┌─────────────────────┐  │    │  │
│  │   │  │ Workflow    │     │       │  │ Activities          │  │    │  │
│  │   │  │ Client      │─────┼───────┼─►│ • ES lookup         │  │    │  │
│  │   │  └─────────────┘     │       │  │ • IDB call          │  │    │  │
│  │   │                      │       │  │ • PGI call          │  │    │  │
│  │   └──────────────────────┘       │  └──────────┬──────────┘  │    │  │
│  │                                  │             │             │    │  │
│  └──────────────────────────────────┼─────────────┼─────────────┘    │  │
│                                     │             │                   │  │
│              ┌──────────────────────┘             │                   │  │
│              │                                    │                   │  │
│              ▼                                    ▼                   │  │
│  ┌───────────────────────┐          ┌─────────────────────────────┐  │  │
│  │    TEMPORAL SERVER    │          │    EXTERNAL SERVICES        │  │  │
│  │                       │          │                             │  │  │
│  │  • Task Queues        │          │  ┌─────────────────────┐    │  │  │
│  │  • Workflow History   │          │  │   Elasticsearch     │    │  │  │
│  │  • Timer Management   │          │  │   (gateway lookup)  │    │  │  │
│  │  • Retry Logic        │          │  └─────────────────────┘    │  │  │
│  │                       │          │                             │  │  │
│  │  ┌─────────────────┐  │          │  ┌─────────────────────┐    │  │  │
│  │  │    Database     │  │          │  │    IDB Facade       │    │  │  │
│  │  │  (PostgreSQL)   │  │          │  │   (batch notify)    │    │  │  │
│  │  └─────────────────┘  │          │  └─────────────────────┘    │  │  │
│  │                       │          │                             │  │  │
│  │  ┌─────────────────┐  │          │  ┌─────────────────────┐    │  │  │
│  │  │   Temporal UI   │  │          │  │    PGI Gateway      │    │  │  │
│  │  │   (monitoring)  │  │          │  │  (status check)     │    │  │  │
│  │  └─────────────────┘  │          │  └─────────────────────┘    │  │  │
│  │                       │          │                             │  │  │
│  └───────────────────────┘          └─────────────────────────────┘  │  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Summary

This guide covered:

1. **The Business Problem**: Processing payments across multiple gateways with complex parallelism and failure requirements

2. **Why It's Hard**: Controlled parallelism, partial failures, long-running operations, and observability challenges

3. **Temporal's Architecture**: Server as coordinator, Workers execute code, history enables durability

4. **Core Concepts**: 
   - Workflows (deterministic orchestration)
   - Activities (side effects)
   - Idempotency (for at-least-once semantics)
   - Replay (how durability works)

5. **Our Solution**: Parent-child workflow pattern with batched parallel activities

6. **Execution Model**: How Temporal coordinates between server and workers

7. **Failure Handling**: Automatic recovery from crashes at any point

8. **Production Guide**: 
   - Determinism requirements
   - Versioning changes safely
   - Schema evolution
   - Deployment strategies
   - Monitoring and testing

**Key Takeaways:**

- Temporal doesn't execute your code—it orchestrates and persists state
- Workflows must be deterministic for replay to work
- Activities handle all side effects and are automatically retried
- Use `Workflow.getVersion()` for safe workflow changes
- Activity changes are generally safe; workflow changes need care
- Test replay before deploying workflow changes

---

*Document generated for Payment Status Check Service demo.*
