# Temporal Saga: Split Payment Implementation

## Context
Split payments allow merchants to distribute a single payment across multiple recipients routed through different gateways. If any part fails, all previously completed parts must be automatically compensated (refunded or voided).


## Architecture Overview

```
Merchant API
    │
    ▼
SplitPurchaseController (REST)
    │
    ▼
Temporal Client → starts SplitPurchaseWorkflow
    │
    ├── Activity: resolveSplitPlan()
    │
    └── for each split part (sequential):
        ├── Activity: executePurchase(gateway, amount, token)
        ├── saga.addCompensation(() -> compensate(gateway, txId))
        │
        └── on failure at any step:
            └── Saga runs compensations in reverse order
```

## Data Models

### SplitPurchaseRequest

```
- posId: String — merchant POS identifier
- amount: long — total amount in minor units (kopecks)
- currency: String — "UAH"
- splitConfigId: String — reference to split configuration
- cardToken: String — tokenized card for payment
- orderId: String — merchant order reference
- webhookUrl: String — URL for result notification
```

### SplitPlan

```
- parts: List<SplitPart>
  - recipient: String — recipient identifier
  - amount: long — amount for this part in minor units
  - gateway: String — gateway code (e.g. "GW_ALPHA", "GW_BETA")
  - merchantAccount: String — recipient's merchant account on this gateway
```

### SplitPartResult

```
- recipient: String
- amount: long
- gateway: String
- transactionId: String — gateway transaction ID (null if not attempted)
- status: enum { SUCCESS, FAILED, NOT_ATTEMPTED, COMPENSATED, COMPENSATION_FAILED }
- compensationAction: enum { VOID, REFUND, NONE }
- errorCode: String — gateway error code if failed
```

### SplitPurchaseResult

```
- orderId: String
- overallStatus: enum { SUCCESS, FAILED }
- failedAtPart: int — index of the part that failed (-1 if all succeeded)
- failureReason: String
- parts: List<SplitPartResult>
```

## Workflow Definition

### Interface: SplitPurchaseWorkflow

```
@WorkflowInterface
Method: executeSplitPurchase(SplitPurchaseRequest request) -> SplitPurchaseResult
```

### Workflow Implementation Logic

1. Call `resolveSplitPlan(request.splitConfigId, request.amount)` activity to get the dynamic list of split parts.

2. Validate split plan:
    - Sum of all part amounts must equal total request amount.
    - Each part must have a valid gateway assignment.
    - If validation fails, return FAILED immediately (no compensations needed).

3. Initialize Saga object.

4. Iterate over split parts **sequentially** (not in parallel):
    - For each part, call `executePurchase(part.gateway, part.amount, request.cardToken, part.merchantAccount)`.
    - On success: register compensation with saga — `saga.addCompensation(() -> compensatePurchase(part.gateway, txId, part.amount))`.
    - On failure: stop iteration, let saga run compensations automatically in reverse order.

5. If all parts succeed, call `notifyMerchant(request.webhookUrl, successResult)`.

6. If any part fails, saga compensates, then call `notifyMerchant(request.webhookUrl, failedResult)`.

7. Return `SplitPurchaseResult` with details of each part.

### Why Sequential, Not Parallel

- If Part B fails, Part C is never attempted — no unnecessary compensations.
- Reduces risk window: fewer transactions to roll back on failure.
- Simpler reasoning about transaction state.

## Activity Definitions

### Activity Interface: SplitPlanActivities

```
resolveSplitPlan(splitConfigId: String, totalAmount: long) -> SplitPlan
```

- Fetches split configuration (from DB or config service).
- Calculates amounts per recipient.
- Resolves gateway for each recipient based on routing rules.
- RetryOptions: initialInterval=1s, maxAttempts=3.

### Activity Interface: GatewayActivities

```
executePurchase(gateway: String, amount: long, cardToken: String, merchantAccount: String) -> PurchaseResult
```

- Routes to the appropriate gateway adapter.
- Executes a token-based purchase.
- Returns transaction ID on success, throws exception on decline.
- StartToCloseTimeout: 30s.
- RetryOptions: initialInterval=2s, maxAttempts=3, nonRetryableErrors=[DeclinedError, InvalidTokenError].

```
compensatePurchase(gateway: String, transactionId: String, amount: long) -> CompensationResult
```

