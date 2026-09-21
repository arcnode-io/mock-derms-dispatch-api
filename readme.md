# mock-derms-dispatch-api 🎛️📡

![](https://img.shields.io/gitlab/pipeline-status/arcnode-io/mock-derms-dispatch-api?branch=main&logo=gitlab)
![](https://gitlab.com/arcnode-io/mock-derms-dispatch-api/badges/main/coverage.svg)
![](https://img.shields.io/badge/21-gray?logo=openjdk)
![](https://img.shields.io/badge/4.1.1-gray?logo=springboot)
![](https://img.shields.io/badge/build-maven-C71A36?logo=apachemaven)

A mock utility DERMS (Distributed Energy Resource Management System) dispatch-decision service —
stands in for the real utility/aggregator side of the DER dispatch chain during development and
testing. Per the `## DER Event` sequence in `ems/readme.md`:

- Day-ahead forecast intake (`gridstatus_api` load + weather) → headroom curve.
- Real-time: consumes `dlr_rtu`'s live rating over MQTT + `gridstatus_api`'s live loading →
  trigger check.
- On trigger: identifies enrolled DER(s), computes magnitude, `POST /der-events` to
  `ems-der-control-api` — the real utility-facing IEEE 2030.5 intake this mock exercises.
- Compliance return path: not implemented. A prior version subscribed directly to the broker
  topic `ems-hmi` also consumes for `der_dispatch` measurements — removed 2026-09-21 because a
  utility has no business holding broker access to a site's internal telemetry; a real utility
  only ever sees this over the 2030.5 HTTP surface, never raw pub/sub. Correct replacement (if
  built) is an HTTP poll against der-control-api, not a broker subscribe — deliberately left open
  rather than backfilled with the same mistake in a new shape.
- Event-end: rating recovered (sustained, not single-sample — avoid rebound trip) OR
  `max_duration_h` hit, whichever first.

Stateless in-memory mock — no persistence. Real DERMS platforms are stateful; this one only needs
to be consistent for the duration of a test run.

Instance of `~/engineering-with-ai/java-spring-jpa`, with the JPA/Postgres layer removed (a mock
service has no need for it) and an MQTT client added (Eclipse Paho, same as `ems-der-control-api`)
for the broker-side consumption above.
