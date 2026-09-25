package io.arcnode.mockderms.dispatch;

import static io.arcnode.mockderms.dispatch.ZoneStressFixtures.ELEVATED_MW;
import static io.arcnode.mockderms.dispatch.ZoneStressFixtures.NORMAL_MW;
import static io.arcnode.mockderms.dispatch.ZoneStressFixtures.NOW;
import static io.arcnode.mockderms.dispatch.ZoneStressFixtures.PAST_TTL;
import static io.arcnode.mockderms.dispatch.ZoneStressFixtures.THRESHOLD_MW;
import static io.arcnode.mockderms.dispatch.ZoneStressFixtures.reachStressed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.arcnode.mockderms.Config;
import io.arcnode.mockderms.dispatch.ZoneStressFixtures.MutableClock;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit — turns ErcotZoneLoadClient's raw MW reading into a debounced "zone is stressed" signal:
 * cached to IHLF's own real refresh cadence, and requires N consecutive elevated *fresh* readings
 * before entering the stressed state (same shape as ems-der-control-api's own
 * DeliveryShortfallMonitor — slow to enter, clears immediately on the first reading back below
 * threshold). The absent-reading path lives in ZoneStressFeedFailureTest. Mocked
 * ErcotZoneLoadClient, fake Clock, AAA.
 */
@ExtendWith(MockitoExtension.class)
class ZoneStressTrackerTest {

  private final Config config = ZoneStressFixtures.config();

  @Mock private ErcotZoneLoadClient zoneLoadClient;

  @Test
  void notStressedBeforeAnyElevatedReading() {
    // Arrange
    given(zoneLoadClient.currentNorthZoneLoadMw()).willReturn(OptionalDouble.of(NORMAL_MW));
    ZoneStressTracker tracker =
        new ZoneStressTracker(zoneLoadClient, config, new MutableClock(NOW));

    // Act / Assert
    assertThat(tracker.isZoneStressed()).isFalse();
  }

  @Test
  void notStressedAtExactlyTheThresholdValue() {
    // Arrange: strictly greater-than, same boundary convention as TriggerEvaluator's own margin
    // comparison
    given(zoneLoadClient.currentNorthZoneLoadMw()).willReturn(OptionalDouble.of(THRESHOLD_MW));
    ZoneStressTracker tracker =
        new ZoneStressTracker(zoneLoadClient, config, new MutableClock(NOW));

    // Act / Assert
    assertThat(tracker.isZoneStressed()).isFalse();
  }

  @Test
  void staysNotStressedBeforeThreeConsecutiveElevatedReadings() {
    // Arrange
    given(zoneLoadClient.currentNorthZoneLoadMw()).willReturn(OptionalDouble.of(ELEVATED_MW));
    MutableClock clock = new MutableClock(NOW);
    ZoneStressTracker tracker = new ZoneStressTracker(zoneLoadClient, config, clock);

    // Act: two fresh elevated readings — not sustained yet
    tracker.isZoneStressed();
    clock.advance(PAST_TTL);
    boolean afterTwo = tracker.isZoneStressed();

    // Assert
    assertThat(afterTwo).isFalse();
  }

  @Test
  void becomesStressedAfterThreeConsecutiveElevatedFreshReadings() {
    // Arrange
    given(zoneLoadClient.currentNorthZoneLoadMw()).willReturn(OptionalDouble.of(ELEVATED_MW));
    MutableClock clock = new MutableClock(NOW);
    ZoneStressTracker tracker = new ZoneStressTracker(zoneLoadClient, config, clock);

    // Act
    reachStressed(tracker, clock);

    // Assert
    assertThat(tracker.isZoneStressed()).isTrue();
  }

  @Test
  void clearsImmediatelyOnASingleFreshReadingBackBelowThreshold() {
    // Arrange: reach stressed, then one normal fresh reading
    given(zoneLoadClient.currentNorthZoneLoadMw())
        .willReturn(
            OptionalDouble.of(ELEVATED_MW),
            OptionalDouble.of(ELEVATED_MW),
            OptionalDouble.of(ELEVATED_MW),
            OptionalDouble.of(NORMAL_MW));
    MutableClock clock = new MutableClock(NOW);
    ZoneStressTracker tracker = new ZoneStressTracker(zoneLoadClient, config, clock);
    reachStressed(tracker, clock);
    assertThat(tracker.isZoneStressed()).isTrue(); // sustained — now stressed

    // Act
    clock.advance(PAST_TTL);
    boolean afterRecovery = tracker.isZoneStressed();

    // Assert
    assertThat(afterRecovery).isFalse();
  }

  @Test
  void doesNotRefetchWithinTheCacheTtl() {
    // Arrange
    given(zoneLoadClient.currentNorthZoneLoadMw()).willReturn(OptionalDouble.of(NORMAL_MW));
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
    given(zoneLoadClient.currentNorthZoneLoadMw()).willReturn(OptionalDouble.of(NORMAL_MW));
    MutableClock clock = new MutableClock(NOW);
    ZoneStressTracker tracker = new ZoneStressTracker(zoneLoadClient, config, clock);
    tracker.isZoneStressed();

    // Act
    clock.advance(PAST_TTL);
    tracker.isZoneStressed();

    // Assert
    verify(zoneLoadClient, times(2)).currentNorthZoneLoadMw();
  }
}
