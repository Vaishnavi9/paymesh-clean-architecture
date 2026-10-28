# PayMesh — provider simulators

Three **local, stateful, provider-inspired services** for learning payment adapters.
They do not call Stripe, Adyen, PayPal or a bank. All funds and credentials here are fake.

## Names, repeatable demo data and event contracts

Keep the short folder names, but introduce each app on screen as a
**Stripe-inspired / Adyen-inspired / PayPal-inspired local simulator**.
Their public payment lifecycles are the reference; these are independent local
implementations with documented simplifications, not official vendor services.

See [the demo guide](demo/README.md) for the precise compatibility boundaries and
fourteen deterministic payment examples. Run `python3 scripts/seed_demo.py` after
starting the services. It uses the actual HTTP APIs, checks persisted states and
ledger effects, and reuses fixed idempotency keys without resetting existing data.

The provider OpenAPI files describe commands and queries. The new
[AsyncAPI contract](asyncapi.yaml) describes outgoing HTTP webhooks, including
signatures, stable event IDs, retry rules and acknowledgement. Kafka and SQS
remain a later integration. Sixteen Java-mapped webhook examples cover all nine
implemented event types; `npm --prefix contract-tests test` validates them after
installing the project-local validation dependencies as described in the demo guide.

## Start locally and debug in IntelliJ

MongoDB runs in Docker. **All three Java services run on your Mac.**
Stripe and Adyen use Spring Boot 4.1.1; PayPal uses Quarkus 3.39.2.
All modules target Java 21. The verified local run uses IntelliJ's bundled JDK 25.
Choose JDK 21 or a supported newer JDK as the IntelliJ project SDK.

From this folder, with Docker Desktop running:

```sh
docker compose up -d mongo
mvn -DskipTests package
python3 scripts/local.py start
```

The launcher uses `JAVA_HOME` if set; otherwise it finds IntelliJ's bundled Java
on this Mac, then falls back to `java`. Maven also needs a JDK 21 or newer.
No Java application container is needed.

| Service | Framework | HTTP address | IntelliJ remote debug port | Contract and requests |
|---|---|---|---:|---|
| Stripe-inspired | Spring Boot | http://127.0.0.1:18101 | 5005 | `stripe/openapi.yaml`, `stripe/demo.http` |
| Adyen-inspired | Spring Boot | http://127.0.0.1:18102 | 5006 | `adyen/openapi.yaml`, `adyen/demo.http` |
| PayPal-inspired | Quarkus | http://127.0.0.1:18103 | 5007 | `paypal/openapi.yaml`, `paypal/demo.http` |

`GET /health` checks each service and MongoDB. Local API credentials are:

- Stripe: `Authorization: Bearer local-stripe-key`
- Adyen: `X-API-Key: local-adyen-key`
- PayPal: `Authorization: Bearer local-paypal-key`
- Inspection/fault routes under `/__sim/`: `X-Simulator-Admin-Key: local-admin-key`

These are fake local keys. They do not grant access to any real payment provider.
For a provider launched separately, override `PROVIDER_API_KEY` and
`PROVIDER_ADMIN_KEY` in its environment. Use separate run configurations when
assigning different keys to different services.

Open this folder's root `pom.xml` as a Maven project in IntelliJ. Let Maven import
all three modules. The saved **Stripe / Adyen / PayPal - Attach debugger**
configurations attach to the processes started above. Choose one and click Debug,
then place a breakpoint and send a request from that provider's `demo.http`.
HTTP and debugger listeners are bound to loopback.

**Do not start a second instance on the same port.** The alternative **Local Spring**
and **Local Quarkus** configurations launch from IntelliJ itself after stopping the
managed local processes. Rebuild before using the Quarkus JAR configuration after
source changes. Disconnecting an attached debugger leaves the service running.

```sh
python3 scripts/local.py status
python3 scripts/local.py stop
```

Logs and process ownership records are in `.runtime/`. Stop terminates only Java
processes whose saved PID, artifact path and debug port still match this project.
MongoDB stays running and retains data in the `paymesh-providers_provider-mongo`
volume. Do not delete that volume when demonstrating restart recovery.

## Modules and framework comparison

- `provider-core`: framework-independent simulation rules, MongoDB persistence,
  transaction boundaries, provider response mapping and webhook delivery.
- `spring-provider`: Spring MVC controllers and explicit `@Bean` wiring for
  Stripe and Adyen; scheduled work dispatched to a bounded executor.
- `paypal`: Quarkus REST resources, CDI `@Produces` wiring, validation, authentication
  filter and a scheduled webhook executor. Its API documentation is alongside its source.

The shared engine contains no Spring or Quarkus annotations. Each framework creates
and connects the same application objects. This lets the lesson compare Spring's
`@Bean` with CDI producers, MVC controllers with Jakarta REST resources, and both
schedulers without rewriting the financial invariants.

The Quarkus endpoints use `@Blocking` because they use the synchronous MongoDB
client. Blocking I/O must not run on Quarkus's event-loop threads. Our MongoDB
transactions use driver sessions explicitly; neither Spring nor Jakarta
`@Transactional` is being used to manage these MongoDB transactions.

