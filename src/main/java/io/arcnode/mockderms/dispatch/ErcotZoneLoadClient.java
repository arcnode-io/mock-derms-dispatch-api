package io.arcnode.mockderms.dispatch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.arcnode.mockderms.Config;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Real-time North-zone (ERCOT weather zone) load, from the live NP3-562-CD "Intra-Hour Load
 * Forecast by Weather Zone" report — verified directly against ERCOT's Public API (5-minute
 * cadence, confirmed live), queried via {@link ErcotTokenClient}. Falls back to a labeled synthetic
 * reading on any failure (missing credentials, auth failure, rate limit, network error) — same
 * resilience shape as ems-analyst-agent's own gridstatus.io integration (markets.py), so a demo or
 * CI run without live ERCOT credentials degrades gracefully instead of failing.
 *
 * <p>POC-stage: feeds {@link TriggerEvaluator}'s dynamic margin only. The local DLR trigger stays
 * the sole authority on whether to fire — this signal can only make the margin more conservative,
 * never independently cause a dispatch. Pending final design review (routed to system-architect/
 * SME, not yet resolved as of this build).
 */
@Component
public class ErcotZoneLoadClient {

  private static final Logger LOG = LoggerFactory.getLogger(ErcotZoneLoadClient.class);
  // Reason: live North-zone load observed while building this (2026-09-21, IHLF's rolling 2h
  // window) sat in the 1714-1922 MW range — keep the synthetic fallback plausible in that band
  // rather than an arbitrary number, matching SyntheticLoadGenerator's own MVP-placeholder
  // reasoning. Tune once this runs against more than a single day's observation.
  public static final double SYNTHETIC_MIN_MW = 1600.0;
  public static final double SYNTHETIC_MAX_MW = 2000.0;

  private final RestClient client;
  private final ErcotTokenClient tokenClient;
  private final String subscriptionKey;
  private final String archiveUrl;

  public ErcotZoneLoadClient(
      RestClient.Builder builder,
      ErcotTokenClient tokenClient,
      Config config,
      @Value("${ERCOT_PRIMARY_KEY:}") String subscriptionKey) {
    // Reason: same HTTP/2-incapable pin as DerEventsClient — no reason to risk the same JDK
    // HttpClient/Http2Connection failure mode against a different external host.
    this.client = builder.requestFactory(new SimpleClientHttpRequestFactory()).build();
    this.tokenClient = tokenClient;
    this.subscriptionKey = subscriptionKey;
    this.archiveUrl = config.ercotArchiveUrl();
  }

  /** Current North-zone load, MW — real IHLF data, or a synthetic fallback on any failure. */
  public double currentNorthZoneLoadMw() {
    try {
      return fetchReal();
    } catch (RuntimeException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("ERCOT IHLF call failed ({}), using synthetic North-zone load", e.toString());
      }
      return ThreadLocalRandom.current().nextDouble(SYNTHETIC_MIN_MW, SYNTHETIC_MAX_MW);
    }
  }

  private double fetchReal() {
    String token = tokenClient.currentToken();

    ArchiveListing listing =
        client
            .get()
            .uri(archiveUrl + "?size=1")
            .header("Authorization", "Bearer " + token)
            .header("Ocp-Apim-Subscription-Key", subscriptionKey)
            .retrieve()
            .body(ArchiveListing.class);
    if (listing == null || listing.archives().isEmpty()) {
      throw new IllegalStateException("ERCOT archive listing returned no documents");
    }
    long docId = listing.archives().get(0).docId();

    byte[] zip =
        client
            .get()
            .uri(archiveUrl + "?download=" + docId)
            .header("Authorization", "Bearer " + token)
            .header("Ocp-Apim-Subscription-Key", subscriptionKey)
            .retrieve()
            .body(byte[].class);
    if (zip == null) {
      throw new IllegalStateException("ERCOT archive download returned no content");
    }
    return IhlfCsvParser.parseNorthZoneMw(extractCsv(zip));
  }

  private static String extractCsv(byte[] zipBytes) {
    try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
      ZipEntry entry = zis.getNextEntry();
      if (entry == null) {
        throw new IllegalStateException("ERCOT archive zip had no entries");
      }
      return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read ERCOT archive zip", e);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ArchiveListing(List<Archive> archives) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Archive(long docId) {}
}
