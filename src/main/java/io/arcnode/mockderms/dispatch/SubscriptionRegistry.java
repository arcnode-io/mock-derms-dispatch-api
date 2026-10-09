package io.arcnode.mockderms.dispatch;

import io.arcnode.mockderms.mirror.ieee20305.SubscriptionElement;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The utility's record of the site's IEEE 2030.5 {@code Subscription}s. A client POSTs one per
 * DERProgram it is enrolled in, each carrying its own {@code notificationURI}, and the server
 * pushes that program's {@code Notification}s there — so with nothing on file for a program there
 * is no destination, and inventing one is what this exists to prevent.
 *
 * <p>In memory and one slot per program: this mock serves one site, so a re-registration against
 * the same program (the site restarting) replaces that program's entry rather than accumulating,
 * while the other programs' entries stand. A real server would keep a list per EndDevice and
 * persist it.
 */
@Component
public class SubscriptionRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(SubscriptionRegistry.class);

  // Reason: 2030.5 addresses resources by short path segments, and the program number already is
  // one — a subscription against /derp/2/derc is addressable as subscription 2, which keeps the
  // id stable across the site re-registering.
  private static final Pattern PROGRAM = Pattern.compile("/derp/(\\d+)/derc$");

  /** One registered subscription: its resource id and the URI to push notifications to. */
  public record Registered(String id, String notificationUri, SubscriptionElement subscription) {}

  private final Map<String, Registered> byProgram = new ConcurrentHashMap<>();

  /**
   * @param subscription the client's Subscription, already parsed
   * @return the id this subscription is now addressable at
   * @throws IllegalArgumentException when subscribedResource does not name one of this utility's
   *     DERProgram control lists
   */
  public String register(SubscriptionElement subscription) {
    String programPath = programPath(subscription.getSubscribedResource());
    Registered registered =
        new Registered(idOf(programPath), subscription.getNotificationURI(), subscription);
    byProgram.put(programPath, registered);
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "🔔 Subscription registered: notifications go to {} (subscribed to {})",
          registered.notificationUri(),
          subscription.getSubscribedResource());
    }
    return registered.id();
  }

  /**
   * The subscription a site registered against {@code programPath}, or empty when it has not.
   *
   * @param programPath a {@link DerPrograms} path; matched as the suffix of the registered
   *     subscribedResource, since the site names it with the utility's absolute base URL
   */
  public Optional<Registered> forProgram(String programPath) {
    return Optional.ofNullable(byProgram.get(programPath));
  }

  /** The subscription addressable at {@code id}, for serving it back as a resource. */
  public Optional<SubscriptionElement> find(String id) {
    return byProgram.values().stream()
        .filter(registered -> registered.id().equals(id))
        .map(Registered::subscription)
        .findFirst();
  }

  private static String programPath(String subscribedResource) {
    Matcher m = PROGRAM.matcher(subscribedResource == null ? "" : subscribedResource);
    if (!m.find()) {
      throw new IllegalArgumentException(
          "subscribedResource does not name a DERProgram control list: " + subscribedResource);
    }
    return m.group(0);
  }

  private static String idOf(String programPath) {
    Matcher m = PROGRAM.matcher(programPath);
    m.find();
    return m.group(1);
  }
}
