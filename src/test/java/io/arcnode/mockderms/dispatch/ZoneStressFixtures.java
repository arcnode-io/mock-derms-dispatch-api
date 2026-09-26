package io.arcnode.mockderms.dispatch;

import io.arcnode.mockderms.Config;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Shared arrange-step fixtures for the two ZoneStressTracker test classes — imported explicitly by
 * each, never auto-discovered.
 */
final class ZoneStressFixtures {

  static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");
  // Reason: threshold 1800.0 — matches Config's own placeholder, kept local so these tests don't
  // depend on cfg.yml's exact value.
  static final double THRESHOLD_MW = 1800.0;
  static final double ELEVATED_MW = 1900.0;
  static final double NORMAL_MW = 1500.0;

  /** Past CACHE_TTL, so the next isZoneStressed() re-attempts the fetch. */
  static final Duration PAST_TTL = Duration.ofMinutes(6);

  private ZoneStressFixtures() {}

  static Config config() {
    return new Config(
        Config.LogLevel.INFO,
        8080,
        "localhost",
        false,
        "tcp://localhost:1883",
        "arcnode_mock_derms_dispatch_api",
        "site_001",
        "http://localhost:8080",
        "http://localhost:8081",
        13.8,
        50.0,
        4.0,
        "dlr_rtu_demo",
        "https://example.invalid/token",
        "https://example.invalid/archive",
        THRESHOLD_MW,
        25.0);
  }

  /** Advanceable fake — same pattern as EventOrchestratorTest's own MutableClock. */
  static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  /** Drives the tracker to the sustained-stressed state with three fresh elevated readings. */
  static void reachStressed(ZoneStressTracker tracker, MutableClock clock) {
    tracker.isZoneStressed();
    clock.advance(PAST_TTL);
    tracker.isZoneStressed();
    clock.advance(PAST_TTL);
    tracker.isZoneStressed();
  }
}
