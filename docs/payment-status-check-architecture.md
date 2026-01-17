# Payment Status Check - Architecture Document

## Overview

This document describes the architecture for a payment status check system that processes a list of payment IDs through multiple external services using Temporal workflow orchestration.

### Business Flow

1. Accept a list of payment IDs via REST API
2. Query Elasticsearch to determine the gateway for each payment
3. Group payment IDs by gateway name
4. Chunk payments per gateway (max 5 per chunk) to avoid overloading
5. For each gateway, process chunks sequentially:
   - Call IDB Facade with gateway name and payment IDs (batch)
   - Call PGI Gateway to trigger status check for each payment (sequential, one by one)
6. Return aggregated results

### Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| ES Lookup | Individual calls per payment | Simple, no batch API available |
| ES Parallelism | Configurable (default 10) | Balance speed vs ES load |
| Workflow Granularity | Parent + child per gateway | Isolated failures, parallel across gateways |
| Chunk Size | Configurable (default 5) | Prevent gateway overload |
| Chunks per Gateway | Sequential | Prevent gateway overload |
| IDB Call | Batch per chunk | API supports batch |
| PGI Call | Sequential, one per payment | API supports single ID only |
| API Response | Fire-and-forget | Long-running process, client polls for status |
| Failure Handling | Partial success | Continue processing other gateways/chunks on failure |

---

## API Endpoints

### POST /payments/check-status

Starts a new payment status check workflow.

**Request:**
```json
{
  "paymentIds": ["pay_001", "pay_002", "pay_003", "pay_004", "pay_005"]
}
```

**Response (202 Accepted):**
```json
{
  "workflowId": "payment-check-1705487234",
  "status": "STARTED"
}
```

---

### GET /payments/check-status/{workflowId}

Query the status and result of a workflow.

**Response - In Progress:**
```json
{
  "workflowId": "payment-check-1705487234",
  "status": "RUNNING",
  "progress": {
    "totalPayments": 50,
    "gatewaysIdentified": 3,
    "chunksTotal": 12,
    "chunksCompleted": 5,
    "chunksFailed": 0
  },
  "result": null
}
```

**Response - Completed Successfully:**
```json
{
  "workflowId": "payment-check-1705487234",
  "status": "COMPLETED",
  "progress": {
    "totalPayments": 50,
    "gatewaysIdentified": 3,
    "chunksTotal": 12,
    "chunksCompleted": 12,
    "chunksFailed": 0
  },
  "result": {
    "successful": {
      "stripe": ["pay_001", "pay_003", "pay_010"],
      "adyen": ["pay_002", "pay_004", "pay_005"],
      "paypal": ["pay_006", "pay_007"]
    },
    "failed": {},
    "gatewayLookupFailed": []
  }
}
```

**Response - Completed with Partial Failures:**
```json
{
  "workflowId": "payment-check-1705487234",
  "status": "COMPLETED",
  "progress": {
    "totalPayments": 50,
    "gatewaysIdentified": 3,
    "chunksTotal": 12,
    "chunksCompleted": 10,
    "chunksFailed": 2
  },
  "result": {
    "successful": {
      "stripe": ["pay_001", "pay_003"],
      "adyen": ["pay_002", "pay_004", "pay_005"]
    },
    "failed": {
      "paypal": [
        {
          "chunkIndex": 0,
          "paymentIds": ["pay_006", "pay_007"],
          "error": "IDB facade timeout after 3 retries",
          "stage": "IDB"
        }
      ]
    },
    "gatewayLookupFailed": ["pay_099", "pay_100"]
  }
}
```

**Response - Workflow Not Found:**
```json
{
  "error": "Workflow not found",
  "workflowId": "payment-check-invalid"
}
```

---

## Configuration

```yaml
payment-check:
  elasticsearch:
    max-parallel-queries: 10        # Max concurrent ES calls during gateway lookup

  gateway:
    max-payments-per-chunk: 5       # Max payment IDs per chunk

  retry:
    max-attempts: 3                 # Retry count for all activities
    timeout-seconds: 5              # Timeout per activity attempt

temporal:
  service-address: localhost:7233
  namespace: default
  task-queue: payment-status-check
```

