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
    assertThat(registry.active()).isEmpty();
  }

  @Test
  void keepsWhatTheClientRegisteredSoNotificationsHaveADestination() {
    // Arrange
    SubscriptionRegistry registry = new SubscriptionRegistry();

    // Act
    String id = registry.register(subscription(NOTIFICATION_URI));

    // Assert
    assertThat(registry.active()).isPresent();
    assertThat(registry.active().orElseThrow().notificationUri()).isEqualTo(NOTIFICATION_URI);
    assertThat(registry.active().orElseThrow().id()).isEqualTo(id);
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
    assertThat(registry.active().orElseThrow().notificationUri())
        .isEqualTo("https://site.invalid/moved");
  }
}
