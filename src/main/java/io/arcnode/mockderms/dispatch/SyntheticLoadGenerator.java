package io.arcnode.mockderms.dispatch;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * Internal "live loading" simulator, amps-denominated to match {@code dynamic_line_rating}'s own
 * unit. Deliberately not a real gridstatus.io call: this mock doesn't need real market data to
 * exercise der-control-api's real contract, and gridstatus.io's quota is a shared resource with
 * ems-analyst-agent, not backend-engineer's to spend for a mock service.
 */
@Component
public class SyntheticLoadGenerator {

  // Reason: MVP placeholders, not spec'd by anyone — chosen to plausibly straddle line_rating's
  // own nominal (600A per edp-api's line_rating.yaml), so the trigger check has real cases to fire
  // on without every reading being an obvious yes/no.
  static final double MIN_LOADING_AMPS = 200.0;
  static final double MAX_LOADING_AMPS = 650.0;

  /** One simulated live-loading reading, amps. */
  public double currentLoadingAmps() {
    return ThreadLocalRandom.current().nextDouble(MIN_LOADING_AMPS, MAX_LOADING_AMPS);
  }
}