---

## Workflow Architecture

### High-Level Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     PAYMENT-ACT APPLICATION (Combined)                       │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │                          REST API LAYER                                │ │
│  │                                                                        │ │
│  │  PaymentStatusController                                              │ │
│  │    POST /payments/check-status  ──► Start workflow, return workflowId │ │
│  │    GET  /payments/check-status/{id} ──► Query workflow status/result  │ │
│  │                                                                        │ │
│  │  PaymentStatusService                                                 │ │
│  │    - Temporal WorkflowClient injection                                │ │
│  │    - Start workflow execution                                         │ │
│  │    - Query workflow state                                             │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                                      │                                      │
│                                      ▼                                      │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │                        TEMPORAL WORKER LAYER                           │ │
│  │                                                                        │ │
│  │  Workflow Implementations                                             │ │
│  │    PaymentStatusCheckWorkflow (Parent)                                │ │
│  │      └── GatewayWorkflow (Child, one per gateway)                     │ │
│  │                                                                        │ │
│  │  Activity Implementations                                             │ │
│  │    ElasticsearchActivities                                            │ │
│  │      └── getGatewayForPayment(paymentId) ──► Elasticsearch            │ │
│  │    PaymentGatewayActivities                                           │ │
│  │      ├── callIdbFacade(gateway, paymentIds) ──► IDB Facade            │ │
│  │      └── callPgiGateway(gateway, paymentId) ──► PGI Gateway           │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              TEMPORAL SERVER                                 │
│                                                                             │
│  - Workflow orchestration                                                   │
│  - State persistence (PostgreSQL)                                          │
│  - Retry management                                                         │
│  - Workflow history                                                         │
│  - Query handling                                                           │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### Parent Workflow: PaymentStatusCheckWorkflow

Orchestrates the entire payment status check process.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      PaymentStatusCheckWorkflow                              │
│                                                                             │
│  Input: List of payment IDs (e.g., 50 payments)                            │
│  Output: CheckStatusResult with successful/failed/lookupFailed              │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │ STEP 1: Elasticsearch Gateway Lookup                                  │ │
│  │                                                                       │ │
│  │ For each paymentId (parallel, max 10 concurrent):                    │ │
│  │   Call activity: getGatewayForPayment(paymentId)                     │ │
│  │                                                                       │ │
│  │ Result: Map<PaymentId, GatewayName> + List<FailedLookups>            │ │
│  │                                                                       │ │
│  │ Example:                                                              │ │
│  │   pay_001 → stripe                                                   │ │
│  │   pay_002 → adyen                                                    │ │
│  │   pay_003 → stripe                                                   │ │
│  │   pay_099 → FAILED (added to gatewayLookupFailed)                    │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                                      │                                      │
│                                      ▼                                      │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │ STEP 2: Group by Gateway and Chunk                                    │ │
│  │                                                                       │ │
│  │ Group payment IDs by gateway name:                                   │ │
│  │   stripe: [pay_001, pay_003, pay_010, pay_011, pay_015,              │ │
│  │            pay_020, pay_025, pay_030, pay_035, pay_040]              │ │
│  │   adyen:  [pay_002, pay_004, pay_005, pay_012, pay_022]              │ │
│  │   paypal: [pay_006, pay_007]                                         │ │
│  │                                                                       │ │
│  │ Chunk each gateway (max 5 per chunk):                                │ │
│  │   stripe: [[pay_001,pay_003,pay_010,pay_011,pay_015],                │ │
│  │            [pay_020,pay_025,pay_030,pay_035,pay_040]]                │ │
│  │   adyen:  [[pay_002,pay_004,pay_005,pay_012,pay_022]]                │ │
│  │   paypal: [[pay_006,pay_007]]                                        │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                                      │                                      │
│                                      ▼                                      │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │ STEP 3: Spawn Child Workflows (parallel across gateways)              │ │
│  │                                                                       │ │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐       │ │
│  │  │ GatewayWorkflow │  │ GatewayWorkflow │  │ GatewayWorkflow │       │ │
│  │  │ stripe          │  │ adyen           │  │ paypal          │       │ │
│  │  │ 2 chunks        │  │ 1 chunk         │  │ 1 chunk         │       │ │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────┘       │ │
│  │          │                    │                    │                  │ │
│  │          └────────────────────┴────────────────────┘                  │ │
│  │                               │                                       │ │
│  │                    Running in parallel                                │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                                      │                                      │
│                                      ▼                                      │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │ STEP 4: Await and Aggregate Results                                   │ │
│  │                                                                       │ │
│  │ Collect results from all child workflows:                            │ │
│  │   - Merge successful payments by gateway                             │ │
│  │   - Collect failed chunks with error details                         │ │
│  │   - Include gatewayLookupFailed from Step 1                          │ │
│  │                                                                       │ │
│  │ Return: CheckStatusResult                                            │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                                                                             │
│  Query Method: getProgress() → ProgressInfo                                │
│    - Tracks: totalPayments, gatewaysIdentified, chunksTotal,               │
│              chunksCompleted, chunksFailed                                 │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### Child Workflow: GatewayWorkflow

