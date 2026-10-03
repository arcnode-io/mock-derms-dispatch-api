package io.arcnode.mockderms.mirror;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import io.arcnode.mockderms.dispatch.EnvelopeDispatcher;
import io.arcnode.mockderms.dispatch.EventOrchestrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit — compares the utility's real, HTTP-reported actual watts against what this service itself
 * dispatched ({@link EventOrchestrator#currentRequiredReductionWatts()}) — this service already
 * knows what it commanded, so it only needs the actual reading repeated back, not the target too.
 * Mocked collaborator, AAA.
 */
@ExtendWith(MockitoExtension.class)
class ComplianceCheckerTest {

  @Mock private EventOrchestrator orchestrator;
  @Mock private EnvelopeDispatcher envelopeDispatcher;

  private ComplianceChecker checker() {
    return new ComplianceChecker(orchestrator, envelopeDispatcher);
  }

  @Test
  void isCompliantWhenNoEventIsActive() {
    // Arrange: nothing to be non-compliant with
    given(orchestrator.currentRequiredReductionWatts()).willReturn(null);

    // Act / Assert
    assertThat(checker().isCompliant(500_000.0)).isTrue();
  }

  @Test
  void isCompliantWhenImportIsUnderTheEnvelope() {
    // Arrange: an event is running and the site was told it may import 500 kW
    given(orchestrator.currentRequiredReductionWatts()).willReturn(76_500.0);
    given(envelopeDispatcher.currentImportLimitWatts()).willReturn(500_000.0);

    // Act / Assert: under the ceiling, so compliant
    assertThat(checker().isCompliant(495_000.0)).isTrue();
  }

  @Test
  void isCompliantWhenASiteShedsEverythingItCan() {
    // Arrange: the regression this fix exists for. The utility needs 76.5 kW of
    // reduction and has told the site to import nothing; the site has shed to its
    // floor at 492.8 kW. Comparing delivery to the *reduction* called this
    // non-compliant, which penalised a site doing everything available to it.
    given(orchestrator.currentRequiredReductionWatts()).willReturn(76_500.0);
    given(envelopeDispatcher.currentImportLimitWatts()).willReturn(500_000.0);

    // Act / Assert
    assertThat(checker().isCompliant(492_800.0)).isTrue();
  }

  @Test
  void isNotCompliantWhenImportExceedsTheEnvelope() {
    // Arrange
    given(orchestrator.currentRequiredReductionWatts()).willReturn(76_500.0);
    given(envelopeDispatcher.currentImportLimitWatts()).willReturn(500_000.0);

    // Act / Assert: an envelope is a ceiling, so non-compliance is one-sided —
    // drawing more than you were allowed. Drawing less is never a violation.
    assertThat(checker().isCompliant(900_000.0)).isFalse();
  }

  @Test
  void isCompliantWhenImportIsFarBelowTheEnvelope() {
    // Arrange
    given(orchestrator.currentRequiredReductionWatts()).willReturn(76_500.0);
    given(envelopeDispatcher.currentImportLimitWatts()).willReturn(500_000.0);

    // Act / Assert: the old rule treated deviation in either direction as a
    // breach, which is right for a setpoint you must hit and wrong for a limit
    // you must stay under.
    assertThat(checker().isCompliant(200_000.0)).isTrue();
  }
}
