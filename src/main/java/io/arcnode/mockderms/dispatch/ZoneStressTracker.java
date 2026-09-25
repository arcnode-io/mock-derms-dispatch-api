package io.arcnode.mockderms.dispatch;

import io.arcnode.mockderms.Config;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p>When ERCOT cannot be read at all, the last good reading still stands until it is older than
 * {@link #MAX_USABLE_READING_AGE}; past that the zone counts as not stressed and {@link
 * #isZoneFeedStale()} goes true. Absence therefore only ever removes the margin boost, so an
 * unavailable feed makes the trigger less eager and can never cause a dispatch on its own. Retries
 * are budgeted off the last *attempt*, not the last success, so a sustained ERCOT outage still
 * costs one call per {@link #CACHE_TTL} rather than one per tick.
 */
@Component
public class ZoneStressTracker {

  private static final Logger LOG = LoggerFactory.getLogger(ZoneStressTracker.class);

  // Reason: matches IHLF's own real refresh cadence (confirmed live: 5-minute-updated documents)
  // — this isn't a performance shortcut, it's simply not polling faster than the data can change.
  static final Duration CACHE_TTL = Duration.ofMinutes(5);
  // Reason: one full IHLF cadence plus slack for jitter in when the document actually lands. A
  // reading inside this window is still the newest one ERCOT ever published; past it, we no longer
  // know what the zone is doing.
  static final Duration MAX_USABLE_READING_AGE = CACHE_TTL.plusMinutes(1);
  // Reason: same shape as DeliveryShortfallMonitor.SHORTFALL_THRESHOLD_TICKS.
  static final int ZONE_STRESS_THRESHOLD_READINGS = 3;

  private final ErcotZoneLoadClient zoneLoadClient;
  private final double zoneStressThresholdMw;
  private final Clock clock;

  private final AtomicReference<@Nullable CachedReading> lastGood = new AtomicReference<>();
  private final AtomicReference<@Nullable Instant> lastAttemptAt = new AtomicReference<>();
  private final AtomicInteger consecutiveElevatedReadings = new AtomicInteger();
  private final AtomicBoolean zoneStressed = new AtomicBoolean();
  private final AtomicBoolean zoneFeedStale = new AtomicBoolean();

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

  /**
   * True when there is no usable ERCOT reading, so the zone-stress signal is currently unavailable
   * rather than merely calm. Drives the demo narration and the WARN log.
   */
  public boolean isZoneFeedStale() {
    refreshIfDue();
    return zoneFeedStale.get();
  }

  private void refreshIfDue() {
    Instant now = clock.instant();
    Instant attemptedAt = lastAttemptAt.get();
    if (attemptedAt != null && Duration.between(attemptedAt, now).compareTo(CACHE_TTL) < 0) {
      return;
    }
    lastAttemptAt.set(now);
    OptionalDouble reading = zoneLoadClient.currentNorthZoneLoadMw();
    if (reading.isEmpty()) {
      holdOrExpireLastGood(now);
      return;
    }
    double value = reading.getAsDouble();
    lastGood.set(new CachedReading(value, now));
    zoneFeedStale.set(false);
    recordReading(value);
  }

  private void holdOrExpireLastGood(Instant now) {
    CachedReading good = lastGood.get();
    Duration age = good == null ? null : Duration.between(good.fetchedAt(), now);
    if (age != null && age.compareTo(MAX_USABLE_READING_AGE) < 0) {
      // Reason: same reading as last time, not a new observation — leave the debounce counter and
      // the stressed state exactly where the last real reading left them.
      if (LOG.isInfoEnabled()) {
        LOG.info(
            "🌡️ Zone feed unavailable — holding last good reading ({} MW, {}s old)",
            good.northZoneMw(),
            age.toSeconds());
      }
      return;
    }
    consecutiveElevatedReadings.set(0);
    zoneStressed.set(false);
    zoneFeedStale.set(true);
    if (LOG.isWarnEnabled()) {
      LOG.warn(
          "⚠️ zone_feed_stale — no usable ERCOT reading (last good {}), zone counts as not"
              + " stressed, no margin boost",
          age == null ? "none ever" : age.toSeconds() + "s old");
    }
  }

  private void recordReading(double value) {
    if (value > zoneStressThresholdMw) {
      boolean nowStressed =
          consecutiveElevatedReadings.incrementAndGet() >= ZONE_STRESS_THRESHOLD_READINGS;
      zoneStressed.set(nowStressed);
      if (LOG.isInfoEnabled()) {
        LOG.info(
            "🌡️ Zone stress evaluated: {} MW > {} MW threshold → {}",
            value,
            zoneStressThresholdMw,
            nowStressed ? "ZONE STRESSED" : "elevated (not yet sustained)");
      }
    } else {
      consecutiveElevatedReadings.set(0);
      zoneStressed.set(false);
      if (LOG.isInfoEnabled()) {
        LOG.info(
            "🌡️ Zone stress evaluated: {} MW within normal range (threshold {} MW)",
            value,
            zoneStressThresholdMw);
      }
    }
  }
}