Processes all chunks for a single gateway sequentially.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           GatewayWorkflow                                    │
│                                                                             │
│  Input: gateway name, list of chunks (each chunk is list of payment IDs)   │
│  Output: GatewayResult with successful payments and failed chunks           │
│                                                                             │
│  Example Input:                                                             │
│    gateway: "stripe"                                                        │
│    chunks: [[pay_001,pay_003,pay_010,pay_011,pay_015],                     │
│             [pay_020,pay_025,pay_030,pay_035,pay_040]]                     │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │ PROCESS CHUNKS SEQUENTIALLY                                           │ │
│  │                                                                       │ │
│  │ ┌─────────────────────────────────────────────────────────────────┐  │ │
│  │ │ Chunk 1: [pay_001, pay_003, pay_010, pay_011, pay_015]          │  │ │
│  │ │                                                                 │  │ │
│  │ │ Step A: IDB Facade (batch call)                                │  │ │
│  │ │   callIdbFacade("stripe", [pay_001,pay_003,pay_010,pay_011,    │  │ │
│  │ │                            pay_015])                            │  │ │
│  │ │   - Retries: up to 3 times                                     │  │ │
│  │ │   - Timeout: 5 seconds per attempt                             │  │ │
│  │ │   - On failure: Mark entire chunk as failed, skip to next chunk│  │ │
│  │ │                                                                 │  │ │
│  │ │ Step B: PGI Gateway (sequential, one by one)                   │  │ │
│  │ │   for each paymentId in chunk:                                 │  │ │
│  │ │     callPgiGateway("stripe", paymentId)                        │  │ │
│  │ │     - Retries: up to 3 times                                   │  │ │
│  │ │     - Timeout: 5 seconds per attempt                           │  │ │
│  │ │     - On failure: Record failed payment, continue with next    │  │ │
│  │ │                                                                 │  │ │
│  │ │   PGI("stripe", pay_001) ──► success ✓                         │  │ │
│  │ │   PGI("stripe", pay_003) ──► success ✓                         │  │ │
│  │ │   PGI("stripe", pay_010) ──► success ✓                         │  │ │
│  │ │   PGI("stripe", pay_011) ──► success ✓                         │  │ │
│  │ │   PGI("stripe", pay_015) ──► success ✓                         │  │ │
│  │ └─────────────────────────────────────────────────────────────────┘  │ │
│  │                              │                                        │ │
│  │                              ▼                                        │ │
│  │ ┌─────────────────────────────────────────────────────────────────┐  │ │
│  │ │ Chunk 2: [pay_020, pay_025, pay_030, pay_035, pay_040]          │  │ │
│  │ │                                                                 │  │ │
│  │ │ Step A: IDB Facade (batch call)                                │  │ │
│  │ │   callIdbFacade("stripe", [pay_020,pay_025,pay_030,pay_035,    │  │ │
│  │ │                            pay_040])                            │  │ │
│  │ │                                                                 │  │ │
│  │ │ Step B: PGI Gateway (sequential, one by one)                   │  │ │
│  │ │   PGI("stripe", pay_020) ──► success ✓                         │  │ │
│  │ │   PGI("stripe", pay_025) ──► FAILED ✗ (after 3 retries)        │  │ │
│  │ │   PGI("stripe", pay_030) ──► success ✓                         │  │ │
│  │ │   PGI("stripe", pay_035) ──► success ✓                         │  │ │
│  │ │   PGI("stripe", pay_040) ──► success ✓                         │  │ │
│  │ └─────────────────────────────────────────────────────────────────┘  │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                                                                             │
│  Output:                                                                    │
│    successful: [pay_001,pay_003,pay_010,pay_011,pay_015,                   │
│                 pay_020,pay_030,pay_035,pay_040]                           │
│    failed: [{chunkIndex: 1, paymentIds: [pay_025],                         │
│              error: "PGI timeout", stage: "PGI"}]                          │
│                                                                             │
│  Query Method: getChunkProgress() → ChunkProgress                          │
│    - Tracks: totalChunks, completedChunks, currentChunkIndex               │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Activities

