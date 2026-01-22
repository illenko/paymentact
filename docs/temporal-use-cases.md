# Temporal Use Cases for Tranzzo

This document outlines potential Temporal workflow implementations for payment system operations.

---

## 1. Cross-border Payment Orchestration

Orchestrate international payments with currency conversion, compliance checks, and smart routing — without passing sensitive card data through the workflow.

### Key Principles
- **Reference-based processing**: Workflow operates on `paymentId`/`transactionRef`, not card numbers
- **Sensitive data stays in secure services**: PCI-compliant services handle actual card data
- **Workflow tracks state and coordinates**: Temporal manages the orchestration logic

### Workflow Schema

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CrossBorderPaymentWorkflow                               │
│                    Input: { paymentRef, amount, sourceCurrency,             │
│                             targetCurrency, destinationCountry }            │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────┐
                    │  1. Compliance Pre-Check        │
                    │  ─────────────────────────────  │
                    │  • Sanctions screening          │
                    │  • Country restrictions         │
                    │  • Amount limits check          │
                    └─────────────────────────────────┘
                                      │
                           ┌──────────┴──────────┐
                           ▼                     ▼
                      [PASSED]              [BLOCKED]
                           │                     │
                           │                     ▼
                           │              Return: COMPLIANCE_BLOCKED
                           ▼
                    ┌─────────────────────────────────┐
                    │  2. Get FX Rate Quote           │
                    │  ─────────────────────────────  │
                    │  • Lock rate for X minutes      │
                    │  • Store quoteId in workflow    │
                    └─────────────────────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────┐
                    │  3. Select Optimal Route        │
                    │  ─────────────────────────────  │
                    │  • Check corridor availability  │
                    │  • Compare fees across routes   │
                    │  • Select: SWIFT/SEPA/Local     │
                    └─────────────────────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────┐
                    │  4. Execute Payment             │
                    │  ─────────────────────────────  │
                    │  • Call selected corridor       │
                    │  • Pass only paymentRef         │
                    │  • Corridor fetches card data   │
                    │    from secure vault            │
                    └─────────────────────────────────┘
                                      │
                           ┌──────────┴──────────┐
                           ▼                     ▼
                      [SUCCESS]              [FAILED]
                           │                     │
                           ▼                     ▼
                    ┌──────────────┐    ┌──────────────────┐
                    │ 5. Confirm   │    │ 5. Retry with    │
                    │    FX Rate   │    │    fallback route│
                    └──────────────┘    └──────────────────┘
                           │                     │
                           ▼                     │
                    ┌─────────────────────────────────┐
                    │  6. Post-Processing             │
                    │  ─────────────────────────────  │
                    │  • Update ledger                │
                    │  • Notify merchant              │
                    │  • Audit log                    │
                    └─────────────────────────────────┘
```

### Code Structure

```kotlin
@WorkflowInterface
interface CrossBorderPaymentWorkflow {
    @WorkflowMethod
    fun processPayment(request: CrossBorderRequest): CrossBorderResult

    @QueryMethod
    fun getStatus(): PaymentStatus
}

