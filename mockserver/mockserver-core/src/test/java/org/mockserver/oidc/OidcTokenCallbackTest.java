package org.mockserver.oidc;

import com.nimbusds.jwt.SignedJWT;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.keys.AsymmetricKeyGenerator;
import org.mockserver.keys.AsymmetricKeyPair;
import org.mockserver.keys.AsymmetricKeyPairAlgorithm;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.HttpRequest.request;

/**
 * Behavioural tests for {@link OidcTokenCallback} — the mock OIDC {@code /token} endpoint that
 * completes the authorization-code, refresh, client-credentials and device-code grants.
 *
 * <p>These exercise the security-relevant logic through the public {@link OidcTokenCallback#handle}
 * contract against a registered provider in the process-wide {@link OidcAuthorizationStore}: PKCE
 * (S256 + plain) verification, redirect_uri binding, single-use authorization codes, device-code
 * polling/single-use/expiry, and token-endpoint client authentication (RFC 6749 §2.3 /
 * §5.2 {@code invalid_client}). Tokens are asserted via observable response status/body and by
 * parsing the issued {@code id_token} to confirm the {@code nonce} is echoed.
 *
 * <p>The store is a singleton, so each test resets it and registers its own provider; this is a
 * global-state-mutating test (sequential phase in the surefire split).
 */
public class OidcTokenCallbackTest {

    private static final String TOKEN_PATH = "/token";
    private static final String CLIENT_ID = "mock-client";
    private static final String CLIENT_SECRET = "mock-client-secret";

    private final OidcTokenCallback callback = new OidcTokenCallback();
    private OidcAuthorizationStore store;

    @Before
    public void setUp() {
        store = OidcAuthorizationStore.getInstance();
        store.reset();
    }

    @After
    public void tearDown() {
        store.reset();
    }

    // --- helpers -----------------------------------------------------------------------------

    /** Registers a provider (real signing key pair + minter) for the default config, with the given mutations. */
    private OidcProviderConfiguration registerProvider(java.util.function.Consumer<OidcProviderConfiguration> mutator) {
        OidcProviderConfiguration config = new OidcProviderConfiguration();
        if (mutator != null) {
            mutator.accept(config);
        }
        AsymmetricKeyPair keyPair =
            AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        OidcTokenMinter minter = new OidcTokenMinter(config, keyPair);
        store.registerProvider(new OidcAuthorizationStore.Provider(
            config.getAuthorizePath(), TOKEN_PATH, config.getDeviceAuthorizationPath(), config, minter));
        return config;
    }

    private HttpRequest tokenRequest(String body) {
        return request().withMethod("POST").withPath(TOKEN_PATH).withBody(body);
    }

