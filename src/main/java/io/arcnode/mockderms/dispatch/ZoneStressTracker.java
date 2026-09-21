package io.arcnode.mockderms.dispatch;

import io.arcnode.mockderms.Config;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Turns {@link ErcotZoneLoadClient}'s raw North-zone MW reading into a debounced "is the zone
 * currently stressed" signal for {@link TriggerEvaluator}. Two real gaps an SME review caught in
 * the first cut of this integration: (1) the raw client was being called on every 5s orchestrator
 * tick against a report that only refreshes every 5 minutes — ~60x more real ERCOT calls than
 * useful, on a personal credential; (2) a single reading crossing {@code zoneStressThresholdMw}
 * flipped the margin boost immediately, with no debounce against a noisy signal (IHLF is a
 * forecast, updated every 5 minutes — not a direct measurement).
 *
 * <p>Caches the raw reading to IHLF's own real refresh cadence, and requires {@link
 * #ZONE_STRESS_THRESHOLD_READINGS} consecutive fresh elevated readings before entering the stressed
 * state — same debounce shape as ems-der-control-api's {@code DeliveryShortfallMonitor} (slow to
 * enter, clears immediately on the first reading back below threshold; IHLF is itself a forecast,
 * so a drop back down is trusted right away — only the rising edge is debounced). {@code
 * zoneStressThresholdMw} (from {@link Config}) is arbitrary, not a placeholder awaiting review — an
 * IEEE 738 sizing attempt confirmed no physical derivation exists (the weather effect is already in
 * the live DLR reading), and no empirical one does either until real local loading telemetry
 * exists.
 */
@Component
public class ZoneStressTracker {

  // Reason: matches IHLF's own real refresh cadence (confirmed live: 5-minute-updated documents)
  // — this isn't a performance shortcut, it's simply not polling faster than the data can change.
  static final Duration CACHE_TTL = Duration.ofMinutes(5);
  // Reason: same shape as DeliveryShortfallMonitor.SHORTFALL_THRESHOLD_TICKS.
  static final int ZONE_STRESS_THRESHOLD_READINGS = 3;

  private final ErcotZoneLoadClient zoneLoadClient;
  private final double zoneStressThresholdMw;
  private final Clock clock;

  private final AtomicReference<@Nullable CachedReading> cached = new AtomicReference<>();
  private final AtomicInteger consecutiveElevatedReadings = new AtomicInteger();
  private final AtomicBoolean zoneStressed = new AtomicBoolean();

  private record CachedReading(double northZoneMw, Instant fetchedAt) {}

  public ZoneStressTracker(ErcotZoneLoadClient zoneLoadClient, Config config, Clock clock) {
    this.zoneLoadClient = zoneLoadClient;
    this.zoneStressThresholdMw = config.zoneStressThresholdMw();
    this.clock = clock;
  }

  /** Debounced "is the zone currently stressed" signal — see class Javadoc. */
  public boolean isZoneStressed() {
    refreshIfDue();
    return zoneStressed.get();
  }

  private void refreshIfDue() {
    Instant now = clock.instant();
    CachedReading reading = cached.get();
    if (reading != null && Duration.between(reading.fetchedAt(), now).compareTo(CACHE_TTL) < 0) {
      return;
    }
    double value = zoneLoadClient.currentNorthZoneLoadMw();
    recordReading(value);
    cached.set(new CachedReading(value, now));
  }

  private void recordReading(double value) {
    if (value > zoneStressThresholdMw) {
      if (consecutiveElevatedReadings.incrementAndGet() >= ZONE_STRESS_THRESHOLD_READINGS) {
        zoneStressed.set(true);
      }
    } else {
      consecutiveElevatedReadings.set(0);
      zoneStressed.set(false);
    }
  }
}