// Request contains NO sensitive data - only references
data class CrossBorderRequest(
    val paymentRef: String,           // Reference to fetch card from vault
    val amount: BigDecimal,
    val sourceCurrency: String,
    val targetCurrency: String,
    val destinationCountry: String,
    val merchantId: String
)
```

---

## 2. Batch Settlement Processing

End-of-day settlement with banks and acquirers, processing thousands of transactions reliably.

### Workflow Schema

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      BatchSettlementWorkflow                                │
│                      Input: { settlementDate, acquirerId }                  │
│                      Trigger: Scheduled (e.g., 23:00 UTC daily)             │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────┐
                    │  1. Collect Transactions        │
                    │  ─────────────────────────────  │
                    │  • Query all unsettled txns     │
                    │  • Filter by acquirer           │
                    │  • Group by currency/merchant   │
                    └─────────────────────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────┐
                    │  2. Generate Settlement File    │
                    │  ─────────────────────────────  │
                    │  • Create batch file format     │
                    │  • Calculate totals             │
                    │  • Generate checksum            │
                    └─────────────────────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────┐
                    │  3. Submit to Acquirer          │
                    │  ─────────────────────────────  │
                    │  • SFTP upload / API call       │
                    │  • Retry on failure (3x)        │
                    │  • Store submission reference   │
                    └─────────────────────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────┐
                    │  4. Await Confirmation          │  ◄─── Timer: wait up to 4 hours
                    │  ─────────────────────────────  │       for async response
                    │  • Poll for response file       │
                    │  • Or receive webhook signal    │
                    └─────────────────────────────────┘
                                      │
                           ┌──────────┼──────────┐
                           ▼          ▼          ▼
                      [ACCEPTED] [PARTIAL]  [REJECTED]
                           │          │          │
                           │          ▼          │
                           │   ┌─────────────┐   │
                           │   │ Process     │   │
                           │   │ Rejections  │   │
                           │   │ (child wf)  │   │
                           │   └─────────────┘   │
                           │          │          │
                           ▼          ▼          ▼
                    ┌─────────────────────────────────┐
                    │  5. Update Transaction States   │
                    │  ─────────────────────────────  │
                    │  • Mark settled transactions    │
                    │  • Flag rejected for retry      │
                    │  • Update merchant balances     │
                    └─────────────────────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────┐
                    │  6. Generate Reports            │
                    │  ─────────────────────────────  │
                    │  • Settlement summary           │
                    │  • Exception report             │
                    │  • Notify finance team          │
                    └─────────────────────────────────┘


                    ┌─────────────────────────────────┐
                    │     Child: ProcessRejections    │
                    ├─────────────────────────────────┤
                    │  For each rejected transaction: │
                    │  • Analyze rejection reason     │
                    │  • Auto-fix if possible         │
                    │  • Queue for next batch or      │
                    │    escalate to manual review    │
                    └─────────────────────────────────┘
```

### Code Structure

```kotlin
@WorkflowInterface
interface BatchSettlementWorkflow {
    @WorkflowMethod
    fun runSettlement(request: SettlementRequest): SettlementResult

    @SignalMethod
    fun onAcquirerResponse(response: AcquirerResponse)  // Webhook can signal

    @QueryMethod
    fun getProgress(): SettlementProgress
}

data class SettlementProgress(
    val totalTransactions: Int,
    val processedTransactions: Int,
    val phase: String,  // COLLECTING, SUBMITTING, AWAITING_RESPONSE, FINALIZING
    val settledAmount: BigDecimal,
    val rejectedCount: Int
)
```

---

## 3. Merchant Onboarding (with Signals)

Multi-step merchant onboarding with human review steps, document uploads, and external verifications.

