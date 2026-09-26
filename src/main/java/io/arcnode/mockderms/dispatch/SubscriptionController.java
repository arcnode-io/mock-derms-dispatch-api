package io.arcnode.mockderms.dispatch;

import io.arcnode.mockderms.mirror.Ieee20305Xml;
import io.arcnode.mockderms.mirror.ieee20305.SubscriptionElement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The utility's IEEE 2030.5 {@code Subscription} resource. A client POSTs a Subscription naming its
 * own {@code notificationURI}; the utility records it and pushes {@code Notification}s there. The
 * subscription is then addressable, which is what makes a Notification's {@code subscriptionURI}
 * resolve to something real instead of naming a resource that was never served.
 *
 * <p>2030.5 discovers resources by following links rather than by fixed paths, so these URIs are
 * ours; only their absoluteness in a Notification is a schema requirement.
 */
@Tag(name = "subscription")
@RestController
public class SubscriptionController {

  /** The media type IANA registers for IEEE 2030.5 (published specification: IEEE 2030.5). */
  private static final String SEP_XML = "application/sep+xml";

  static final String PATH = "/sub";

  private final SubscriptionRegistry registry;

  public SubscriptionController(SubscriptionRegistry registry) {
    this.registry = registry;
  }

  @Operation(
      summary = "Register a Subscription",
      description =
          "Records the client's notificationURI and returns the created Subscription's location."
              + " Re-registering replaces the previous one, since this mock serves one site.")
  @PostMapping(
      value = PATH,
      consumes = {SEP_XML, MediaType.APPLICATION_XML_VALUE})
  public ResponseEntity<Void> subscribe(@RequestBody String xml) {
    SubscriptionElement subscription = Ieee20305Xml.unmarshalSubscription(xml);
    if (subscription.getNotificationURI() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Subscription is missing its mandatory notificationURI");
    }
    String id = registry.register(subscription);
    return ResponseEntity.created(URI.create(PATH + "/" + id)).build();
  }

  @Operation(summary = "Read a registered Subscription")
  @GetMapping(value = PATH + "/{id}", produces = SEP_XML)
  public ResponseEntity<String> read(@PathVariable String id) {
    return registry
        .find(id)
        .map(subscription -> ResponseEntity.ok(Ieee20305Xml.marshal(subscription)))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }
}