    private static String s256(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void putCode(String code, String redirectUri, String challenge, String method, String nonce) {
        store.putCode(code, new OidcAuthorizationStore.AuthorizationCode(
            redirectUri, challenge, method, "openid profile", nonce));
    }

    private static String idTokenNonce(HttpResponse response) throws Exception {
        String body = response.getBodyAsString();
        // crude extraction of the id_token JWT from the JSON token response
        int idx = body.indexOf("\"id_token\"");
        assertThat("response must carry an id_token", idx, is(not(-1)));
        int start = body.indexOf('"', body.indexOf(':', idx) + 1) + 1;
        int end = body.indexOf('"', start);
        String idToken = body.substring(start, end);
        SignedJWT jwt = SignedJWT.parse(idToken);
        Object nonce = jwt.getJWTClaimsSet().getClaim("nonce");
        return nonce == null ? null : nonce.toString();
    }

    // --- authorization_code: PKCE S256 ------------------------------------------------------

    @Test
    public void authorizationCodeWithValidS256PkceSucceeds() {
        registerProvider(null);
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        putCode("code-1", "https://app/cb", s256(verifier), "S256", null);

        HttpResponse response = callback.handle(tokenRequest(
            "grant_type=authorization_code&code=code-1&redirect_uri=https%3A%2F%2Fapp%2Fcb&code_verifier=" + verifier));

        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), containsString("access_token"));
    }

    @Test
    public void authorizationCodeWithDefaultedS256MethodSucceeds() {
        // method null/empty defaults to S256
        registerProvider(null);
        String verifier = "abc123abc123abc123abc123abc123abc123abc123ab";
        putCode("code-1", "https://app/cb", s256(verifier), null, null);

        HttpResponse response = callback.handle(tokenRequest(
            "grant_type=authorization_code&code=code-1&redirect_uri=https%3A%2F%2Fapp%2Fcb&code_verifier=" + verifier));

        assertThat(response.getStatusCode(), is(200));
    }

    @Test
    public void authorizationCodeWithWrongS256VerifierIsRejected() {
        registerProvider(null);
        putCode("code-1", "https://app/cb", s256("the-real-verifier"), "S256", null);

        HttpResponse response = callback.handle(tokenRequest(
            "grant_type=authorization_code&code=code-1&redirect_uri=https%3A%2F%2Fapp%2Fcb&code_verifier=a-different-verifier"));

        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("invalid_grant"));
        assertThat(response.getBodyAsString(), containsString("PKCE"));
    }

    @Test
    public void authorizationCodeWithMissingPkceVerifierWhenChallengeBoundIsRejected() {
        registerProvider(null);
        putCode("code-1", "https://app/cb", s256("the-verifier"), "S256", null);

        HttpResponse response = callback.handle(tokenRequest(
            "grant_type=authorization_code&code=code-1&redirect_uri=https%3A%2F%2Fapp%2Fcb"));

        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("code_verifier is required for PKCE"));
    }

    // --- authorization_code: PKCE plain -----------------------------------------------------

    @Test
    public void authorizationCodeWithValidPlainPkceSucceeds() {
        registerProvider(null);
        putCode("code-1", "https://app/cb", "plain-challenge-value", "plain", null);

        HttpResponse response = callback.handle(tokenRequest(
            "grant_type=authorization_code&code=code-1&redirect_uri=https%3A%2F%2Fapp%2Fcb&code_verifier=plain-challenge-value"));

        assertThat(response.getStatusCode(), is(200));
    }

    @Test
    public void authorizationCodeWithWrongPlainPkceIsRejected() {
        registerProvider(null);
        putCode("code-1", "https://app/cb", "plain-challenge-value", "plain", null);

        HttpResponse response = callback.handle(tokenRequest(
            "grant_type=authorization_code&code=code-1&redirect_uri=https%3A%2F%2Fapp%2Fcb&code_verifier=not-the-challenge"));

        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("PKCE verification failed"));
    }

    // --- authorization_code: redirect_uri binding -------------------------------------------

    @Test
    public void authorizationCodeWithMismatchedRedirectUriIsRejected() {
        registerProvider(null);
        putCode("code-1", "https://app/cb", null, null, null);

        HttpResponse response = callback.handle(tokenRequest(
            "grant_type=authorization_code&code=code-1&redirect_uri=https%3A%2F%2Fevil%2Fcb"));

        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("redirect_uri does not match"));
    }

    @Test
    public void authorizationCodeWithMatchingRedirectUriSucceeds() {
        registerProvider(null);
        putCode("code-1", "https://app/cb", null, null, null);

        HttpResponse response = callback.handle(tokenRequest(
            "grant_type=authorization_code&code=code-1&redirect_uri=https%3A%2F%2Fapp%2Fcb"));

        assertThat(response.getStatusCode(), is(200));
    }

    // --- authorization_code: single-use -----------------------------------------------------

    @Test
    public void authorizationCodeCannotBeRedeemedTwice() {
        registerProvider(null);
        putCode("code-1", "https://app/cb", null, null, null);

        HttpResponse first = callback.handle(tokenRequest(
            "grant_type=authorization_code&code=code-1&redirect_uri=https%3A%2F%2Fapp%2Fcb"));
        assertThat("first redemption succeeds", first.getStatusCode(), is(200));

        HttpResponse second = callback.handle(tokenRequest(
            "grant_type=authorization_code&code=code-1&redirect_uri=https%3A%2F%2Fapp%2Fcb"));
        assertThat("a single-use code cannot be redeemed twice", second.getStatusCode(), is(400));
        assertThat(second.getBodyAsString(), containsString("invalid_grant"));
        assertThat(second.getBodyAsString(), containsString("already used"));
    }

    @Test
    public void unknownAuthorizationCodeIsRejected() {
        registerProvider(null);

        HttpResponse response = callback.handle(tokenRequest(
            "grant_type=authorization_code&code=never-issued&redirect_uri=https%3A%2F%2Fapp%2Fcb"));

        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("invalid_grant"));
    }

    // --- authorization_code: nonce echoed into id_token -------------------------------------

    @Test
    public void nonceFromAuthorizeIsEchoedIntoIdToken() throws Exception {
        registerProvider(null);
        putCode("code-1", "https://app/cb", null, null, "n-0S6_WzA2Mj");

        HttpResponse response = callback.handle(tokenRequest(
            "grant_type=authorization_code&code=code-1&redirect_uri=https%3A%2F%2Fapp%2Fcb"));

        assertThat(response.getStatusCode(), is(200));
        assertThat("the id_token must echo the authorize-request nonce", idTokenNonce(response), is("n-0S6_WzA2Mj"));
    }

    // --- non-authorization-code grants ------------------------------------------------------

    @Test
    public void clientCredentialsGrantMintsTokenAtRequestTime() {
        registerProvider(null);

        HttpResponse response = callback.handle(tokenRequest("grant_type=client_credentials&scope=openid"));

        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), containsString("access_token"));
    }

    @Test
    public void refreshTokenGrantReturnsAFreshRefreshToken() {
        registerProvider(null);

        HttpResponse response = callback.handle(tokenRequest("grant_type=refresh_token&scope=openid"));

        assertThat(response.getStatusCode(), is(200));
        assertThat("the refresh_token grant returns a refresh_token", response.getBodyAsString(),
            containsString("refresh_token"));
    }

    @Test
    public void unknownTokenPathYieldsEmptyJsonObject() {
        // no provider registered for this path
        HttpResponse response = callback.handle(
            request().withMethod("POST").withPath("/unregistered-token").withBody("grant_type=client_credentials"));

        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString().trim(), is("{}"));
    }

    // --- device-code grant (RFC 8628) -------------------------------------------------------

    @Test
    public void deviceCodeGrantApprovedImmediatelyMintsTokens() {
        registerProvider(null);
        store.putDeviceCode("dev-1", new OidcAuthorizationStore.DeviceCode("USER-CODE", "openid", 0));

        HttpResponse response = callback.handle(tokenRequest(
            "grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=dev-1"));

        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), containsString("access_token"));
    }

    @Test
    public void deviceCodeGrantReturnsAuthorizationPendingWhileNotYetApproved() {
        registerProvider(null);
        store.putDeviceCode("dev-1", new OidcAuthorizationStore.DeviceCode("USER-CODE", "openid", 2));

        HttpResponse first = callback.handle(tokenRequest(
            "grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=dev-1"));
        assertThat(first.getStatusCode(), is(400));
        assertThat(first.getBodyAsString(), containsString("authorization_pending"));

        HttpResponse second = callback.handle(tokenRequest(
            "grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=dev-1"));
        assertThat(second.getBodyAsString(), containsString("authorization_pending"));

        // third poll: pendingPolls now exhausted -> approved, tokens minted
        HttpResponse third = callback.handle(tokenRequest(
            "grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=dev-1"));
        assertThat(third.getStatusCode(), is(200));
        assertThat(third.getBodyAsString(), containsString("access_token"));
    }

    @Test
    public void deviceCodeIsSingleUseAfterApproval() {
        registerProvider(null);
        store.putDeviceCode("dev-1", new OidcAuthorizationStore.DeviceCode("USER-CODE", "openid", 0));

        HttpResponse first = callback.handle(tokenRequest(
            "grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=dev-1"));
        assertThat(first.getStatusCode(), is(200));

        HttpResponse second = callback.handle(tokenRequest(
            "grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=dev-1"));
        assertThat("an approved device code is consumed (single-use)", second.getStatusCode(), is(400));
        assertThat(second.getBodyAsString(), containsString("expired_token"));
    }

    @Test
    public void unknownDeviceCodeYieldsExpiredToken() {
        registerProvider(null);

        HttpResponse response = callback.handle(tokenRequest(
            "grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=never-issued"));

        assertThat(response.getStatusCode(), is(400));
        assertThat(response.getBodyAsString(), containsString("expired_token"));
    }

    // --- token-endpoint client authentication (RFC 6749 §2.3 / §5.2) ------------------------

    @Test
    public void enforcedClientAuthAcceptsClientSecretPost() {
        registerProvider(c -> c.setEnforceClientAuthentication(true));

        HttpResponse response = callback.handle(tokenRequest(
            "grant_type=client_credentials&client_id=" + CLIENT_ID + "&client_secret=" + CLIENT_SECRET));

        assertThat(response.getStatusCode(), is(200));
    }

    @Test
    public void enforcedClientAuthAcceptsClientSecretBasic() {
        registerProvider(c -> c.setEnforceClientAuthentication(true));
        String basic = Base64.getEncoder().encodeToString(
            (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));

        HttpResponse response = callback.handle(
            tokenRequest("grant_type=client_credentials").withHeader("Authorization", "Basic " + basic));

        assertThat(response.getStatusCode(), is(200));
    }

    @Test
    public void enforcedClientAuthRejectsWrongSecretWithInvalidClient() {
        registerProvider(c -> c.setEnforceClientAuthentication(true));

        HttpResponse response = callback.handle(tokenRequest(
            "grant_type=client_credentials&client_id=" + CLIENT_ID + "&client_secret=wrong-secret"));

        assertThat("wrong credential is RFC 6749 §5.2 invalid_client (401)", response.getStatusCode(), is(401));
        assertThat(response.getBodyAsString(), containsString("invalid_client"));
        assertThat(response.getFirstHeader("WWW-Authenticate"), is("Basic"));
    }

    @Test
    public void enforcedClientAuthRejectsMissingCredentialsWithInvalidClient() {
        registerProvider(c -> c.setEnforceClientAuthentication(true));

        HttpResponse response = callback.handle(tokenRequest("grant_type=client_credentials"));

        assertThat(response.getStatusCode(), is(401));
        assertThat(response.getBodyAsString(), containsString("invalid_client"));
    }

    @Test
    public void clientAuthNotEnforcedAllowsMissingCredentials() {
        registerProvider(null); // enforceClientAuthentication defaults false

        HttpResponse response = callback.handle(tokenRequest("grant_type=client_credentials"));

        assertThat(response.getStatusCode(), is(200));
    }

    @Test
    public void enforcedClientAuthBlocksAuthorizationCodeGrantWithoutCredentials() {
        // client authentication is checked BEFORE the grant is processed, so a wrong/absent secret
        // must reject even a fully-valid authorization-code exchange.
        registerProvider(c -> c.setEnforceClientAuthentication(true));
        putCode("code-1", "https://app/cb", null, null, null);

        HttpResponse response = callback.handle(tokenRequest(
            "grant_type=authorization_code&code=code-1&redirect_uri=https%3A%2F%2Fapp%2Fcb"));

        assertThat(response.getStatusCode(), is(401));
        assertThat("the single-use code must NOT have been consumed when client auth fails first",
            store.consumeCode("code-1"), is(not(nullValue())));
    }

    @Test
    public void malformedBasicAuthHeaderYieldsInvalidClient() {
        registerProvider(c -> c.setEnforceClientAuthentication(true));

        HttpResponse response = callback.handle(
            tokenRequest("grant_type=client_credentials")
                .withHeader("Authorization", "Basic !!!not-base64!!!"));

        assertThat(response.getStatusCode(), is(401));
        assertThat(response.getBodyAsString(), containsString("invalid_client"));
    }

    @Test
    public void unspecifiedGrantTypeStillMintsAToken() {
        // a request without a grant_type falls through the non-authorization-code branch
        registerProvider(null);

        HttpResponse response = callback.handle(tokenRequest("scope=openid"));

        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), containsString("access_token"));
    }
}