References: [Quarkus REST](https://quarkus.io/guides/rest/),
[CDI](https://quarkus.io/guides/cdi),
[scheduler](https://quarkus.io/guides/scheduler-reference),
[Maven and debugging](https://quarkus.io/guides/maven-tooling).

## Executors and bounded concurrency

`ThreadPoolExecutor` implements `ExecutorService`, which extends `Executor`.
Here the abstractions have concrete jobs:

- Schedulers trigger polling; they do not perform slow webhook HTTP calls.
- Four named platform threads execute claimed background tasks. The queue is bounded,
  and a semaphore limits claimed in-flight jobs to four per process. Adyen processing
  and its webhook delivery share the Spring worker capacity.
- When capacity is full, jobs remain unclaimed in MongoDB. A bounded Java queue is
  not the durable queue; MongoDB job records are.
- Database lease tokens protect against overlapping workers. MongoDB transactions,
  idempotency records and unique ledger operations protect money effects.
- Webhook connect/read deadlines are bounded. Shutdown waits for workers, then
  interrupts them if necessary; unfinished jobs become claimable after their lease expires.
- Concurrency tests use `ExecutorService`, `Callable` and `Future` to send competing
  requests and assert **one capture**, rather than assuming thread safety.

A debugger pause can exceed the normal 30-second lease. Use thread-only breakpoints
and inspect the lease behaviour, or raise `provider.lease-ms` for a deliberate long
walkthrough. A thread pool alone does not prevent double charging.

## What is concrete

The `accounts` collection starts with these fake payment tokens, once per database:

| Token | Currency | Available balance | Rule |
|---|---|---:|---|
| `pm_funded` | EUR | 1000.00 | Usable while funds remain |
| `pm_low` | EUR | 1.00 | A 25.00 payment declines |
| `pm_blocked` | EUR | 1000.00 | Blocked account declines |
| `pm_usd` | USD | 1000.00 | Currency must match |

Authorisation moves funds from **available to reserved**. Capture removes the
reservation and credits the fake merchant account. A MongoDB transaction commits
the balances, payment state, ledger record, webhook outbox record and (for HTTP
operations) idempotency result together. The unique ledger operation prevents
repeating a reservation or capture.

`ledger` records show those simulated operations. This is a deliberately small
funds model, not a complete accounting ledger or banking settlement system.

There are no random success/decline decisions. Account state determines outcomes.
Only retry scheduling uses bounded jitter to spread attempts.

## Three contracts and the documentation behind them

### Stripe-inspired

`POST /v1/payment_intents` accepts form-encoded amount, currency, payment token,
order-reference metadata, confirmation and capture mode.

Create → requires confirmation → confirm → requires capture → capture → succeeded
(manual-capture example). Automatic capture is also supported.

A decline returns HTTP 402 and leaves the intent needing a payment method.
Replaying a successful mutation with the same key returns its original response;
GET retrieves current state. Different payload under the same operation/key is 409.

References: [PaymentIntent create](https://docs.stripe.com/api/payment_intents/create),
[lifecycle](https://docs.stripe.com/payments/paymentintents/lifecycle),
[API v1 idempotency](https://docs.stripe.com/api/idempotent_requests).

### Adyen-inspired

`POST /v72/payments` returns `Pending` and persists work.
The worker reserves funds, records `AUTHORISED` or `DECLINED`, and creates a
webhook outbox event. `POST /v72/payments/{id}/captures` accepts a full capture
with a separate capture reference; a worker completes it asynchronously.

This simulator uses manual capture. An authorisation does not move funds into
the merchant account. Real Adyen's `CAPTURE` event indicates capture submission;
a later capture failure is possible. Our simplified funds model does not simulate
the bank settlement stage or a later `CAPTURE_FAILED`.

References: [Checkout v72 payment API](https://docs.adyen.com/api-explorer/Checkout/72/post/payments),
[result codes](https://docs.adyen.com/online-payments/build-your-integration/payment-result-codes/),
[capture](https://docs.adyen.com/online-payments/capture),
[idempotency](https://docs.adyen.com/development-resources/api-idempotency/).

### PayPal-inspired

`POST /v2/checkout/orders` creates an order without moving money.
The local `/__sim/orders/{id}/approve` endpoint stands in for buyer interaction.
Approval reserves no funds. The capture endpoint then attempts the payment.

The public body uses decimal money strings, unlike Stripe/Adyen's integer minor
units. Supported currencies are EUR and USD, both with two decimal places.
Replay can return the latest order state, whereas Stripe-style replay is frozen.

References: [Orders v2](https://developer.paypal.com/api/orders/v2),
[approval/capture flow](https://developer.paypal.com/api/rest/integration/orders-api),
[idempotency](https://developer.paypal.com/api/rest/reference/idempotency/).

Contracts were selected from public documentation on 20 October 2026. Our OpenAPI
files describe **our supported subset**, not the vendors' full specifications.
No claim is made about which databases or internal implementations those vendors use.

## Failure experiments

Use the examples in each provider's `demo.http`.

1. **Missing credentials:** request is rejected before any payment is created.
2. **Insufficient funds:** business decline; no reservation or capture.
3. **Repeated/concurrent request:** one operation, one payment ID, one ledger effect.
4. **Same key, changed amount:** conflict; original payment unchanged.
5. **Lost result:** configure `loseResponse: true` before Stripe automatic capture.
   The simulator commits the operation and then returns an injected HTTP 504.
   Retry with the identical key; the successful saved result is returned.
   This models an ambiguous gateway response, not an actual TCP disconnect.
6. **Transient Adyen processing failure:** configure `transientFailures: 2`.
   The first two attempts make no financial change; a later attempt succeeds.
   Attempts and next-attempt time are persisted.
7. **Retry exhaustion:** configure more transient failures than the attempt budget.
   The payment enters `REVIEW_REQUIRED`, preserving uncertainty. A capture awaiting
   review retains its reserved funds. No manual resolution workflow is implemented yet.
8. **Worker overlap:** database leases with a changing token reject stale workers.
9. **Webhook receiver fails:** signed delivery retries with the same event ID.
   After the delivery budget, the job becomes `DEAD` for inspection.
10. **Restart:** payments, idempotency records, balances and jobs remain in MongoDB.
11. **Storage failure:** HTTP 503; no untracked fallback to in-memory payment state.

The webhook outbox is a concrete first connection to PayMesh's earlier outbox
lesson: business changes and an outgoing notification are committed atomically,
then delivery happens outside the transaction. Delivery can repeat; consumers
must deduplicate. Kafka and SQS are not connected in this provider milestone.

## Webhook delivery

No external webhook destination is enabled by default. Events remain inspectable
in MongoDB. Set `PROVIDER_WEBHOOK_URL` and `PROVIDER_WEBHOOK_SECRET` at startup to
send events to a receiver you control, using its localhost URL for this setup.
Destinations are operator-configured, never accepted
from payment requests; redirects are disabled.

The body resembles the selected provider's event format but remains a limited
subset. **Signing is deliberately a common local scheme, not native Stripe,
Adyen or PayPal verification**:

`X-Simulator-Signature = hex(HMAC-SHA256(secret, timestamp + "." + rawBody))`

Headers also include `X-Simulator-Timestamp` (epoch seconds) and
`X-Simulator-Event-Id`. A future receiver must verify the raw-body signature,
enforce timestamp tolerance, and deduplicate the event ID.
HTTP 2xx acknowledges delivery; network errors, 408, 429 and 5xx are retried.
Other non-2xx responses stop delivery. Retry-After and native vendor delivery
schedules are not implemented.

## Debugger route

1. Spring `web/AuthenticationFilter` or Quarkus `LocalAuthentication`: which credential authorises this local caller?
2. The matching `StripeController`, `AdyenController` or Quarkus `PayPalResource`: how does
   its external shape become an application call?
3. `PaymentService.mutate`: replay, request fingerprint and transaction boundary.
4. `PaymentService.authorise`: account rules and atomic reservation.
5. `PaymentService.captureFunds`: capture exactly once and create event evidence.
6. `ProviderStore.transaction`: commit or rollback across documents.
7. `ProviderWorkers.poll` / `PaymentService.processClaim`: persistent background work,
   bounded concurrency and stale-worker protection.
8. `WebhookDelivery.deliver`: external HTTP outside the money transaction; Quarkus `WebhookWorker` dispatches it through an executor.

Inspect `GET /__sim/payments/{id}`, `/ledger`, `/webhooks`, and `/__sim/accounts`
beside the HTTP response. Never equate buyer approval, authorisation and capture.

## Tests

Start MongoDB, then:

```sh
mvn test
mvn -Pintegration verify
```

Integration tests start two Spring HTTP applications and the packaged Quarkus service on random ports, use fresh
`provider_it_*` databases, and remove only those test databases afterward.
The verified suite has 23 tests (3 rule tests, 13 Spring integration tests and 7 Quarkus integration tests).
They verify HTTP behaviour, idempotency conflicts, concurrent duplicate capture,
competing funds, transaction rollback, persistent retries, lease fencing,
webhook signature/retry, restart recovery, and approval/authorisation/capture.
The three OpenAPI files have also passed OpenAPI validation.
The default test MongoDB address is the local Compose port. Override with
`-Dprovider.test.mongo=...` when running inside a container.

## Deliberate limits

- Local-only Docker ports, fake static API/admin keys, one merchant per provider.
  PayPal's production OAuth token issuance is not emulated.
- Separate logical MongoDB databases share a **single-member local replica set**.
  This enables transactions but does not provide high availability. MongoDB access
  control is not enabled in this isolated local lab.
- No real card data, 3-D Secure, refunds, partial capture, currency conversion,
  reservation expiry, reconciliation operator UI or settlement.
- Key retention is indefinite in the lab; commercial providers have different
  retention and error-caching rules. Validation/concurrency failures are not cached.
- Unsupported vendor fields and payment methods are outside the tested subset.
- No real provider SDK compatibility claim, production certification, AWS changes,
  Kafka/SQS connection or PayMesh adapters yet.