- Smart compensation logic per gateway:
    - If the transaction is not yet settled → attempt `void` (cheaper, instant).
    - If already settled → execute `refund`.
    - Some gateways may only support refund — handle accordingly.
- StartToCloseTimeout: 60s (refunds can be slow).
- RetryOptions: initialInterval=5s, maxAttempts=10, backoffCoefficient=2.0.
- **Must be idempotent** — gateway may have already processed the compensation.
- HeartbeatTimeout: 30s (for long-running refund operations).

### Activity Interface: NotificationActivities

```
notifyMerchant(webhookUrl: String, result: SplitPurchaseResult) -> void
```

- Sends webhook with split purchase result.
- RetryOptions: initialInterval=5s, maxAttempts=5.
- Should not block workflow completion — use async or fire-and-forget pattern.

## Saga Compensation Details

### Compensation Registration Pattern

For each successfully executed split part, register a compensation **immediately after success**, before moving to the next part:

```
pseudocode:
saga = new Saga(sagaOptions)
for each part in splitPlan.parts:
    result = gatewayActivities.executePurchase(part)
    saga.addCompensation(() -> gatewayActivities.compensatePurchase(part.gateway, result.txId, part.amount))
```

### Compensation Execution

- Temporal's Saga utility runs compensations in **reverse order** (LIFO).
- Each compensation is an Activity with its own retry policy.
- If a compensation itself fails after all retries, it is recorded as COMPENSATION_FAILED in the result — this requires manual intervention (alert to ops team).
- ParallelCompensation option: set to `false` — compensate one by one for predictability.

### Compensation Edge Cases

1. **Gateway timeout during purchase**: If we don't know whether the purchase succeeded, treat it as successful and register compensation. Better to refund a transaction that might not exist than to miss one.

2. **Void vs Refund decision**: Each gateway adapter should implement `isSettled(txId)` check. Void is preferred when available (no fees, instant).

3. **Partial refund**: Not applicable here — each split part is compensated in full. Partial refunds are a different business flow.

## Worker Configuration

### Task Queue

```
SPLIT_PURCHASE_TASK_QUEUE = "split-purchase"
```

### Worker Options

```
- MaxConcurrentActivityExecutionSize: 50
- MaxConcurrentWorkflowTaskExecutionSize: 20
```

### Workflow Options (when starting)

```
- WorkflowId: "split-purchase-{orderId}" (prevents duplicate processing)
- WorkflowIdReusePolicy: REJECT_DUPLICATE
- WorkflowExecutionTimeout: 10 minutes
- SearchAttributes:
    - merchantId: String
    - posId: String
    - orderId: String
    - totalAmount: Long
```

## Error Handling

| Scenario | Behavior |
|----------|----------|
| Split plan resolution fails | Return FAILED, no compensations needed |
| Split plan validation fails | Return FAILED, no compensations needed |
| Gateway purchase declined | Saga compensates previous parts, return FAILED |
| Gateway purchase timeout | Retry per activity retry policy, then fail and compensate |
| Compensation fails after all retries | Record COMPENSATION_FAILED, alert ops team |
| Worker crashes mid-workflow | Temporal resumes from last completed step |
| Duplicate workflow start (same orderId) | Rejected by WorkflowIdReusePolicy |

## Observability

### Search Attributes

Set on workflow start for queryability via Temporal UI:
- `merchantId`, `posId`, `orderId`, `totalAmount`, `splitPartsCount`, `status`

### Logging

- Log each split part attempt with gateway, amount, and result.
- Log each compensation attempt with action (void/refund) and result.
- Include workflowId and runId in all log entries for correlation.

### Alerts

- Alert on COMPENSATION_FAILED — requires manual review.
- Alert on workflow execution time > 5 minutes — something is stuck.
- Dashboard: split payment success rate by gateway, compensation rate, average execution time.

## Testing Strategy

1. **Unit tests**: Workflow logic with mocked activities (Temporal test framework).
2. **Integration tests**: Full flow with test gateway adapters (approve/decline/timeout scenarios).
3. **Saga compensation tests**:
    - Fail at part 1 (no compensations expected).
    - Fail at part 2 (compensate part 1).
    - Fail at part N (compensate parts 1..N-1).
    - Compensation failure (verify alerting).
4. **Idempotency tests**: Start same workflow twice with same orderId — second should be rejected.
5. **Crash recovery tests**: Kill worker mid-workflow, verify resumption.