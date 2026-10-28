# Public snapshot verification

Checked on 28 October 2026 with Microsoft OpenJDK 25, compiling Java sources for
Java 21. These checks were run against this source snapshot before its first
publication. They are local evidence, not a claim of production readiness.

| Check | Result | Boundary established |
| --- | --- | --- |
| Root `mvn test` | 40 passed, 13 skipped, no failures | Rules, HTTP boundary, architecture checks and mocked SQS request behaviour |
| `PaymentAcceptancePostgresTest` with its opt-in flag | 12 passed, no skips or failures | SQL, atomic acceptance/outbox writes, replay/conflict and coordinated concurrency against disposable PostgreSQL |
| Providers `-Pintegration verify` | 3 rule tests and 20 integration tests passed | Spring simulators (13 integration tests) and Quarkus simulator (7) against a local MongoDB replica set |
| `providers/contract-tests` `npm test` | Passed | AsyncAPI schema, 16 Java-mapped payload examples across 9 event types, headers, example HMACs and negative schema checks |

The 3 provider rule tests also appear in the root suite. Across the Java runs,
72 distinct tests passed. The opt-in Kafka connectivity test was not run.

Commands:

```sh
mvn -B -ntp test
mvn -B -ntp -f cashflow-lab/pom.xml \
  -Dcashflow.acceptance-postgres-tests=true \
  -Dtest=PaymentAcceptancePostgresTest test
mvn -B -ntp -f providers/pom.xml -Pintegration \
  '-Dprovider.test.mongo=mongodb://127.0.0.1:27029/?replicaSet=rs0&directConnection=true&serverSelectionTimeoutMS=5000' verify
npm --prefix providers/contract-tests ci --ignore-scripts --no-audit --no-fund
npm --prefix providers/contract-tests test
```

The verification MongoDB instance used port 27029. The supplied provider Compose
file uses 27028; omit the `provider.test.mongo` override when using that file.
Provider integration tests create and clean dedicated `provider_it_*` databases.
The PostgreSQL test uses an isolated Testcontainers database.

## What remains unverified or unimplemented

- No live AWS publication or Kafka broker check was performed for this release.
- No real payment provider, money movement or external webhook receiver was used.
- Acceptance and provider simulators do not form an integrated payment pipeline.
- Authentication of merchants, operational load limits and deployment recovery
  require further implementation and evidence.
- Passing these tests does not establish the learner's independent understanding.

## Publication hygiene

The snapshot excludes local credentials, workstation metadata, build outputs,
logs and recordings. A local scan checked credential patterns, private-key
headers, personal workstation paths and accidental inclusion of the known
local database secret. Fake local keys in simulator examples are intentional.
This check is not a comprehensive security audit.