### Workflow Schema

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     MerchantOnboardingWorkflow                              │
│                     Input: { applicationId, merchantData }                  │
│                     Duration: Days to weeks (long-running)                  │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────┐
                    │  1. Initial Application         │
                    │  ─────────────────────────────  │
                    │  • Validate basic data          │
                    │  • Create merchant record       │
                    │  • Status: APPLICATION_RECEIVED │
                    └─────────────────────────────────┘
                                      │
                                      ▼
     ┌────────────────────────────────────────────────────────────────┐
     │  2. Document Collection (AWAITING SIGNALS)                     │
     │  ────────────────────────────────────────────────────────────  │
     │                                                                │
     │   Required Documents:                     Signals:             │
     │   □ Business registration      ◄──────── onDocumentUploaded() │
     │   □ ID verification            ◄──────── onDocumentUploaded() │
     │   □ Bank account proof         ◄──────── onDocumentUploaded() │
     │   □ Processing history         ◄──────── onDocumentUploaded() │
     │                                                                │
     │   Workflow.await { allDocumentsReceived() }                   │
     │   Timer: Send reminder after 3 days                           │
     │   Timer: Expire application after 30 days                     │
     └────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────┐
                    │  3. Automated Verification      │
                    │  ─────────────────────────────  │
                    │  • KYB check (external API)     │
                    │  • Sanctions screening          │
                    │  • Business registry lookup     │
                    └─────────────────────────────────┘
                                      │
                                      ▼
     ┌────────────────────────────────────────────────────────────────┐
     │  4. Underwriting Review (AWAITING SIGNAL)                      │
     │  ────────────────────────────────────────────────────────────  │
     │                                                                │
     │   Status: PENDING_REVIEW                                       │
     │   Assigned to: Underwriting Team                               │
     │                                                                │
     │   Await signal:                                                │
     │   ├── onReviewCompleted(APPROVED, riskTier, limits)           │
     │   ├── onReviewCompleted(REJECTED, reason)                     │
     │   └── onReviewCompleted(NEEDS_INFO, questions)                │
     │                     │                                          │
     │                     ▼                                          │
     │              [NEEDS_INFO] ──► Loop back to Document Collection │
     └────────────────────────────────────────────────────────────────┘
                                      │
                           ┌──────────┴──────────┐
                           ▼                     ▼
                      [APPROVED]            [REJECTED]
                           │                     │
                           │                     ▼
                           │              Send rejection email
                           │              Archive application
                           │              END
                           ▼
                    ┌─────────────────────────────────┐
                    │  5. Contract Generation         │
                    │  ─────────────────────────────  │
                    │  • Generate contract from       │
                    │    template + approved terms    │
                    │  • Send for e-signature         │
                    └─────────────────────────────────┘
                                      │
                                      ▼
     ┌────────────────────────────────────────────────────────────────┐
     │  6. Contract Signing (AWAITING SIGNAL)                         │
     │  ────────────────────────────────────────────────────────────  │
     │                                                                │
     │   Await signal: onContractSigned(signatureData)               │
     │   Timer: Reminder after 5 days                                │
     │   Timer: Expire after 14 days                                 │
     └────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────┐
                    │  7. Integration Setup           │
                    │  ─────────────────────────────  │
                    │  • Generate API credentials     │
                    │  • Create merchant portal user  │
                    │  • Configure webhook endpoints  │
                    │  • Set up test environment      │
                    └─────────────────────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────┐
                    │  8. Go-Live Checklist           │
                    │  ─────────────────────────────  │
                    │  • Verify test transactions     │
                    │  • Enable production access     │
                    │  • Send welcome package         │
                    │  • Status: ACTIVE               │
                    └─────────────────────────────────┘


═══════════════════════════════════════════════════════════════════════════════
                              SIGNAL HANDLERS
═══════════════════════════════════════════════════════════════════════════════

  @SignalMethod
  fun onDocumentUploaded(doc: DocumentUpload)     // From merchant portal/API

  @SignalMethod
  fun onReviewCompleted(decision: ReviewDecision) // From internal admin tool

  @SignalMethod
  fun onContractSigned(signature: SignatureData)  // From e-signature service

  @SignalMethod
  fun onMerchantCancelled()                       // Merchant withdraws application

═══════════════════════════════════════════════════════════════════════════════
                              QUERY METHODS
═══════════════════════════════════════════════════════════════════════════════

  @QueryMethod
  fun getOnboardingStatus(): OnboardingStatus     // Current phase + details

  @QueryMethod
  fun getPendingRequirements(): List<String>      // What's still needed
```

### Code Structure

```kotlin
@WorkflowInterface
interface MerchantOnboardingWorkflow {
    @WorkflowMethod
    fun onboardMerchant(application: MerchantApplication): OnboardingResult

    // Signals - external events that advance the workflow
    @SignalMethod
    fun onDocumentUploaded(document: DocumentUpload)

    @SignalMethod
    fun onReviewCompleted(decision: ReviewDecision)

    @SignalMethod
    fun onContractSigned(signature: SignatureData)

    @SignalMethod
    fun onMerchantCancelled()

    // Queries - check current state without affecting workflow
    @QueryMethod
    fun getStatus(): OnboardingStatus

    @QueryMethod
    fun getPendingRequirements(): List<Requirement>
}

data class OnboardingStatus(
    val phase: OnboardingPhase,
    val documentsReceived: List<String>,
    val documentsPending: List<String>,
    val reviewStatus: ReviewStatus?,
    val assignedReviewer: String?,
    val daysInCurrentPhase: Int
)

