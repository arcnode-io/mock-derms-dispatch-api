package io.arcnode.mockderms.mirror;

import io.arcnode.mockderms.mirror.ieee20305.MirrorUsagePoint;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Real HTTP intake for der-control-api's {@code MirrorUsagePoint} compliance report — the
 * replacement for the removed {@code ComplianceTracker}'s direct broker access. Path is the mirror
 * image of der-control-api's own {@code MirrorUsagePointClient}, same "not a spec-mandated URI"
 * caveat applies here too.
 */
@Tag(name = "mirror")
@RestController
public class MirrorUsagePointController {

  private static final Logger LOG = LoggerFactory.getLogger(MirrorUsagePointController.class);

  private final ComplianceChecker checker;

  public MirrorUsagePointController(ComplianceChecker checker) {
    this.checker = checker;
  }

  @PostMapping(value = "/mirror-usage-points", consumes = MediaType.APPLICATION_XML_VALUE)
  public ResponseEntity<Void> receive(@RequestBody String xml) {
    MirrorUsagePoint usagePoint = Ieee20305Xml.unmarshal(xml);
    double actualWatts = usagePoint.getMirrorMeterReading().get(0).getReading().getValue();
    boolean compliant = checker.isCompliant(actualWatts);
    if (LOG.isInfoEnabled()) {
      LOG.info("received MirrorUsagePoint, actual {}W, compliant={}", actualWatts, compliant);
    }
    return ResponseEntity.status(HttpStatus.CREATED).build();
  }
}