### ElasticsearchActivities

| Activity | Input | Output | Description |
|----------|-------|--------|-------------|
| `getGatewayForPayment` | paymentId: String | GatewayInfo | Queries ES to find gateway for payment |

**Retry Policy:**
- Max attempts: 3 (configurable)
- Timeout: 5 seconds (configurable)
- Backoff: Exponential

**Error Handling:**
- On failure after retries: Exception propagates to workflow
- Workflow catches and adds paymentId to `gatewayLookupFailed` list

---

### PaymentGatewayActivities

| Activity | Input | Output | Description |
|----------|-------|--------|-------------|
| `callIdbFacade` | gateway: String, paymentIds: List | void | Notifies IDB about payments (batch) |
| `callPgiGateway` | gateway: String, paymentId: String | void | Triggers PGI status check (single) |

**Retry Policy:**
- Max attempts: 3 (configurable)
- Timeout: 5 seconds (configurable)
- Backoff: Exponential

**Error Handling:**
- IDB failure: Entire chunk marked as failed, workflow continues to next chunk
- PGI failure: Individual payment marked as failed, workflow continues to next payment

---

## Data Flow Diagram

```
                                    REQUEST
                                       │
                                       ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                         POST /payments/check-status                       │
│                         Body: { paymentIds: [50 IDs] }                   │
└──────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                              START WORKFLOW                               │
│                     Return: { workflowId: "xyz" }                        │
└──────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                         ELASTICSEARCH LOOKUPS                             │
│                                                                          │
│   ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐     (max 10 parallel)        │
│   │ ES  │ │ ES  │ │ ES  │ │ ES  │ │ ES  │ ...                          │
│   │ #1  │ │ #2  │ │ #3  │ │ #4  │ │ #5  │                              │
│   └──┬──┘ └──┬──┘ └──┬──┘ └──┬──┘ └──┬──┘                              │
│      │       │       │       │       │                                   │
│      ▼       ▼       ▼       ▼       ▼                                   │
│   stripe   adyen   stripe  paypal  stripe                               │
└──────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                          GROUP AND CHUNK                                  │
│                                                                          │
│   stripe: [10 payments] ──► chunk1[5], chunk2[5]                        │
│   adyen:  [5 payments]  ──► chunk1[5]                                   │
│   paypal: [2 payments]  ──► chunk1[2]                                   │
└──────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                    CHILD WORKFLOWS (parallel per gateway)                 │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ STRIPE WORKFLOW                                                   │   │
│  │                                                                   │   │
│  │ Chunk 1:                                                         │   │
│  │   IDB(stripe, [5 ids]) ─────────────────────────────────► IDB    │   │
│  │   PGI(stripe, id1) ─────────────────────────────────────► PGI    │   │
│  │   PGI(stripe, id2) ─────────────────────────────────────► PGI    │   │
│  │   PGI(stripe, id3) ─────────────────────────────────────► PGI    │   │
│  │   PGI(stripe, id4) ─────────────────────────────────────► PGI    │   │
│  │   PGI(stripe, id5) ─────────────────────────────────────► PGI    │   │
│  │                                                                   │   │
│  │ Chunk 2:                                                         │   │
│  │   IDB(stripe, [5 ids]) ─────────────────────────────────► IDB    │   │
│  │   PGI(stripe, id6) ─────────────────────────────────────► PGI    │   │
│  │   ...                                                             │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ ADYEN WORKFLOW (runs in parallel with Stripe)                     │   │
│  │                                                                   │   │
│  │ Chunk 1:                                                         │   │
│  │   IDB(adyen, [5 ids]) ──────────────────────────────────► IDB    │   │
│  │   PGI(adyen, id1) ──────────────────────────────────────► PGI    │   │
│  │   ...                                                             │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ PAYPAL WORKFLOW (runs in parallel with Stripe and Adyen)          │   │
│  │                                                                   │   │
│  │ Chunk 1:                                                         │   │
│  │   IDB(paypal, [2 ids]) ─────────────────────────────────► IDB    │   │
│  │   PGI(paypal, id1) ─────────────────────────────────────► PGI    │   │
│  │   PGI(paypal, id2) ─────────────────────────────────────► PGI    │   │
│  └──────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                         AGGREGATE RESULTS                                 │
│                                                                          │
│   successful: { stripe: [...], adyen: [...], paypal: [...] }            │
│   failed: { ... }                                                        │
│   gatewayLookupFailed: [...]                                             │
└──────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                          WORKFLOW COMPLETE                                │
│                                                                          │
│   GET /payments/check-status/{workflowId}                               │
│   Returns: { status: "COMPLETED", result: {...} }                       │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## Error Handling

### Error Scenarios and Behavior

| Scenario | Behavior | Result Location |
|----------|----------|-----------------|
| ES lookup fails for payment X | Retry 3x, then skip payment | `gatewayLookupFailed` list |
| IDB call fails for chunk | Retry 3x, then mark chunk failed | `failed` map under gateway |
| PGI call fails for payment X | Retry 3x, record failure, continue next | `failed` map under gateway |
| Worker crashes | Temporal resumes from checkpoint | No data loss |
| All chunks for gateway fail | Gateway fully in `failed` map | `failed` map |
| Mixed success/failure | Partial results | Both `successful` and `failed` |

### Retry Configuration

```
┌─────────────────────────────────────────────────────────────────┐
│                       RETRY POLICY                               │
│                                                                 │
│   Initial Interval: 1 second                                    │
│   Backoff Coefficient: 2.0                                      │
│   Maximum Interval: 10 seconds                                  │
│   Maximum Attempts: 3 (configurable)                            │
│   Timeout per Attempt: 5 seconds (configurable)                 │
│                                                                 │
│   Example Timeline:                                             │
│     Attempt 1: t=0s    ──► fail                                │
│     Attempt 2: t=1s    ──► fail (1s backoff)                   │
│     Attempt 3: t=3s    ──► fail (2s backoff)                   │
│     Result: Activity marked as failed                          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Sequence Diagram

