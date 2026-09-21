package io.arcnode.mockderms.dispatch;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * This service's own identity when acting as the utility/aggregator side of {@code POST
 * /der-events}. der-control-api's own {@code ClientIdentity} never does a real trust/signature
 * check itself (that's the nginx gateway's job in a real deployment, per its own javadoc) — it only
 * derives an LFDI from whatever cert arrives in {@code X-SSL-Client-Cert}. Calling der-control-api
 * directly (bypassing that gateway, same as every other integration test in that repo) means
 * presenting a self-asserted identity, not proving possession of a private key — matching
 * der-control-api's own {@code TestCerts} fixture exactly, just for main code instead of tests,
 * since this service really does send this header at runtime, not only in tests.
 *
 * @see <a href="https://gitlab.com/arcnode-io/ems-der-control-api">ems-der-control-api's own
 *     TestCerts.java for the identical generation method</a>
 */
public final class MockUtilityIdentity {

  // keytool -genkeypair -alias mock-derms -keyalg RSA -keysize 2048 -validity 3650
  //   -dname "CN=mock-derms-dispatch-api,O=arcnode" -storetype PKCS12
  private static final String PEM =
      """
      -----BEGIN CERTIFICATE-----
      MIIDCzCCAfOgAwIBAgIILASzDbi6B/kwDQYJKoZIhvcNAQELBQAwNDEQMA4GA1UE
      ChMHYXJjbm9kZTEgMB4GA1UEAxMXbW9jay1kZXJtcy1kaXNwYXRjaC1hcGkwHhcN
      MjYwOTIxMDI0MTA2WhcNMzYwOTE4MDI0MTA2WjA0MRAwDgYDVQQKEwdhcmNub2Rl
      MSAwHgYDVQQDExdtb2NrLWRlcm1zLWRpc3BhdGNoLWFwaTCCASIwDQYJKoZIhvcN
      AQEBBQADggEPADCCAQoCggEBALNRHCcZ1ACUDsJCQ6F8NEoduJPBqNvk2WcsMe9F
      jUFzDllh/X7QDaiwBxzxVg9zTOtMfrl0ge/M4v8r4Fl1LNXtdjoG/Aqp0579IbH1
      R6TpoCgxnqNfywE+5EfhwQNwRqfMCa0VlO2OvDN42WK8jx08CaH0R3ZRMGvUEk/p
      pryXmsPe5cNExhx39nCaVpEuDJs0tdaiWlGVFZGmzfkj1eHLj1AxMDV7wWGImCPR
      8NdO325HeIA5IF5zyKiLdoZl4s1WiL5khkMlYqbUiOp7hw1cWHcKKK11zcU2H6zJ
      y3fqXrjvepEh5CyPOMz1YMNnj8wv90ms6wUidhZm4ypHdecCAwEAAaMhMB8wHQYD
      VR0OBBYEFMW5K+mjn/y5MDt+lh7t+/0uJr1yMA0GCSqGSIb3DQEBCwUAA4IBAQCm
      FvHUVqY+kfHC/nQRnwE/hWKzPJMXvUoVB4+RoZV2Pr68F47H20nFT9AKQrFrhKW6
      6DS0QjGl3EOrDihphz5A8aX9uBVqGpqTjE8ofb9/vic5l4+3ggKubb/6gq5Nb4vT
      WfoBrmwxiaGf/RzxUVcyrhiNrMb7dPrckJBY864rnbQq4kjK91PqlmCgo2BNvL3n
      z3V/seBkeBAQeHP/8RAU0XBiA1a5zdlLGyOsK9FIQB5JrYkfOnOdQYRC+202GbAl
      SNvwBee+mzaDAFEjH5mm8PhKhbZlFomCnOnwxbfTFzV3/5pJrWs79HxXXYnVkrqa
      v3KAtwO1p0mMPAHGV+tB
      -----END CERTIFICATE-----
      """;

  /**
   * LFDI this derives to on der-control-api's side — independently computed, not this repo's code.
   */
  public static final String LFDI = "0c6937d4d21a5675a51092e51d9e54e19bff55ae";

  /**
   * {@code X-SSL-Client-Cert} header value — PEM as nginx's {@code $ssl_client_escaped_cert} would
   * present it.
   */
  public static final String HEADER_VALUE = URLEncoder.encode(PEM, StandardCharsets.UTF_8);

  private MockUtilityIdentity() {}
}
