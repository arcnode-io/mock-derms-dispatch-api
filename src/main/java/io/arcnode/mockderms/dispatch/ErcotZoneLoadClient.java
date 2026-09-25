package io.arcnode.mockderms.dispatch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.arcnode.mockderms.Config;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalDouble;
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
 * cadence, confirmed live), queried via {@link ErcotTokenClient}.
 *
 * <p>Returns no value on any failure (missing credentials, auth failure, rate limit, network error)
 * and never substitutes a stand-in reading. A stand-in would land somewhere relative to {@code
 * zoneStressThresholdMw} and so would silently decide whether the zone counts as stressed, which
 * moves the dispatch threshold on fabricated data. {@link ZoneStressTracker} owns what absence
 * means.
 *
 * <p>Called directly only by {@link ZoneStressTracker}, which caches this raw reading to IHLF's own
 * ~5-minute refresh cadence and debounces it before it ever reaches {@link TriggerEvaluator} — this
 * class itself does no caching or debouncing. SME-reviewed and approved: the local DLR trigger
 * stays sole authority on whether to fire, this signal can only make the margin more conservative,
 * never independently cause a dispatch. The threshold/boost numbers themselves are arbitrary, not a
 * placeholder awaiting review — an IEEE 738 sizing attempt confirmed no physical derivation exists
 * (the weather effect the zone signal would correct for is already in the live DLR reading; adding
 * it again would double-count it), and no empirical one does either until real local loading
 * telemetry exists to accumulate paired history against.
 */
@Component
public class ErcotZoneLoadClient {

  private static final Logger LOG = LoggerFactory.getLogger(ErcotZoneLoadClient.class);

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

  /** Current North-zone load, MW — real IHLF data, or empty when ERCOT could not be read. */
  public OptionalDouble currentNorthZoneLoadMw() {
    try {
      return OptionalDouble.of(fetchReal());
    } catch (RuntimeException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("⚠️ ERCOT IHLF call failed ({}) — no North-zone reading this cycle", e.toString());
      }
      return OptionalDouble.empty();
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
