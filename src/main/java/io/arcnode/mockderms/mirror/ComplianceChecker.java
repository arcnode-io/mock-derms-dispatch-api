package io.arcnode.mockderms.mirror;

import io.arcnode.mockderms.dispatch.EnvelopeDispatcher;
import io.arcnode.mockderms.dispatch.EventOrchestrator;
import org.springframework.stereotype.Component;

/**
 * Compares the utility's own real, HTTP-reported actual watts ({@link MirrorUsagePointController},
 * from a real {@code MirrorUsagePoint} POST) against what this service itself dispatched ({@link
 * EventOrchestrator#currentRequiredReductionWatts()}) — no need for the report to repeat the ask
 * back, this service already knows what it commanded.
 */
@Component
public class ComplianceChecker {

  // Reason: MVP placeholder, not spec'd by anyone — same shape as the removed ComplianceTracker's
  // own tolerance. Tune once real BESS delivery behavior is observed via this real HTTP path.
  private static final double TOLERANCE_FRACTION = 0.05;

  private final EventOrchestrator orchestrator;

  private final EnvelopeDispatcher envelopeDispatcher;

  public ComplianceChecker(EventOrchestrator orchestrator, EnvelopeDispatcher envelopeDispatcher) {
    this.orchestrator = orchestrator;
    this.envelopeDispatcher = envelopeDispatcher;
  }

  /**
   * True when no event is active (nothing to be non-compliant with) or actual is within tolerance.
   */
  public boolean isCompliant(double actualWatts) {
    if (orchestrator.currentRequiredReductionWatts() == null) {
      return true;
    }
    // Reason: compared against the envelope, not against the reduction this utility needs. The
    // reduction is how far the conductor is over its margin; the envelope is what the site was
    // actually told it may import. Comparing delivery to a reduction judged a site that had shed
    // everything it could as non-compliant, because the two quantities differ by the load.
    //
    // A null limit means nothing has been published yet, so there is no ceiling to breach.
    Double importLimitWatts = envelopeDispatcher.currentImportLimitWatts();
    return importLimitWatts == null || actualWatts <= importLimitWatts * (1 + TOLERANCE_FRACTION);
  }
}