```
Client              REST API           Temporal            Worker              ES        IDB       PGI
  │                    │                  │                   │                 │          │         │
  │ POST /check-status │                  │                   │                 │          │         │
  │ [pay_001..050]     │                  │                   │                 │          │         │
  │───────────────────>│                  │                   │                 │          │         │
  │                    │                  │                   │                 │          │         │
  │                    │ startWorkflow()  │                   │                 │          │         │
  │                    │─────────────────>│                   │                 │          │         │
  │                    │                  │                   │                 │          │         │
  │                    │ workflowId       │                   │                 │          │         │
  │                    │<─────────────────│                   │                 │          │         │
  │                    │                  │                   │                 │          │         │
  │ 202 Accepted       │                  │                   │                 │          │         │
  │ {workflowId}       │                  │                   │                 │          │         │
  │<───────────────────│                  │                   │                 │          │         │
  │                    │                  │                   │                 │          │         │
  │                    │                  │ scheduleActivity  │                 │          │         │
  │                    │                  │──────────────────>│                 │          │         │
  │                    │                  │                   │                 │          │         │
  │                    │                  │                   │ getGateway(001) │          │         │
  │                    │                  │                   │────────────────>│          │         │
  │                    │                  │                   │ getGateway(002) │          │         │
  │                    │                  │                   │────────────────>│          │         │
  │                    │                  │                   │     ...         │          │         │
  │                    │                  │                   │ (max 10 parallel)         │         │
  │                    │                  │                   │<────────────────│          │         │
  │                    │                  │                   │ stripe, adyen...│          │         │
  │                    │                  │                   │                 │          │         │
  │                    │                  │                   │ (group & chunk) │          │         │
  │                    │                  │                   │                 │          │         │
  │                    │                  │ startChildWorkflow│                 │          │         │
  │                    │                  │ (stripe)          │                 │          │         │
  │                    │                  │──────────────────>│                 │          │         │
  │                    │                  │ startChildWorkflow│                 │          │         │
  │                    │                  │ (adyen)           │                 │          │         │
  │                    │                  │──────────────────>│                 │          │         │
  │                    │                  │                   │                 │          │         │
  │                    │                  │                   │ IDB(stripe,[5]) │          │         │
  │                    │                  │                   │────────────────────────────>│         │
  │                    │                  │                   │<────────────────────────────│         │
  │                    │                  │                   │                 │          │         │
  │                    │                  │                   │ PGI(stripe,001) │          │         │
  │                    │                  │                   │───────────────────────────────────────>│
  │                    │                  │                   │<───────────────────────────────────────│
  │                    │                  │                   │ PGI(stripe,003) │          │         │
  │                    │                  │                   │───────────────────────────────────────>│
  │                    │                  │                   │<───────────────────────────────────────│
  │                    │                  │                   │     ...         │          │         │
  │                    │                  │                   │                 │          │         │
  │ GET /check-status/ │                  │                   │                 │          │         │
  │ {workflowId}       │                  │                   │                 │          │         │
  │───────────────────>│                  │                   │                 │          │         │
  │                    │ query(progress)  │                   │                 │          │         │
  │                    │─────────────────>│                   │                 │          │         │
  │                    │ ProgressInfo     │                   │                 │          │         │
  │                    │<─────────────────│                   │                 │          │         │
  │ {status: RUNNING,  │                  │                   │                 │          │         │
  │  progress: {...}}  │                  │                   │                 │          │         │
  │<───────────────────│                  │                   │                 │          │         │
  │                    │                  │                   │                 │          │         │
  │        ...         │                  │   (processing)    │                 │          │         │
  │                    │                  │                   │                 │          │         │
  │                    │                  │ childComplete     │                 │          │         │
  │                    │                  │<──────────────────│                 │          │         │
  │                    │                  │                   │                 │          │         │
  │                    │                  │ workflowComplete  │                 │          │         │
  │                    │                  │<──────────────────│                 │          │         │
  │                    │                  │                   │                 │          │         │
  │ GET /check-status/ │                  │                   │                 │          │         │
  │ {workflowId}       │                  │                   │                 │          │         │
  │───────────────────>│                  │                   │                 │          │         │
  │                    │ getResult()      │                   │                 │          │         │
  │                    │─────────────────>│                   │                 │          │         │
  │                    │ CheckStatusResult│                   │                 │          │         │
  │                    │<─────────────────│                   │                 │          │         │
  │ {status: COMPLETED,│                  │                   │                 │          │         │
  │  result: {...}}    │                  │                   │                 │          │         │
  │<───────────────────│                  │                   │                 │          │         │
```

