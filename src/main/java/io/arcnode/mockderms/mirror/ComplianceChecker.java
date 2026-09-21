package io.arcnode.mockderms.mirror;

import io.arcnode.mockderms.dispatch.EventOrchestrator;
import org.springframework.stereotype.Component;

/**
 * Compares the utility's own real, HTTP-reported actual watts ({@link MirrorUsagePointController},
 * from a real {@code MirrorUsagePoint} POST) against what this service itself dispatched ({@link
 * EventOrchestrator#currentTargetWatts()}) — no need for the report to repeat the target back, this
 * service already knows what it commanded.
 */
@Component
public class ComplianceChecker {

  // Reason: MVP placeholder, not spec'd by anyone — same shape as the removed ComplianceTracker's
  // own tolerance. Tune once real BESS delivery behavior is observed via this real HTTP path.
  private static final double TOLERANCE_FRACTION = 0.05;

  private final EventOrchestrator orchestrator;

  public ComplianceChecker(EventOrchestrator orchestrator) {
    this.orchestrator = orchestrator;
  }

  /**
   * True when no event is active (nothing to be non-compliant with) or actual is within tolerance.
   */
  public boolean isCompliant(double actualWatts) {
    Double target = orchestrator.currentTargetWatts();
    if (target == null) {
      return true;
    }
    double tolerance = Math.abs(target) * TOLERANCE_FRACTION;
    return Math.abs(actualWatts - target) <= tolerance;
  }
}