enum class OnboardingPhase {
    APPLICATION_RECEIVED,
    COLLECTING_DOCUMENTS,
    AUTOMATED_VERIFICATION,
    PENDING_REVIEW,
    CONTRACT_SENT,
    AWAITING_SIGNATURE,
    INTEGRATION_SETUP,
    ACTIVE,
    REJECTED,
    CANCELLED,
    EXPIRED
}
```

---

## 4. Gateway Failover

Automatic routing when primary payment gateway is unavailable, with health tracking and smart routing.

### Workflow Schema

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    PaymentWithFailoverWorkflow                              │
│                    Input: { paymentRef, amount, preferredGateway }          │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────┐
                    │  1. Check Gateway Health        │
                    │  ─────────────────────────────  │
                    │  • Query health status cache    │
                    │  • Get priority-ordered list    │
                    │  • Consider: success rate,      │
                    │    latency, cost, features      │
                    └─────────────────────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────┐
                    │  Gateway Priority Queue         │
                    │  ═════════════════════════════  │
                    │  1. Preferred (if healthy)      │
                    │  2. Best alternative            │
                    │  3. Fallback                    │
                    └─────────────────────────────────┘
                                      │
                                      ▼
        ┌─────────────────────────────────────────────────────────────┐
        │                    RETRY LOOP (max 3 gateways)              │
        │  ┌───────────────────────────────────────────────────────┐  │
        │  │                                                       │  │
        │  │    ┌─────────────────────────────────┐                │  │
        │  │    │  2. Attempt Payment             │                │  │
        │  │    │  ─────────────────────────────  │                │  │
        │  │    │  • Call current gateway         │                │  │
        │  │    │  • Timeout: 30 seconds          │                │  │
        │  │    └─────────────────────────────────┘                │  │
        │  │                      │                                │  │
        │  │           ┌─────────┴─────────┬──────────┐            │  │
        │  │           ▼                   ▼          ▼            │  │
        │  │      [SUCCESS]           [DECLINED]  [ERROR/TIMEOUT]  │  │
        │  │           │                   │          │            │  │
        │  │           │                   │          ▼            │  │
        │  │           │                   │   ┌─────────────┐     │  │
        │  │           │                   │   │ Report      │     │  │
        │  │           │                   │   │ Gateway     │     │  │
        │  │           │                   │   │ Health Issue│     │  │
        │  │           │                   │   └─────────────┘     │  │
        │  │           │                   │          │            │  │
        │  │           │                   │          ▼            │  │
        │  │           │                   │   Try next gateway    │  │
        │  │           │                   │   in priority queue   │  │
        │  │           │                   │          │            │  │
        │  │           ▼                   ▼          └────────────┤  │
        │  │      EXIT LOOP           EXIT LOOP                    │  │
        │  │                                                       │  │
        │  └───────────────────────────────────────────────────────┘  │
        └─────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────┐
                    │  3. Process Result              │
                    │  ─────────────────────────────  │
                    │  • Record which gateway worked  │
                    │  • Update success metrics       │
                    │  • Return result to caller      │
                    └─────────────────────────────────┘


═══════════════════════════════════════════════════════════════════════════════
                         GATEWAY HEALTH SERVICE (Separate)
═══════════════════════════════════════════════════════════════════════════════

    ┌─────────────────────────────────────────────────────────────────┐
    │                  GatewayHealthWorkflow                          │
    │                  (Singleton, always running)                    │
    ├─────────────────────────────────────────────────────────────────┤
    │                                                                 │
    │   Every 30 seconds:                                            │
    │   ┌─────────────────────────────────────────────────────────┐  │
    │   │  For each gateway:                                      │  │
    │   │  • Send health check ping                               │  │
    │   │  • Measure response time                                │  │
    │   │  • Calculate rolling success rate (last 100 txns)       │  │
    │   │  • Update health status in cache                        │  │
    │   └─────────────────────────────────────────────────────────┘  │
    │                                                                 │
    │   Health Status:                                               │
    │   ┌─────────┬────────────┬─────────┬────────────────────────┐  │
    │   │ Gateway │ Status     │ Latency │ Success Rate (1h)      │  │
    │   ├─────────┼────────────┼─────────┼────────────────────────┤  │
    │   │ GW_A    │ HEALTHY    │ 120ms   │ 99.2%                  │  │
    │   │ GW_B    │ DEGRADED   │ 890ms   │ 94.1%                  │  │
    │   │ GW_C    │ UNHEALTHY  │ timeout │ 45.0%                  │  │
    │   └─────────┴────────────┴─────────┴────────────────────────┘  │
    │                                                                 │
    │   Signals received from payment workflows:                     │
    │   • onGatewaySuccess(gateway, latency)                         │
    │   • onGatewayFailure(gateway, errorType)                       │
    │                                                                 │
    └─────────────────────────────────────────────────────────────────┘
```