---

## Project Structure

```
paymentact/
├── worker/                                          # Combined application
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── kotlin/com/example/paymentact/
│       │   │   ├── PaymentActApplication.kt         # Main entry point
│       │   │   │
│       │   │   ├── config/
│       │   │   │   ├── TemporalConfig.kt            # Temporal client + worker config
│       │   │   │   └── PaymentCheckConfig.kt        # Business config properties
│       │   │   │
│       │   │   ├── controller/
│       │   │   │   └── PaymentStatusController.kt   # REST endpoints
│       │   │   │
│       │   │   ├── service/
│       │   │   │   └── PaymentStatusService.kt      # Workflow client wrapper
│       │   │   │
│       │   │   ├── workflow/
│       │   │   │   ├── PaymentStatusCheckWorkflow.kt      # Interface
│       │   │   │   ├── PaymentStatusCheckWorkflowImpl.kt  # Parent workflow
│       │   │   │   ├── GatewayWorkflow.kt                 # Interface
│       │   │   │   └── GatewayWorkflowImpl.kt             # Child workflow
│       │   │   │
│       │   │   ├── activity/
│       │   │   │   ├── ElasticsearchActivities.kt         # Interface
│       │   │   │   ├── ElasticsearchActivitiesImpl.kt     # ES implementation
│       │   │   │   ├── PaymentGatewayActivities.kt        # Interface
│       │   │   │   └── PaymentGatewayActivitiesImpl.kt    # IDB/PGI implementation
│       │   │   │
│       │   │   └── model/
│       │   │       ├── CheckStatusRequest.kt
│       │   │       ├── CheckStatusResult.kt
│       │   │       ├── CheckStatusQueryResponse.kt
│       │   │       ├── ProgressInfo.kt
│       │   │       ├── GatewayInfo.kt
│       │   │       ├── GatewayResult.kt
│       │   │       └── FailedChunk.kt
│       │   │
│       │   └── resources/
│       │       └── application.yaml
│       │
│       └── test/
│           └── kotlin/com/example/paymentact/
│               └── PaymentActApplicationTests.kt
│
├── environment/
│   ├── docker-compose.yml
│   ├── .env
│   └── dynamicconfig/
│
└── docs/
    └── payment-status-check-architecture.md
```

