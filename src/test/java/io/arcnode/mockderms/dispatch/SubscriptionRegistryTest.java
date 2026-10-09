package io.arcnode.mockderms.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import io.arcnode.mockderms.mirror.ieee20305.SubscriptionElement;
import org.junit.jupiter.api.Test;

/**
 * Unit — the utility's record of who asked to be notified. IEEE 2030.5 has the client POST a
 * Subscription carrying its own notificationURI, and the server then pushes Notifications there, so
 * a server with no subscription on file has nowhere to push and must not invent a destination. AAA.
 */
class SubscriptionRegistryTest {

  private static final String NOTIFICATION_URI = "https://site.invalid/der-events";

  private static SubscriptionElement subscription(String notificationUri) {
    SubscriptionElement subscription = new SubscriptionElement();
    subscription.setSubscribedResource("https://utility.invalid/derp/1/derc");
    subscription.setNotificationURI(notificationUri);
    subscription.setEncoding((short) 0);
    subscription.setLevel("+S2");
    subscription.setLimit(1L);
    return subscription;
  }

  @Test
  void hasNothingOnFileBeforeAnyClientRegisters() {
    // Arrange
    SubscriptionRegistry registry = new SubscriptionRegistry();

    // Act / Assert
    assertThat(registry.forProgram("/derp/1/derc")).isEmpty();
  }

  @Test
  void keepsOneSubscriptionPerProgramSoEachPurposeHasItsOwnDestination() {
    // Arrange: a site enrolled in two of this utility's programs registers against both
    SubscriptionRegistry registry = new SubscriptionRegistry();
    SubscriptionElement constraint = subscription(NOTIFICATION_URI);
    SubscriptionElement flex = subscription(NOTIFICATION_URI);
    flex.setSubscribedResource("https://utility.invalid/derp/2/derc");

    // Act
    String constraintId = registry.register(constraint);
    String flexId = registry.register(flex);

    // Assert: neither displaces the other, and each is addressable by its program
    assertThat(registry.forProgram("/derp/1/derc").orElseThrow().id()).isEqualTo(constraintId);
    assertThat(registry.forProgram("/derp/2/derc").orElseThrow().id()).isEqualTo(flexId);
    assertThat(constraintId).isNotEqualTo(flexId);
    assertThat(registry.forProgram("/derp/9/derc")).isEmpty();
  }

  @Test
  void keepsWhatTheClientRegisteredSoNotificationsHaveADestination() {
    // Arrange
    SubscriptionRegistry registry = new SubscriptionRegistry();

    // Act
    String id = registry.register(subscription(NOTIFICATION_URI));

    // Assert
    assertThat(registry.forProgram("/derp/1/derc")).isPresent();
    assertThat(registry.forProgram("/derp/1/derc").orElseThrow().notificationUri())
        .isEqualTo(NOTIFICATION_URI);
    assertThat(registry.forProgram("/derp/1/derc").orElseThrow().id()).isEqualTo(id);
  }

  @Test
  void servesTheRegisteredSubscriptionBackAtItsOwnId() {
    // Arrange: 2030.5 subscriptions are addressable resources, and the Notification references one
    SubscriptionRegistry registry = new SubscriptionRegistry();
    String id = registry.register(subscription(NOTIFICATION_URI));

    // Act / Assert
    assertThat(registry.find(id)).isPresent();
    assertThat(registry.find("does-not-exist")).isEmpty();
  }

  @Test
  void aReregisteringClientReplacesItsOwnSubscriptionRatherThanAccumulating() {
    // Arrange: the site re-registers on restart, and this mock serves exactly one site
    SubscriptionRegistry registry = new SubscriptionRegistry();
    registry.register(subscription(NOTIFICATION_URI));

    // Act
    registry.register(subscription("https://site.invalid/moved"));

    // Assert
    assertThat(registry.forProgram("/derp/1/derc").orElseThrow().notificationUri())
        .isEqualTo("https://site.invalid/moved");
  }
}
