package io.arcnode.mockderms.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.arcnode.mockderms.Config;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit — turns ErcotZoneLoadClient's raw MW reading into a debounced "zone is stressed" signal:
 * cached to IHLF's own real refresh cadence, and requires N consecutive elevated *fresh* readings
 * before entering the stressed state (same shape as ems-der-control-api's own
 * DeliveryShortfallMonitor — slow to enter, clears immediately on the first reading back below
 * threshold). Mocked ErcotZoneLoadClient, fake Clock, AAA.
 */
@ExtendWith(MockitoExtension.class)
class ZoneStressTrackerTest {

  private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");
  // Reason: threshold 1800.0 — matches Config's own placeholder, kept local so this test doesn't
  // depend on cfg.yml's exact value.
  private static final double THRESHOLD_MW = 1800.0;
  private static final double ELEVATED_MW = 1900.0;
  private static final double NORMAL_MW = 1500.0;

  private final Config config =
      new Config(
          Config.LogLevel.INFO,
          8080,
          "localhost",
          false,
          "tcp://localhost:1883",
          "arcnode_mock_derms_dispatch_api",
          "site_001",
          "http://localhost:8080",
          13.8,
          50.0,
          4.0,
          "dlr_rtu_demo",
          "https://example.invalid/token",
          "https://example.invalid/archive",
          THRESHOLD_MW,
          25.0);

  @Mock private ErcotZoneLoadClient zoneLoadClient;

  /** Advanceable fake — same pattern as EventOrchestratorTest's own MutableClock. */
  private static final class MutableClock extends Clock {
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

  @Test
  void notStressedBeforeAnyElevatedReading() {
    // Arrange
    given(zoneLoadClient.currentNorthZoneLoadMw()).willReturn(NORMAL_MW);
    ZoneStressTracker tracker =
        new ZoneStressTracker(zoneLoadClient, config, new MutableClock(NOW));

    // Act / Assert
    assertThat(tracker.isZoneStressed()).isFalse();
  }

  @Test
  void notStressedAtExactlyTheThresholdValue() {
    // Arrange: strictly greater-than, same boundary convention as TriggerEvaluator's own margin
    // comparison
    given(zoneLoadClient.currentNorthZoneLoadMw()).willReturn(THRESHOLD_MW);
    ZoneStressTracker tracker =
        new ZoneStressTracker(zoneLoadClient, config, new MutableClock(NOW));

    // Act / Assert
    assertThat(tracker.isZoneStressed()).isFalse();
  }

  @Test
  void staysNotStressedBeforeThreeConsecutiveElevatedReadings() {
    // Arrange
    given(zoneLoadClient.currentNorthZoneLoadMw()).willReturn(ELEVATED_MW);
    MutableClock clock = new MutableClock(NOW);
    ZoneStressTracker tracker = new ZoneStressTracker(zoneLoadClient, config, clock);

    // Act: two fresh elevated readings — not sustained yet
    tracker.isZoneStressed();
    clock.advance(Duration.ofMinutes(6));
    boolean afterTwo = tracker.isZoneStressed();

    // Assert
    assertThat(afterTwo).isFalse();
  }

  @Test
  void becomesStressedAfterThreeConsecutiveElevatedFreshReadings() {
    // Arrange
    given(zoneLoadClient.currentNorthZoneLoadMw()).willReturn(ELEVATED_MW);
    MutableClock clock = new MutableClock(NOW);
    ZoneStressTracker tracker = new ZoneStressTracker(zoneLoadClient, config, clock);

    // Act
    tracker.isZoneStressed(); // reading 1
    clock.advance(Duration.ofMinutes(6));
    tracker.isZoneStressed(); // reading 2
    clock.advance(Duration.ofMinutes(6));
    boolean afterThree = tracker.isZoneStressed(); // reading 3

    // Assert
    assertThat(afterThree).isTrue();
  }

  @Test
  void clearsImmediatelyOnASingleFreshReadingBackBelowThreshold() {
    // Arrange: reach stressed, then one normal fresh reading
    given(zoneLoadClient.currentNorthZoneLoadMw())
        .willReturn(ELEVATED_MW, ELEVATED_MW, ELEVATED_MW, NORMAL_MW);
    MutableClock clock = new MutableClock(NOW);
    ZoneStressTracker tracker = new ZoneStressTracker(zoneLoadClient, config, clock);
    tracker.isZoneStressed();
    clock.advance(Duration.ofMinutes(6));
    tracker.isZoneStressed();
    clock.advance(Duration.ofMinutes(6));
    assertThat(tracker.isZoneStressed()).isTrue(); // sustained — now stressed

    // Act
    clock.advance(Duration.ofMinutes(6));
    boolean afterRecovery = tracker.isZoneStressed();

    // Assert
    assertThat(afterRecovery).isFalse();
  }

  @Test
  void doesNotRefetchWithinTheCacheTtl() {
    // Arrange
    given(zoneLoadClient.currentNorthZoneLoadMw()).willReturn(NORMAL_MW);
    MutableClock clock = new MutableClock(NOW);
    ZoneStressTracker tracker = new ZoneStressTracker(zoneLoadClient, config, clock);

    // Act: repeated calls within the cache TTL, no clock advance
    tracker.isZoneStressed();
    tracker.isZoneStressed();
    tracker.isZoneStressed();

    // Assert
    verify(zoneLoadClient, times(1)).currentNorthZoneLoadMw();
  }

  @Test
  void refetchesOnceTheCacheTtlHasElapsed() {
    // Arrange
    given(zoneLoadClient.currentNorthZoneLoadMw()).willReturn(NORMAL_MW);
    MutableClock clock = new MutableClock(NOW);
    ZoneStressTracker tracker = new ZoneStressTracker(zoneLoadClient, config, clock);
    tracker.isZoneStressed();

    // Act
    clock.advance(Duration.ofMinutes(6));
    tracker.isZoneStressed();

    // Assert
    verify(zoneLoadClient, times(2)).currentNorthZoneLoadMw();
  }
}