### Code Structure

```kotlin
@WorkflowInterface
interface PaymentWithFailoverWorkflow {
    @WorkflowMethod
    fun processPayment(request: PaymentRequest): PaymentResult

    @QueryMethod
    fun getAttempts(): List<GatewayAttempt>
}

data class PaymentResult(
    val success: Boolean,
    val gatewayUsed: String,
    val transactionId: String?,
    val attempts: List<GatewayAttempt>,
    val declineReason: String?
)

data class GatewayAttempt(
    val gateway: String,
    val status: AttemptStatus,
    val latencyMs: Long,
    val errorCode: String?
)

enum class AttemptStatus {
    SUCCESS,
    DECLINED,          // Card declined - don't retry with other gateway
    GATEWAY_ERROR,     // Gateway issue - try next
    TIMEOUT            // Gateway timeout - try next
}
```

---

## 5. Webhook Delivery

Guaranteed webhook delivery to merchants with exponential backoff, dead-letter handling, and observability.

### Workflow Schema

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      WebhookDeliveryWorkflow                                │
│                      Input: { merchantId, event, payload, webhookUrl }      │
│                      Started: On every event that requires notification     │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────┐
                    │  1. Prepare Webhook             │
                    │  ─────────────────────────────  │
                    │  • Generate signature (HMAC)    │
                    │  • Add idempotency key          │
                    │  • Set timestamp                │
                    └─────────────────────────────────┘
                                      │
                                      ▼
        ┌─────────────────────────────────────────────────────────────┐
        │                    DELIVERY ATTEMPTS                        │
        │  ═══════════════════════════════════════════════════════════│
        │                                                             │
        │   Retry Schedule (Exponential Backoff):                     │
        │   ┌──────────┬─────────────┬───────────────────────────┐   │
        │   │ Attempt  │ Delay       │ Total time from start     │   │
        │   ├──────────┼─────────────┼───────────────────────────┤   │
        │   │ 1        │ Immediate   │ 0                         │   │
        │   │ 2        │ 1 minute    │ 1 min                     │   │
        │   │ 3        │ 5 minutes   │ 6 min                     │   │
        │   │ 4        │ 30 minutes  │ 36 min                    │   │
        │   │ 5        │ 2 hours     │ 2h 36m                    │   │
        │   │ 6        │ 8 hours     │ 10h 36m                   │   │
        │   │ 7        │ 24 hours    │ 34h 36m                   │   │
        │   └──────────┴─────────────┴───────────────────────────┘   │
        │                                                             │
        │   ┌─────────────────────────────────────────────────────┐   │
        │   │  Attempt Delivery                                   │   │
        │   │  ─────────────────────────────────────────────────  │   │
        │   │  POST {webhookUrl}                                  │   │
        │   │  Headers:                                           │   │
        │   │    X-Webhook-Signature: {hmac}                      │   │
        │   │    X-Webhook-Timestamp: {ts}                        │   │
        │   │    X-Idempotency-Key: {key}                         │   │
        │   │    X-Attempt-Number: {n}                            │   │
        │   │  Body: {event payload}                              │   │
        │   │  Timeout: 30 seconds                                │   │
        │   └─────────────────────────────────────────────────────┘   │
        │                        │                                    │
        │             ┌──────────┴──────────┬──────────┐              │
        │             ▼                     ▼          ▼              │
        │        [2xx OK]              [4xx Error] [5xx/Timeout]      │
        │             │                     │          │              │
        │             ▼                     ▼          ▼              │
        │        SUCCESS              Check type   Wait & Retry      │
        │        (exit loop)               │          │              │
        │                                  ▼          │              │
        │                    ┌─────────────────────┐  │              │
        │                    │ 400/401/403/404:    │  │              │
        │                    │ Configuration error │  │              │
        │                    │ → Move to DLQ       │  │              │
        │                    │                     │  │              │
        │                    │ 429 Rate Limited:   │  │              │
        │                    │ → Respect Retry-    │  │              │
        │                    │   After header      │──┘              │
        │                    └─────────────────────┘                  │
        │                                                             │
        └─────────────────────────────────────────────────────────────┘
                                      │
                    ┌─────────────────┴─────────────────┐
                    ▼                                   ▼
            [ALL RETRIES EXHAUSTED]               [DELIVERED]
                    │                                   │
                    ▼                                   ▼
        ┌─────────────────────────┐      ┌─────────────────────────┐
        │  Move to Dead Letter    │      │  Record Success         │
        │  ─────────────────────  │      │  ─────────────────────  │
        │  • Store in DLQ table   │      │  • Log delivery time    │
        │  • Alert operations     │      │  • Update metrics       │
        │  • Notify merchant      │      │  • Store response       │
        │    (email/dashboard)    │      │                         │
        └─────────────────────────┘      └─────────────────────────┘


