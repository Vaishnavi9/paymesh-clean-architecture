import fs from 'node:fs';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { Parser } from '@asyncapi/parser';
import Ajv from 'ajv';
import addFormats from 'ajv-formats';
import YAML from 'yaml';

const root = new URL('../', import.meta.url);
const source = fs.readFileSync(new URL('asyncapi.yaml', root), 'utf8');
const { document, diagnostics } = await new Parser().parse(source);
const errors = diagnostics.filter(diagnostic => diagnostic.severity === 0);
if (!document || errors.length) {
  throw new Error(`Invalid AsyncAPI: ${JSON.stringify(diagnostics, null, 2)}`);
}
const spec = YAML.parse(source);
const ajv = new Ajv({ allErrors: true, strict: false });
addFormats(ajv);
const compile = ref => ajv.compile({ $ref: ref, components: { schemas: spec.components.schemas } });
const headerValidator = compile('#/components/schemas/DeliveryHeaders');
const validators = Object.fromEntries([
  ['stripe', 'StripeEventBody'], ['adyen', 'AdyenEventBody'], ['paypal', 'PayPalEventBody'],
].map(([provider, schema]) => [provider, compile(`#/components/schemas/${schema}`)]));
const input = process.argv[2] ?? fileURLToPath(new URL('demo/webhook-examples.json', root));
const examples = JSON.parse(fs.readFileSync(input, 'utf8')).examples;
const seen = new Set();
for (const example of examples) {
  const validate = validators[example.provider];
  if (!validate || !validate(example.payload) || !headerValidator(example.headers)) {
    throw new Error(`${example.scenario}: ${JSON.stringify(validate?.errors ?? headerValidator.errors)}`);
  }
  if (JSON.stringify(JSON.parse(example.rawBody)) !== JSON.stringify(example.payload)) {
    throw new Error(`${example.scenario}: raw body does not describe the payload`);
  }
  const expectedSignature = crypto.createHmac('sha256', 'local-webhook-secret')
    .update(`${example.headers['X-Simulator-Timestamp']}.${example.rawBody}`).digest('hex');
  if (expectedSignature !== example.headers['X-Simulator-Signature']) {
    throw new Error(`${example.scenario}: invalid example signature`);
  }
  const bodyEventId = example.payload.id;
  if (bodyEventId && bodyEventId !== example.headers['X-Simulator-Event-Id']) {
    throw new Error(`${example.scenario}: inconsistent event ID`);
  }
  const eventName = example.payload.type ?? example.payload.event_type
    ?? example.payload.notificationItems[0].NotificationRequestItem.eventCode;
  seen.add(`${example.provider}:${eventName}`);
  // Negative checks: the schema must reject absent event type and absent signature.
  const brokenBody = structuredClone(example.payload);
  delete brokenBody.type;
  delete brokenBody.event_type;
  delete brokenBody.notificationItems;
  if (validate(brokenBody)) throw new Error('Schema accepted a missing event discriminator');
  const brokenHeaders = { ...example.headers };
  delete brokenHeaders['X-Simulator-Signature'];
  if (headerValidator(brokenHeaders)) throw new Error('Schema accepted an unsigned event');
}
const expectedTypes = [
  'stripe:payment_intent.amount_capturable_updated', 'stripe:payment_intent.succeeded',
  'stripe:payment_intent.payment_failed', 'adyen:AUTHORISATION', 'adyen:CAPTURE',
  'adyen:SIMULATOR_REVIEW_REQUIRED', 'paypal:SIMULATOR.AUTHORISED',
  'paypal:SIMULATOR.DECLINED', 'paypal:PAYMENT.CAPTURE.COMPLETED',
];
for (const type of expectedTypes) if (!seen.has(type)) throw new Error(`Missing example for ${type}`);
console.log(`AsyncAPI 3.0 validated; ${examples.length} Java-mapped payloads cover all ${seen.size} event types.`);
console.log('Headers, example HMACs, event IDs and negative schema checks passed.');
console.log('This checks contracts and serialisation; it does not claim receiver delivery or vendor compatibility.');
