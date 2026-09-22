# mock-derms-dispatch-api 🎛️📡

![](https://img.shields.io/gitlab/pipeline-status/arcnode-io/mock-derms-dispatch-api?branch=main&logo=gitlab)
![](https://gitlab.com/arcnode-io/mock-derms-dispatch-api/badges/main/coverage.svg)
![](https://img.shields.io/badge/21-gray?logo=openjdk)
![](https://img.shields.io/badge/4.1.1-gray?logo=springboot)
![](https://img.shields.io/badge/build-maven-C71A36?logo=apachemaven)

A mock utility DERMS (Distributed Energy Resource Management System) dispatch-decision service —
stands in for the real utility/aggregator side of the DER dispatch chain during development and
testing. Per the `## DER Event` sequence in `ems/readme.md`:

- Day-ahead forecast + headroom curve: not implemented — real-time monitoring only.
- Real-time: consumes `dlr_rtu`'s live rating (MQTT) + `dlr_tap_regulator_sim`'s live line
  loading (MQTT, `test/line_loading/A`) → trigger check. Real-time ERCOT North-zone load
  (NP3-562-CD) tightens the trigger margin when the zone's stressed — never an independent
  trigger, the local DLR trigger stays sole authority.
- On trigger: `der_dispatch` is a confirmed IEEE 2030.5/CSIP single-EndDevice-per-site concept,
  so there's no "which DER" registry — computes magnitude, `POST /der-events` to
  `ems-der-control-api`, the real utility-facing IEEE 2030.5 intake this mock exercises.
- Compliance return path: real IEEE 2030.5 `MirrorUsagePoint` intake at
  `POST /mirror-usage-points` — der-control-api reports its actual delivered power, this compares
  it against what was dispatched. Replaces an earlier version that subscribed directly to the
  broker topic `ems-hmi` also consumes for `der_dispatch` measurements, removed because a utility
  has no business holding broker access to a site's internal telemetry — a real utility only ever
  sees this over the 2030.5 HTTP surface, never raw pub/sub.
- Event-end: rating recovered (sustained, not single-sample — avoid rebound trip) → closes with
  `CANCELLED`, or `max_duration_h` hit → closes with `COMPLETED`, per IEEE 2030.5's own
  `EventStatus.currentStatus` distinction between an early cutoff and a natural expiry.

Stateless in-memory mock — no persistence. Real DERMS platforms are stateful; this one only needs
to be consistent for the duration of a test run.

Instance of `~/engineering-with-ai/java-spring-jpa`, with the JPA/Postgres layer removed (a mock
service has no need for it) and an MQTT client added (Eclipse Paho, same as `ems-der-control-api`)
for the broker-side consumption above.
