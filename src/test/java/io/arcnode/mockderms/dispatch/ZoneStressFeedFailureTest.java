package io.arcnode.mockderms.dispatch;

import static io.arcnode.mockderms.dispatch.ZoneStressFixtures.ELEVATED_MW;
import static io.arcnode.mockderms.dispatch.ZoneStressFixtures.NORMAL_MW;
import static io.arcnode.mockderms.dispatch.ZoneStressFixtures.NOW;
import static io.arcnode.mockderms.dispatch.ZoneStressFixtures.PAST_TTL;
import static io.arcnode.mockderms.dispatch.ZoneStressFixtures.reachStressed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.arcnode.mockderms.Config;
import io.arcnode.mockderms.dispatch.ZoneStressFixtures.MutableClock;
import java.time.Duration;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit — what the tracker does when ERCOT gives it nothing. A failed IHLF call yields no value at
 * all, so the tracker rides the last good reading while it is still inside MAX_USABLE_READING_AGE,
 * then falls back to "not stressed" and raises the zone-feed-stale flag. Absence can only remove
 * the margin boost, never add one, so an unavailable feed cannot cause a dispatch. Mocked
 * ErcotZoneLoadClient, fake Clock, AAA.
 */
@ExtendWith(MockitoExtension.class)
class ZoneStressFeedFailureTest {

  private final Config config = ZoneStressFixtures.config();

  @Mock private ErcotZoneLoadClient zoneLoadClient;

  @Test
  void neverStressedWhenTheFeedHasNeverSucceeded() {
    // Arrange
    given(zoneLoadClient.currentNorthZoneLoadMw()).willReturn(OptionalDouble.empty());
    ZoneStressTracker tracker =
        new ZoneStressTracker(zoneLoadClient, config, new MutableClock(NOW));

    // Act
    boolean stressed = tracker.isZoneStressed();

    // Assert
    assertThat(stressed).isFalse();
    assertThat(tracker.isZoneFeedStale()).isTrue();
  }

  @Test
  void ridesTheLastGoodReadingWhileItIsStillUsable() {
    // Arrange
    given(zoneLoadClient.currentNorthZoneLoadMw())
        .willReturn(
            OptionalDouble.of(ELEVATED_MW),
            OptionalDouble.of(ELEVATED_MW),
            OptionalDouble.of(ELEVATED_MW),
            OptionalDouble.empty());
    MutableClock clock = new MutableClock(NOW);
    ZoneStressTracker tracker = new ZoneStressTracker(zoneLoadClient, config, clock);
    reachStressed(tracker, clock);

    // Act: the failing attempt lands while the last good reading is 5m30s old — under the 6m budget
    clock.advance(Duration.ofMinutes(5).plusSeconds(30));
    boolean stressed = tracker.isZoneStressed();

    // Assert
    assertThat(stressed).isTrue();
    assertThat(tracker.isZoneFeedStale()).isFalse();
  }

  @Test
  void dropsToNotStressedOnceTheLastGoodReadingAgesOut() {
    // Arrange
    given(zoneLoadClient.currentNorthZoneLoadMw())
        .willReturn(
            OptionalDouble.of(ELEVATED_MW),
            OptionalDouble.of(ELEVATED_MW),
            OptionalDouble.of(ELEVATED_MW),
            OptionalDouble.empty());
    MutableClock clock = new MutableClock(NOW);
    ZoneStressTracker tracker = new ZoneStressTracker(zoneLoadClient, config, clock);
    reachStressed(tracker, clock);

    // Act: the last good reading is now past MAX_USABLE_READING_AGE
    clock.advance(Duration.ofMinutes(7));
    boolean stressed = tracker.isZoneStressed();

    // Assert
    assertThat(stressed).isFalse();
    assertThat(tracker.isZoneFeedStale()).isTrue();
  }

  @Test
  void doesNotHammerErcotWhileTheFeedIsFailing() {
    // Arrange: one failed attempt, then ticks arriving every 5s inside the retry budget
    given(zoneLoadClient.currentNorthZoneLoadMw()).willReturn(OptionalDouble.empty());
    MutableClock clock = new MutableClock(NOW);
    ZoneStressTracker tracker = new ZoneStressTracker(zoneLoadClient, config, clock);
    tracker.isZoneStressed();

    // Act
    for (int i = 0; i < 12; i++) {
      clock.advance(Duration.ofSeconds(5));
      tracker.isZoneStressed();
    }

    // Assert: a failure must not reopen the retry window — still one attempt
    verify(zoneLoadClient, times(1)).currentNorthZoneLoadMw();
  }

  @Test
  void clearsTheStaleFlagOnceTheFeedRecovers() {
    // Arrange
    given(zoneLoadClient.currentNorthZoneLoadMw())
        .willReturn(OptionalDouble.empty(), OptionalDouble.of(NORMAL_MW));
    MutableClock clock = new MutableClock(NOW);
    ZoneStressTracker tracker = new ZoneStressTracker(zoneLoadClient, config, clock);
    tracker.isZoneStressed();
    assertThat(tracker.isZoneFeedStale()).isTrue();

    // Act
    clock.advance(PAST_TTL);
    tracker.isZoneStressed();

    // Assert
    assertThat(tracker.isZoneFeedStale()).isFalse();
  }
}
