# Temporal Schedule: Recurring Payments Implementation

## Context

Recurring payments (subscriptions) charge customers periodically using a stored card token. The system must handle soft declines (retry with durable sleep), hard declines (pause and notify), token expiry, and full lifecycle management (pause/resume/cancel) — all without cron jobs or external schedulers.


## Architecture Overview

```
Merchant API: create subscription
    │
    ▼
SubscriptionController (REST)
    │
    ▼
Temporal Client → creates Schedule
    │
    └── Schedule triggers every N days/months:
        │
        ▼
    RecurringPaymentWorkflow
        ├── Activity: validateSubscription()
        ├── Activity: verifyToken()
        ├── Activity: executePayment()
        │
        └── Handle result:
            ├── SUCCESS → webhook to merchant
            ├── SOFT_DECLINE → child workflow (retry 3x, 24h apart)
            └── HARD_DECLINE → pause schedule, webhook to merchant
```

## Data Models

### CreateSubscriptionRequest

```
- posId: String — merchant POS identifier
- amount: long — charge amount in minor units
- currency: String — "UAH"
- cardToken: String — tokenized card
- interval: int — billing interval value (e.g. 30)
- intervalUnit: enum { DAYS, WEEKS, MONTHS } — billing interval unit
- startDate: Instant — when first charge should occur
- endDate: Instant — optional, when subscription expires
- description: String — subscription description for receipts
- webhookUrl: String — URL for payment result notifications
- metadata: Map<String, String> — merchant custom data
```

### Subscription (persisted entity)

```
- id: String — unique subscription ID (e.g. "sub_abc123")
- posId: String
- merchantId: String
- amount: long
- currency: String
- cardToken: String
- interval: int
- intervalUnit: enum { DAYS, WEEKS, MONTHS }
- status: enum { ACTIVE, PAUSED, CANCELLED, TOKEN_EXPIRED }
- scheduleId: String — Temporal Schedule ID
- createdAt: Instant
- endDate: Instant — nullable
- lastChargeAt: Instant — nullable
- lastChargeStatus: enum { SUCCESS, FAILED }
- totalCharges: int
- totalAmount: long
- webhookUrl: String
- metadata: Map<String, String>
```

### RecurringPaymentRequest (workflow input)

```
- subscriptionId: String
- posId: String
- amount: long
- currency: String
- cardToken: String
- gateway: String
- webhookUrl: String
- scheduleId: String — to control the schedule from within the workflow
```

### PaymentAttemptResult

```
- subscriptionId: String
- transactionId: String — nullable
- status: enum { SUCCESS, SOFT_DECLINE, HARD_DECLINE, TOKEN_EXPIRED, SUBSCRIPTION_INACTIVE }
- declineCode: String — gateway decline code
- declineReason: String
- attemptNumber: int
- attemptedAt: Instant
```

## Schedule Configuration

### Schedule Creation

When a merchant creates a subscription via API:

1. Persist the `Subscription` entity in the database with status `ACTIVE`.
2. Create a Temporal Schedule:

```
Schedule configuration:
- scheduleId: "recurring-{posId}-{subscriptionId}"
- workflowType: RecurringPaymentWorkflow
- workflowInput: RecurringPaymentRequest (built from subscription data)
- interval: subscription.interval + subscription.intervalUnit
- startAt: subscription.startDate
- overlapPolicy: SKIP
- catchupWindow: 48 hours
- paused: false
- searchAttributes:
    - merchantId
    - posId
    - subscriptionId
- memo: subscription description
```

### Key Schedule Settings

| Setting | Value | Reason |
|---------|-------|--------|
| Overlap Policy | SKIP | Prevents double-charging if previous run is still retrying |
| Catchup Window | 48 hours | If schedule was paused during deploy, catch up missed runs within 48h |
| Jitter | 5 minutes | Spread load across gateway APIs, avoid thundering herd |

## Workflow Definition

### Interface: RecurringPaymentWorkflow

```
@WorkflowInterface
Method: executeRecurringPayment(RecurringPaymentRequest request) -> PaymentAttemptResult
```

### Workflow Implementation Logic

#### Step 1: Validate Subscription

Call `validateSubscription(request.subscriptionId)` activity.

- If status is `CANCELLED`:
    - Delete the Temporal Schedule via `scheduleHandle.delete()`.
    - Return result with status `SUBSCRIPTION_INACTIVE`.

