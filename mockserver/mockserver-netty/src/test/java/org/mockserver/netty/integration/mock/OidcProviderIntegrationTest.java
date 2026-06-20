package org.mockserver.netty.integration.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.mock.Expectation;
import org.mockserver.oidc.OidcAuthorizationStore;
import org.mockserver.oidc.OidcProviderConfiguration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end integration test for the one-call OIDC provider over real HTTP:
 * <ol>
 *   <li>{@code PUT /mockserver/oidc} via the typed client ({@link ClientAndServer#mockOpenIdProvider}),</li>
 *   <li>fetch the discovery document and the JWKS over HTTP,</li>
 *   <li>run the full authorization_code + PKCE(S256) + nonce flow over HTTP,</li>
 *   <li>verify the issued id_token's signature against the fetched JWKS using nimbus-jose-jwt,</li>
 *   <li>assert the nonce round-trips into the id_token.</li>
 * </ol>
 */
public class OidcProviderIntegrationTest {

    private static ClientAndServer mockServer;
    private static int port;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    @BeforeClass
    public static void startServer() {
        mockServer = ClientAndServer.startClientAndServer(0);
        port = mockServer.getLocalPort();
    }

    @AfterClass
    public static void stopServer() {
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    @Before
    public void reset() {
        mockServer.reset();
        OidcAuthorizationStore.getInstance().reset();
    }

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    public void typedClientMockOpenIdProviderReturnsExpectations() {
        Expectation[] expectations = mockServer.mockOpenIdProvider(
            new OidcProviderConfiguration().setIssuer(base()));
        assertThat(expectations.length, is(8));
    }

    @Test
    public void fullAuthorizationCodePkceNonceFlowOverHttp() throws Exception {
        // 1. one-call OIDC provider, issuer pointing at this running server
        mockServer.mockOpenIdProvider(new OidcProviderConfiguration()
            .setIssuer(base())
            .setClientId("integration-client"));

        // 2. discovery
        JsonNode discovery = objectMapper.readTree(get("/.well-known/openid-configuration"));
        String authorizeEndpoint = discovery.get("authorization_endpoint").asText();
        String tokenEndpoint = discovery.get("token_endpoint").asText();
        String jwksUri = discovery.get("jwks_uri").asText();
        assertThat(discovery.get("issuer").asText(), is(base()));

        // 3. JWKS
        JWKSet jwkSet = JWKSet.parse(get(pathOf(jwksUri)));
        JWSVerifier verifier = new RSASSAVerifier((RSAKey) jwkSet.getKeys().get(0));

        // 4. authorize with PKCE + nonce
        String codeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String codeChallenge = s256(codeVerifier);
        String nonce = "nonce-12345";
        String redirectUri = "https://app.example.com/callback";

        String authorizeQuery = "response_type=code"
            + "&client_id=integration-client"
            + "&redirect_uri=" + enc(redirectUri)
            + "&scope=" + enc("openid profile email")
            + "&state=xyz"
            + "&nonce=" + nonce
            + "&code_challenge=" + codeChallenge
            + "&code_challenge_method=S256";

        HttpResponse<String> authorizeResponse = httpClient.send(
            HttpRequest.newBuilder(URI.create(base() + pathOf(authorizeEndpoint) + "?" + authorizeQuery)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(authorizeResponse.statusCode(), is(302));
        String location = authorizeResponse.headers().firstValue("location").orElse(null);
        assertThat(location, is(notNullValue()));
        String code = extractCode(location);

        // 5. token exchange
        String tokenBody = "grant_type=authorization_code"
            + "&code=" + code
            + "&redirect_uri=" + enc(redirectUri)
            + "&code_verifier=" + codeVerifier;
        HttpResponse<String> tokenResponse = httpClient.send(
            HttpRequest.newBuilder(URI.create(base() + pathOf(tokenEndpoint)))
                .header("content-type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(tokenBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(tokenResponse.statusCode(), is(200));

        JsonNode tokens = objectMapper.readTree(tokenResponse.body());
        assertTrue(tokens.has("access_token"));
        assertTrue(tokens.has("id_token"));
        assertTrue("authorization_code grant returns a refresh_token", tokens.has("refresh_token"));

        // 6. verify id_token signature against the fetched JWKS and assert nonce + aud
        SignedJWT idToken = SignedJWT.parse(tokens.get("id_token").asText());
        assertTrue("id_token must verify against the published JWKS", idToken.verify(verifier));
        assertThat(idToken.getJWTClaimsSet().getStringClaim("nonce"), is(nonce));
        assertThat(idToken.getJWTClaimsSet().getAudience().get(0), is("integration-client"));

        // access_token also verifies
        SignedJWT accessToken = SignedJWT.parse(tokens.get("access_token").asText());
        assertTrue(accessToken.verify(verifier));
    }

    // --- helpers ---

    private String get(String path) throws Exception {
        HttpResponse<String> response = httpClient.send(
            HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat("GET " + path + " should return 200", response.statusCode(), is(200));
        return response.body();
    }

    private static String pathOf(String urlOrPath) {
        if (urlOrPath.startsWith("http")) {
            return URI.create(urlOrPath).getPath();
        }
        return urlOrPath;
    }

    private static String extractCode(String location) {
        for (String pair : location.substring(location.indexOf('?') + 1).split("&")) {
            if (pair.startsWith("code=")) {
                return pair.substring("code=".length());
            }
        }
        throw new AssertionError("no code in redirect location: " + location);
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String s256(String codeVerifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }
}
