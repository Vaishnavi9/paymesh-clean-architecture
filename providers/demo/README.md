# Repeatable PayMesh provider demo

Use the on-screen names **Stripe-inspired local simulator**, **Adyen-inspired local
simulator** and **PayPal-inspired local simulator**. The existing folder names are
convenient identifiers. These are independent educational implementations, not the
vendors' services, official SDK integrations, or a statement of endorsement.

## What resembles public provider behaviour

- Stripe: PaymentIntent creation, confirmation, authorisation and capture.
  [Official lifecycle](https://docs.stripe.com/payments/paymentintents/lifecycle).
- Adyen: payment submission, asynchronous outcomes and capture notifications.
  [Official result codes](https://docs.adyen.com/online-payments/build-your-integration/payment-result-codes/)
  and [capture](https://docs.adyen.com/online-payments/capture/).
- PayPal: order creation, buyer approval and capture as separate steps.
  [Official Orders flow](https://developer.paypal.com/api/rest/integration/orders-api/).

Saved state, atomic balance changes, idempotency records, bounded workers and a
durable webhook outbox make the examples meaningful. This models public
integration patterns; it does not reproduce or establish the vendors' internal
architecture, databases, retry schedules or full contracts.

Important differences to say aloud:

- All funds, accounts and credentials are synthetic.
- PayPal buyer approval uses a local admin endpoint. Real browser approval and
  OAuth are not implemented.
- All three use our common local HMAC signature scheme, not their native webhook
  verification protocols.
- Our PayPal capture-completed webhook carries an order-shaped resource, whereas
  the real event concerns a capture resource. `SIMULATOR.*` events are local.
- Local Stripe automatic capture also creates the internal authorisation
  notification before the capture notification; do not assume one-to-one parity
  with the vendor's event sequence.
- Adyen's local `SIMULATOR_REVIEW_REQUIRED` is a teaching extension.
  Local capture completes our small funds model; real capture and settlement have
  additional stages. There are no refunds, partial captures, 3DS or real settlement.

## Add the synthetic data

Run from the `providers` project with MongoDB and all three services running:

```sh
python3 scripts/seed_demo.py
```

The script calls the actual local HTTP endpoints and validates stored results. It
does not insert invented final states directly into MongoDB or reset existing data.
The default fake API keys match the services. For separately configured instances,
set `STRIPE_DEMO_API_KEY`, `ADYEN_DEMO_API_KEY`, `PAYPAL_DEMO_API_KEY` and
`DEMO_ADMIN_KEY` in the script's environment.

`scenarios.json` defines fourteen examples:

| Case | Evidence to show |
|---|---|
| `s-authorise` | Reserved funds, no capture yet |
| `s-capture` | One reservation and one capture |
| `s-low` | Insufficient funds; no money movement |
| `s-blocked` | Blocked account declines despite its balance |
| `s-currency` | Funding currency mismatch |
| `s-lost` | First call commits then returns 504; same-key replay recovers |
| `a-retry` | Two transient failures, authorisation on attempt three |
| `a-capture` | Capture accepted, then completed asynchronously |
| `a-decline` | Pending submission ends in a business decline |
| `a-review` | Five unsuccessful attempts end in review, not a fabricated success |
| `p-created` | Created order; no funds reserved |
| `p-approved` | Buyer approved; still no funds reserved |
| `p-capture` | Approved order captured once |
| `p-decline` | Approval does not guarantee sufficient funds at capture |

The catalog uses fixed `catalog-v1-...` references and idempotency keys. Rerunning
it reuses those payments and verifies the same effects. The lost-response fault
occurs on the first committed call only; a later replay correctly returns the saved
result instead of generating the fault again.

For one case:

```sh
python3 scripts/seed_demo.py --only s-lost
```

Payment IDs, ledger operation IDs and stored outbox jobs are saved in
`.runtime/demo-results.json` (or `demo-one-result.json` for `--only`). If you
manually advance a catalog payment, its original expected-state check may fail.
Use a fresh reference and idempotency key in the existing `demo.http` examples
for new interactive experiments. Do not reset shared balances to force a pass.
The live accounts have finite simulated balances; successful new payments consume
or reserve them.

The recorded catalog check ran all fourteen cases twice: payment IDs, ledger
operations and event IDs stayed the same, and the second run changed no balances.
The first `s-lost` run observed the injected HTTP 504 and recovered successfully.
Local verification evidence is in `.runtime/catalog-verification.json`.

## OpenAPI and AsyncAPI

- Each provider's `openapi.yaml` describes HTTP commands and queries.
- The root `asyncapi.yaml` describes the three services' outgoing HTTP webhook
  messages, headers, correlation, retry and acknowledgement behaviour.
- `webhook-examples.json` contains sixteen examples covering nine event types,
  exported from persisted events through the real Java mapper.

AsyncAPI is an event contract. It does not itself implement a receiver, start an
executor, or connect Kafka/SQS. No webhook destination is enabled by default.
Set `PROVIDER_WEBHOOK_URL` and `PROVIDER_WEBHOOK_SECRET` when starting a service
to deliver to a receiver you control. An HTTP 2xx acknowledges delivery.
Duplicate and out-of-order events remain possible.

Examples include local demonstration signatures so we can check signed bytes.
They are not reusable authenticated HTTP requests: their historical timestamps
should fail a receiver's normal freshness check.

Validate the contract and saved examples:

```sh
npm --prefix contract-tests ci --ignore-scripts
npm --prefix contract-tests test
```

To refresh the examples after changing Java mapping, rebuild the Java modules,
run the catalog, and use the helper with the core classpath:

```sh
mvn -pl provider-core dependency:build-classpath -Dmdep.outputFile=target/runtime-classpath.txt -DincludeScope=runtime
java --class-path "provider-core/target/classes:$(cat provider-core/target/runtime-classpath.txt)" scripts/ExportWebhookExamples.java .runtime/demo-results.json demo/webhook-examples.json
npm --prefix contract-tests test
```

The helper calls `WebhookDelivery.payload` and `WebhookDelivery.sign`; it does
not maintain a second hand-written mapping. The checks cover schemas, required
headers, event IDs, signatures and rejection of malformed messages. These checks
verify serialisation, not HTTP receiver delivery or compatibility with vendor SDKs.

## Short narration

“These are our local provider simulators. Their payment flows are inspired by the
public Stripe, Adyen and PayPal documentation. They store payment state and use
fake balances, so we can prove what happens when a request succeeds, declines or
has to be retried.

“OpenAPI tells us what to send to each service. AsyncAPI describes the event it
can send back later. We will follow the request, the saved payment, the ledger
and the outgoing event together.”