---

## Dependencies

### Combined Application (worker module)
- Spring Boot Starter Web (REST API + RestClient for ES, IDB, PGI)
- Spring Boot Starter Actuator (health/metrics)
- Spring Boot Starter Validation
- Temporal SDK (`io.temporal:temporal-sdk`)
- Temporal Kotlin (`io.temporal:temporal-kotlin`)
- Jackson Kotlin Module
- Kotlin Reflect

---

## External Service Contracts

### Elasticsearch

**Query:** Get gateway for payment ID

```
GET /payments/_doc/{paymentId}

Response:
{
  "_source": {
    "paymentId": "pay_001",
    "gatewayName": "stripe",
    ...
  }
}
```

### IDB Facade

**Request:** Notify about payments

```
POST /api/v1/payments/notify

Request Body:
{
  "gatewayName": "stripe",
  "paymentIds": ["pay_001", "pay_003", "pay_010"]
}

Response: 200 OK
```

### PGI Gateway

**Request:** Trigger status check

```
POST /api/v1/payments/{paymentId}/check-status

Headers:
  X-Gateway-Name: stripe

Response: 202 Accepted
```

---

## Summary

This architecture provides:

1. **Reliability** - Temporal handles retries, timeouts, and crash recovery
2. **Scalability** - Parallel processing across gateways
3. **Observability** - Query workflow progress, view in Temporal UI
4. **Resilience** - Partial failures don't stop entire workflow
5. **Configurability** - Tune parallelism, chunk sizes, retries via config
6. **Maintainability** - Clear separation of concerns (workflows, activities, models)