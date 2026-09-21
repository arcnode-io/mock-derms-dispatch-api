package io.arcnode.mockderms.mirror;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import io.arcnode.mockderms.dispatch.EventOrchestrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit — compares the utility's real, HTTP-reported actual watts against what this service itself
 * dispatched ({@link EventOrchestrator#currentTargetWatts()}) — this service already knows what it
 * commanded, so it only needs the actual reading repeated back, not the target too. Mocked
 * collaborator, AAA.
 */
@ExtendWith(MockitoExtension.class)
class ComplianceCheckerTest {

  @Mock private EventOrchestrator orchestrator;

  private ComplianceChecker checker() {
    return new ComplianceChecker(orchestrator);
  }

  @Test
  void isCompliantWhenNoEventIsActive() {
    // Arrange: nothing to be non-compliant with
    given(orchestrator.currentTargetWatts()).willReturn(null);

    // Act / Assert
    assertThat(checker().isCompliant(500_000.0)).isTrue();
  }

  @Test
  void isCompliantWhenActualIsWithinToleranceOfTarget() {
    // Arrange
    given(orchestrator.currentTargetWatts()).willReturn(500_000.0);

    // Act / Assert: 1% off, well within the 5% tolerance
    assertThat(checker().isCompliant(495_000.0)).isTrue();
  }

  @Test
  void isNotCompliantWhenActualIsFarFromTarget() {
    // Arrange
    given(orchestrator.currentTargetWatts()).willReturn(500_000.0);

    // Act / Assert: 60% off
    assertThat(checker().isCompliant(200_000.0)).isFalse();
  }
}
