package io.arcnode.mockderms.dispatch;

import io.arcnode.mockderms.mirror.ieee20305.SubscriptionElement;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The utility's record of the site's IEEE 2030.5 {@code Subscription}. A client POSTs one carrying
 * its own {@code notificationURI}, and the server pushes {@code Notification}s there — so with
 * nothing on file there is no destination, and inventing one is what this exists to prevent.
 *
 * <p>In memory and single-slot: this mock serves one site, so a re-registration (the site
 * restarting) replaces the previous entry rather than accumulating, and the subscription is
 * addressable at one well-known id. A real server would keep a list per EndDevice and persist it.
 */
@Component
public class SubscriptionRegistry {

  // Reason: 2030.5 addresses resources by short path segments, and a single-slot registry only ever
  // needs one — the Notification's subscriptionURI resolves to it.
  private static final String ONLY_ID = "1";

  private static final Logger LOG = LoggerFactory.getLogger(SubscriptionRegistry.class);

  /** One registered subscription: its resource id and the URI to push notifications to. */
  public record Registered(String id, String notificationUri, SubscriptionElement subscription) {}

  private final AtomicReference<@Nullable Registered> current = new AtomicReference<>();

  /**
   * @param subscription the client's Subscription, already parsed
   * @return the id this subscription is now addressable at
   */
  public String register(SubscriptionElement subscription) {
    Registered registered =
        new Registered(ONLY_ID, subscription.getNotificationURI(), subscription);
    current.set(registered);
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "🔔 Subscription registered: notifications go to {} (subscribed to {})",
          registered.notificationUri(),
          subscription.getSubscribedResource());
    }
    return registered.id();
  }

  /** The subscription notifications should be pushed to, or empty when no client has registered. */
  public Optional<Registered> active() {
    return Optional.ofNullable(current.get());
  }

  /** The subscription addressable at {@code id}, for serving it back as a resource. */
  public Optional<SubscriptionElement> find(String id) {
    return active().filter(registered -> registered.id().equals(id)).map(Registered::subscription);
  }
}
