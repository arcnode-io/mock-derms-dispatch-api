package io.arcnode.mockderms.dispatch;

import io.arcnode.mockderms.Config;
import io.arcnode.mockderms.dispatch.dto.DerEventRequest;
import io.arcnode.mockderms.mirror.Ieee20305Xml;
import java.net.URI;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Calls ems-der-control-api's real {@code POST /der-events} — the actual utility-facing IEEE 2030.5
 * intake this whole mock exists to exercise — sending a real {@code Notification} document as
 * {@code application/sep+xml}, the media type IANA registers for IEEE 2030.5. Presents this
 * service's own identity via {@link MockUtilityIdentity} rather than proving possession of a
 * private key, matching how der-control-api's own {@code ClientIdentity} treats the header (it
 * never does a trust check itself — that's the real gateway's job in production).
 */
@Service
public class DerEventsClient {

  private static final String SEP_XML = "application/sep+xml";

  private static final Logger LOG = LoggerFactory.getLogger(DerEventsClient.class);

  private final RestClient client;
  private final String publicBaseUrl;
  private final SubscriptionRegistry subscriptions;

  public DerEventsClient(
      RestClient.Builder builder, Config config, SubscriptionRegistry subscriptions) {
    this.publicBaseUrl = config.publicBaseUrl();
    this.subscriptions = subscriptions;
    // Reason: the JDK HttpClient-backed default request factory hit a real, reproducible
    // EOFException from its own Http2Connection code against WireMock — confirmed across two
    // separate clean `mvn verify` runs (pass, then fail, no code change in between), so this
    // isn't a one-off. Neither WireMock nor der-control-api's own Tomcat has any HTTP/2 to
    // negotiate anyway. SimpleClientHttpRequestFactory is backed by HttpURLConnection, which
    // cannot speak HTTP/2 at all — verified reliable across repeated clean-recompiled runs.
    // Reason: no baseUrl — the destination is whatever absolute notificationURI the client
    // registered, which is the whole point of the Subscription. This service holds no address for
    // the site of its own.
    this.client = builder.requestFactory(new SimpleClientHttpRequestFactory()).build();
  }

  /**
   * Constraint dispatch (trigger fired) or event close (a terminal EventStatus on the same mRID).
   *
   * @param creationTime when this control was created — {@code Event::creationTime} is mandatory
   * @param programPath the {@link DerPrograms} path this control is issued under
   */
  public void dispatch(DerEventRequest request, Instant creationTime, String programPath) {
    SubscriptionRegistry.Registered subscription =
        subscriptions.forProgram(programPath).orElse(null);
    if (subscription == null) {
      if (LOG.isWarnEnabled()) {
        LOG.warn(
            "⚠️ No Subscription registered — nothing to notify. The site registers one at POST {},"
                + " and until it does this utility has no destination to push a DERControl to.",
            SubscriptionController.PATH);
      }
      return;
    }
    String subscriptionUri = publicBaseUrl + SubscriptionController.PATH + "/" + subscription.id();
    String document =
        Ieee20305Xml.marshal(
            DerControlNotificationFactory.build(
                request,
                creationTime,
                subscription.subscription().getSubscribedResource(),
                subscriptionUri));
    client
        .post()
        .uri(URI.create(subscription.notificationUri()))
        .contentType(MediaType.parseMediaType(SEP_XML))
        .header("X-SSL-Client-Cert", MockUtilityIdentity.HEADER_VALUE)
        .body(document)
        .retrieve()
        .toBodilessEntity();
  }
}
