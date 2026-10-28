#!/usr/bin/env python3
"""Create repeatable synthetic examples through the LOCAL simulator HTTP APIs."""
import argparse
import json
import os
from pathlib import Path
import sys
import time
from urllib import error, parse, request

ROOT = Path(__file__).resolve().parents[1]
PORTS = {"stripe": 18101, "adyen": 18102, "paypal": 18103}
PREFIX = "catalog-v1"


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


HTTP = request.build_opener(request.ProxyHandler({}), NoRedirect())


def call(provider, method, path, body=None, key=None, admin=False, form=False, headers=None):
    # Destination is deliberately fixed to this lab; never point this seed script at a vendor.
    api_key = os.environ.get(f"{provider.upper()}_DEMO_API_KEY", f"local-{provider}-key")
    auth = {"X-API-Key": api_key} if provider == "adyen" else {"Authorization": f"Bearer {api_key}"}
    if admin:
        auth = {"X-Simulator-Admin-Key": os.environ.get("DEMO_ADMIN_KEY", "local-admin-key")}
    if key:
        auth["PayPal-Request-Id" if provider == "paypal" else "Idempotency-Key"] = key
    if headers:
        auth.update(headers)
    data = None
    if body is not None:
        auth["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        data = (parse.urlencode(body) if form else json.dumps(body)).encode()
    req = request.Request(f"http://127.0.0.1:{PORTS[provider]}{path}", data=data, headers=auth, method=method)
    try:
        with HTTP.open(req, timeout=10) as response:
            return response.status, json.load(response)
    except error.HTTPError as response:
        return response.code, json.load(response)


def expect(result, *statuses):
    status, body = result
    if status not in statuses:
        raise RuntimeError(f"Expected HTTP {statuses}, got {status}: {body}")
    return body


def inspect(provider, payment_id):
    return expect(call(provider, "GET", f"/__sim/payments/{payment_id}", admin=True), 200)


def wait_for_worker(provider, payment_id):
    deadline = time.monotonic() + 75
    while time.monotonic() < deadline:
        payment = inspect(provider, payment_id)
        if payment["state"] not in ("PROCESSING", "CAPTURE_PROCESSING"):
            return payment
        time.sleep(0.25)
    raise RuntimeError(f"{payment_id}: worker did not finish; check debugger pauses and service logs")


def seed(case):
    provider = case["provider"]
    reference = f'{PREFIX}-{case["id"]}'
    scenario = {"transientFailures": 0, "loseResponse": False, "delayMs": 250}
    scenario.update(case.get("scenario", {}))
    expect(call(provider, "PUT", f"/__sim/scenarios/{reference}", scenario, admin=True), 200)
    key = reference + "-create"
    observations = []
    amount = case["amountMinor"]
    currency = case["currency"]

    if provider == "stripe":
        body = {
            "amount": amount, "currency": currency.lower(), "metadata[order_reference]": reference,
            "payment_method": case["token"], "confirm": "true",
            "capture_method": "automatic" if case.get("automatic") else "manual",
        }
        result = call(provider, "POST", "/v1/payment_intents", body, key, form=True)
        if result[0] == 504 and scenario["loseResponse"]:
            observations.append("Observed injected HTTP 504 after commit; replayed the identical request/key")
            result = call(provider, "POST", "/v1/payment_intents", body, key, form=True)
        reply = expect(result, 200, 402)
        payment_id = reply["id"] if "id" in reply else reply["error"]["payment_intent"]["id"]
        if case.get("capture"):
            expect(call(provider, "POST", f"/v1/payment_intents/{payment_id}/capture",
                        key=reference + "-capture"), 200)

    elif provider == "adyen":
        body = {
            "merchantAccount": "merchant-demo", "reference": reference,
            "amount": {"value": amount, "currency": currency},
            "paymentMethod": {"type": "scheme", "storedPaymentMethodId": case["token"]},
        }
        reply = expect(call(provider, "POST", "/v72/payments", body, key), 200)
        payment_id = reply["pspReference"]
        payment = wait_for_worker(provider, payment_id)
        if case.get("capture"):
            # Replay a previous capture using the same key. A new capture needs authorisation.
            if payment["state"] not in ("AUTHORISED", "CAPTURED"):
                raise RuntimeError(f"Cannot capture {payment_id} in {payment['state']}")
            expect(call(provider, "POST", f"/v72/payments/{payment_id}/captures", {
                "merchantAccount": "merchant-demo", "reference": reference + "-capture",
                "amount": {"value": amount, "currency": currency},
            }, reference + "-capture"), 201)
            wait_for_worker(provider, payment_id)

    else:
        body = {"intent": "CAPTURE", "purchase_units": [{
            "custom_id": reference,
            "amount": {"currency_code": currency, "value": f"{amount // 100}.{amount % 100:02d}"},
        }]}
        reply = expect(call(provider, "POST", "/v2/checkout/orders", body, key,
                            headers={"X-Simulator-Payment-Method": case["token"]}), 201)
        payment_id = reply["id"]
        if case.get("approve"):
            expect(call(provider, "POST", f"/__sim/orders/{payment_id}/approve", {},
                        reference + "-approve", admin=True), 200)
        if case.get("capture"):
            expect(call(provider, "POST", f"/v2/checkout/orders/{payment_id}/capture", {},
                        reference + "-capture"), 201, 422)

    payment = inspect(provider, payment_id)
    if payment["state"] != case["expectedState"]:
        raise RuntimeError(f"{reference}: expected {case['expectedState']}, got {payment['state']}")
    if "expectedAttempts" in case and payment["attempts"] != case["expectedAttempts"]:
        raise RuntimeError(f"{reference}: unexpected worker attempt count {payment['attempts']}")
    ledger = expect(call(provider, "GET", f"/__sim/payments/{payment_id}/ledger", admin=True), 200)
    expected = {"AUTHORISED": ["RESERVE"], "CAPTURED": ["CAPTURE", "RESERVE"]}.get(payment["state"], [])
    if sorted(row["type"] for row in ledger) != expected:
        raise RuntimeError(f"{reference}: incorrect financial effects")
    webhooks = expect(call(provider, "GET", f"/__sim/payments/{payment_id}/webhooks", admin=True), 200)
    return {
        "case": case["id"], "provider": provider, "paymentId": payment_id,
        "state": payment["state"], "attempts": payment["attempts"],
        "ledgerOperations": sorted(row["operation"] for row in ledger),
        "webhookIds": sorted(row["_id"] for row in webhooks),
        "observations": observations, "webhookJobs": webhooks,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--only", help="Run one catalog case, for example s-lost")
    args = parser.parse_args()
    cases = json.loads((ROOT / "demo/scenarios.json").read_text())
    if args.only:
        cases = [case for case in cases if case["id"] == args.only]
        if not cases:
            parser.error("Unknown catalog case")
    for provider in sorted({case["provider"] for case in cases}):
        health = expect(call(provider, "GET", "/health"), 200)
        if health.get("simulation") is not True or health.get("provider") != provider:
            raise RuntimeError(f"{provider}: not the expected simulator")
    results = []
    for case in cases:
        result = seed(case)
        results.append(result)
        print(f'{case["id"]:12} {result["state"]:18} {result["paymentId"]}', flush=True)
    output = ROOT / ".runtime" / ("demo-one-result.json" if args.only else "demo-results.json")
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps(results, indent=2) + "\n")
    print(f"Verified {len(results)} scenarios. Evidence saved to {output}")
    print("No data was reset; successful examples reserve/capture synthetic funds.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, error.URLError, TimeoutError) as failure:
        print(f"Demo stopped: {failure}", file=sys.stderr)
        sys.exit(1)