- If status is `PAUSED`:
    - Return result with status `SUBSCRIPTION_INACTIVE` (schedule shouldn't have fired, but handle defensively).

- If `endDate` is set and `now() > endDate`:
    - Update subscription status to `CANCELLED`.
    - Delete the Temporal Schedule.
    - Notify merchant via webhook: subscription expired.
    - Return result with status `SUBSCRIPTION_INACTIVE`.

#### Step 2: Verify Token

Call `verifyToken(request.cardToken)` activity.

- If token is expired:
    - Pause the Temporal Schedule via `scheduleHandle.pause("Token expired")`.
    - Update subscription status to `TOKEN_EXPIRED`.
    - Notify merchant via webhook: token expired, new token required.
    - Return result with status `TOKEN_EXPIRED`.

#### Step 3: Execute Payment

Call `executePayment(request.gateway, request.cardToken, request.amount, request.currency)` activity.

Handle result based on decline type:

**SUCCESS:**
- Update subscription: `lastChargeAt = now()`, increment `totalCharges`, add to `totalAmount`.
- Notify merchant via webhook: `payment.success`.
- Return result with status `SUCCESS`.

**SOFT_DECLINE** (insufficient funds, temporary processing error):
- Start child workflow `SoftDeclineRetryWorkflow` with max 3 retries, 24h apart.
- Child workflow result determines final outcome.
- If all retries fail: notify merchant via webhook: `payment.failed` with decline details.
- Return child workflow result.

**HARD_DECLINE** (card stolen, blocked, restricted, expired by issuer):
- Pause the Temporal Schedule via `scheduleHandle.pause("Hard decline: " + declineCode)`.
- Update subscription status to `PAUSED`.
- Notify merchant via webhook: `payment.failed`, `action_required: new_token`.
- Return result with status `HARD_DECLINE`.

### Decline Classification

Map gateway decline codes to categories. Each gateway adapter should implement:

```
classifyDecline(declineCode: String) -> DeclineType { SOFT, HARD }
```

Common mappings:

| Decline Code | Type | Meaning |
|-------------|------|---------|
| insufficient_funds | SOFT | Retry later, customer may add funds |
| processing_error | SOFT | Temporary gateway issue |
| card_expired | HARD | Card no longer valid |
| card_stolen | HARD | Card reported stolen |
| card_restricted | HARD | Card blocked by issuer |
| do_not_honor | HARD | Issuer refuses, usually permanent |
| invalid_card | HARD | Card number invalid |

## Child Workflow: Soft Decline Retry

### Interface: SoftDeclineRetryWorkflow

```
@WorkflowInterface
Method: retryPayment(RetryRequest request) -> PaymentAttemptResult
```

### RetryRequest

```
- subscriptionId: String
- gateway: String
- cardToken: String
- amount: long
- currency: String
- maxRetries: int — default 3
- retryIntervalHours: int — default 24
- originalDeclineCode: String
```

### Implementation Logic

```
for attemptNumber = 1 to maxRetries:
    Workflow.sleep(Duration.ofHours(retryIntervalHours))  // DURABLE SLEEP
    result = executePayment(gateway, cardToken, amount, currency)

    if result == SUCCESS:
        return SUCCESS result

    if result == HARD_DECLINE:
        // Situation escalated — stop retrying, pause schedule
        pause schedule
        return HARD_DECLINE result

    // SOFT_DECLINE — continue to next retry

return FAILED result (all retries exhausted)
```

### Key Points About Durable Sleep

- `Workflow.sleep(Duration.ofHours(24))` is durable — survives worker restarts, deployments, infrastructure failures.
- The sleep timer is managed by Temporal Server, not the worker process.
- No database polling, no external scheduler, no cron — just a workflow sleeping for 24 hours.
- Temporal UI shows the workflow as "sleeping" with the exact wake-up time.

## Activity Definitions

### Activity Interface: SubscriptionActivities

```
validateSubscription(subscriptionId: String) -> SubscriptionStatus
```
- Reads subscription from database.
- Returns current status and metadata.
- RetryOptions: initialInterval=1s, maxAttempts=3.

```
updateSubscription(subscriptionId: String, update: SubscriptionUpdate) -> void
```
- Updates subscription fields in database (status, lastChargeAt, etc.).
- RetryOptions: initialInterval=1s, maxAttempts=3.

### Activity Interface: TokenActivities

```
verifyToken(cardToken: String) -> TokenStatus
```
- Checks token validity (expiration, revocation).
- May call gateway to verify token is still usable.
- StartToCloseTimeout: 10s.
- RetryOptions: initialInterval=1s, maxAttempts=3.

### Activity Interface: GatewayActivities

```
executePayment(gateway: String, cardToken: String, amount: long, currency: String) -> PaymentResult
```
- Executes a token-based purchase via the appropriate gateway.
- Returns transaction ID and status.
- StartToCloseTimeout: 30s.
- RetryOptions: initialInterval=2s, maxAttempts=2, nonRetryableErrors=[DeclinedError].
- **Important**: Do NOT retry on decline — the workflow handles retry logic with durable sleep. Only retry on transient errors (network timeout, 5xx from gateway).

### Activity Interface: NotificationActivities

```
notifyMerchant(webhookUrl: String, event: WebhookEvent) -> void
```
- Sends webhook to merchant with payment result.
- RetryOptions: initialInterval=5s, maxAttempts=5.
- Should not block workflow completion.

### Activity Interface: ScheduleActivities

```
pauseSchedule(scheduleId: String, reason: String) -> void
deleteSchedule(scheduleId: String) -> void
```
- Controls the Temporal Schedule from within a workflow.
- Uses Temporal Client internally to call schedule API.
- RetryOptions: initialInterval=1s, maxAttempts=3.

## Subscription Lifecycle API

### REST Endpoints

```
POST   /api/v1/subscriptions                    — create subscription
GET    /api/v1/subscriptions/{id}                — get subscription details
POST   /api/v1/subscriptions/{id}/pause          — pause billing
POST   /api/v1/subscriptions/{id}/resume         — resume billing
PUT    /api/v1/subscriptions/{id}/amount          — update charge amount
PUT    /api/v1/subscriptions/{id}/token           — update card token
DELETE /api/v1/subscriptions/{id}                 — cancel subscription
GET    /api/v1/pos/{posId}/subscriptions          — list merchant subscriptions
```

### Lifecycle Operations Mapping

| API Operation | DB Update | Temporal Action |
|---------------|-----------|-----------------|
| Create | Insert subscription (ACTIVE) | Create Schedule |
| Pause | Set status = PAUSED | `schedule.pause()` |
| Resume | Set status = ACTIVE | `schedule.unpause()` |
| Update amount | Update amount field | `schedule.update()` with new workflow input |
| Update token | Update cardToken, set status = ACTIVE | `schedule.unpause()` if was TOKEN_EXPIRED |
| Cancel | Set status = CANCELLED | `schedule.delete()` |

### Token Update Flow

When a merchant provides a new token (after expiry or hard decline):

1. Update `cardToken` in database.
2. Set subscription status to `ACTIVE`.
3. If schedule is paused, call `schedule.unpause()`.
4. Optionally trigger an immediate charge: `schedule.trigger()`.

## Worker Configuration

### Task Queue

```
RECURRING_PAYMENT_TASK_QUEUE = "recurring-payments"
```

### Worker Options

```
- MaxConcurrentActivityExecutionSize: 100
- MaxConcurrentWorkflowTaskExecutionSize: 50
```

### Workflow Options

```
- WorkflowId: derived from scheduleId (Temporal handles this)
- WorkflowExecutionTimeout: 4 days (to allow 3 retries × 24h + buffer)
- SearchAttributes:
    - merchantId: String
    - posId: String
    - subscriptionId: String
    - chargeAmount: Long
```

## Error Handling

| Scenario | Behavior |
|----------|----------|
| Subscription not found in DB | Return SUBSCRIPTION_INACTIVE, log warning |
| Token verification fails (network) | Activity retry, then fail workflow run |
| Gateway timeout during payment | Activity retry (2 attempts), then treat as SOFT_DECLINE |
| Worker crash during sleep | Temporal resumes — sleep timer is server-side |
| Schedule fires while retry workflow running | SKIP — overlap policy prevents double charge |
| Merchant cancels during retry workflow | Next retry iteration checks subscription status, exits |
| All 3 soft decline retries fail | Notify merchant, schedule continues for next cycle |

## Webhook Events

### Event Types

```
payment.success        — charge completed successfully
payment.failed         — charge failed (after all retries)
subscription.paused    — subscription paused (hard decline or token expiry)
subscription.expired   — subscription reached end date
subscription.cancelled — subscription cancelled by merchant
token.expired          — card token expired, new token required
```

### Webhook Payload Structure

```
{
  "event": "payment.success",
  "subscriptionId": "sub_abc123",
  "posId": "pos_xyz",
  "transactionId": "txn_789",
  "amount": 50000,
  "currency": "UAH",
  "attemptNumber": 1,
  "timestamp": "2026-02-16T10:00:00Z",
  "metadata": { ... }
}
```

## Observability

### Search Attributes

Query all subscriptions for a merchant:
```
merchantId = "merchant_xyz" AND posId = "pos_001"
```

Find failed recurring payments:
```
status = "FAILED" AND merchantId = "merchant_xyz"
```

### Metrics to Track

- Recurring payment success rate (by gateway, by merchant).
- Soft decline recovery rate (how often retries succeed).
- Average retry count before success.
- Token expiry rate.
- Schedule pause/resume frequency.

### Alerts

- Alert if soft decline recovery rate drops below 30% — may indicate systemic issue.
- Alert if a subscription has failed 3 consecutive cycles — merchant should be notified proactively.
- Alert on COMPENSATION_FAILED for any charge.

## Testing Strategy

1. **Unit tests**: Workflow logic with mocked activities.
    - Happy path: subscription active, token valid, payment approved.
    - Soft decline → retry succeeds on attempt 2.
    - Soft decline → all 3 retries fail.
    - Hard decline → schedule paused.
    - Token expired → schedule paused.
    - Subscription cancelled between retries.
    - Subscription end date reached.

2. **Integration tests**: Full flow with test gateway.
    - Create subscription → verify schedule created.
    - Wait for first charge → verify webhook sent.
    - Pause → verify no charges.
    - Resume → verify charging resumes.
    - Cancel → verify schedule deleted.

3. **Durable sleep tests**: Verify workflow resumes after worker restart during sleep.

4. **Overlap tests**: Trigger schedule while retry workflow is running — verify second run is skipped.

5. **Concurrency tests**: Multiple subscriptions charging simultaneously — verify no cross-contamination.