═══════════════════════════════════════════════════════════════════════════════
                         DEAD LETTER QUEUE HANDLING
═══════════════════════════════════════════════════════════════════════════════

    ┌─────────────────────────────────────────────────────────────────┐
    │                  Merchant Dashboard                             │
    ├─────────────────────────────────────────────────────────────────┤
    │                                                                 │
    │   Failed Webhooks (3)                              [Retry All]  │
    │   ┌─────────────────────────────────────────────────────────┐  │
    │   │ Event: payment.completed                                │  │
    │   │ Payload: {"paymentId": "pay_123", ...}                  │  │
    │   │ Failed: 2024-01-15 14:30 (7 attempts)                   │  │
    │   │ Error: Connection refused                               │  │
    │   │ [View Details] [Retry Now] [Dismiss]                    │  │
    │   └─────────────────────────────────────────────────────────┘  │
    │                                                                 │
    │   Manual retry triggers signal:                                │
    │   workflow.signal("retryDelivery")                             │
    │                                                                 │
    └─────────────────────────────────────────────────────────────────┘


═══════════════════════════════════════════════════════════════════════════════
                              OBSERVABILITY
═══════════════════════════════════════════════════════════════════════════════

    Metrics (per merchant):
    • webhook_delivery_success_total
    • webhook_delivery_failure_total
    • webhook_delivery_latency_seconds
    • webhook_retry_count
    • webhook_dlq_size

    Alerts:
    • DLQ size > 100 for merchant
    • Delivery success rate < 95% (1h window)
    • Average latency > 10s
```

### Code Structure

```kotlin
@WorkflowInterface
interface WebhookDeliveryWorkflow {
    @WorkflowMethod
    fun deliverWebhook(request: WebhookRequest): WebhookResult

    @SignalMethod
    fun retryDelivery()  // Manual retry from dashboard

    @QueryMethod
    fun getDeliveryStatus(): DeliveryStatus
}

data class WebhookRequest(
    val merchantId: String,
    val webhookUrl: String,
    val secretKey: String,           // For HMAC signature
    val eventType: String,
    val payload: String,             // JSON payload
    val idempotencyKey: String
)

data class WebhookResult(
    val delivered: Boolean,
    val attempts: Int,
    val finalStatusCode: Int?,
    val deliveredAt: Instant?,
    val movedToDlq: Boolean,
    val dlqReason: String?
)

data class DeliveryStatus(
    val currentAttempt: Int,
    val maxAttempts: Int,
    val lastAttemptAt: Instant?,
    val lastError: String?,
    val nextRetryAt: Instant?,
    val state: DeliveryState
)

enum class DeliveryState {
    PENDING,
    ATTEMPTING,
    WAITING_RETRY,
    DELIVERED,
    FAILED_PERMANENT,
    IN_DLQ
}
```

---

## Summary

| Use Case | Key Temporal Features Used |
|----------|---------------------------|
| Cross-border Payment | Activities, Child Workflows, Retry Policies |
| Batch Settlement | Scheduled Workflows, Signals, Child Workflows, Timers |
| Merchant Onboarding | **Signals** (primary), Timers, Long-running workflows, Queries |
| Gateway Failover | Retry Logic, Activity Timeouts, Saga Pattern |
| Webhook Delivery | Exponential Backoff, Signals, Timers, Queries |

All examples avoid storing sensitive data (card numbers, credentials) in workflow state — only references and non-sensitive metadata are persisted.
