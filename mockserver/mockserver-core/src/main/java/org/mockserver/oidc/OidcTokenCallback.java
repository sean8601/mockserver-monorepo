package org.mockserver.oidc;

import org.mockserver.mock.action.ExpectationResponseCallback;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.mockserver.model.HttpResponse.response;

/**
 * Mock OIDC {@code /token} endpoint.
 *
 * <p>Backward compatible: for {@code client_credentials}, {@code refresh_token}, or any request
 * without an {@code authorization_code} grant, it returns the same pre-minted token response the
 * static {@code /token} expectation previously served.
 *
 * <p>For {@code grant_type=authorization_code} it completes the authorization-code flow: it consumes
 * the single-use {@code code} issued by {@link OidcAuthorizationCodeCallback}, validates the
 * {@code redirect_uri} matches the one bound at {@code /authorize}, validates the PKCE
 * {@code code_verifier} against the stored {@code code_challenge} (when one was supplied), and
 * returns the associated token response.
 */
public class OidcTokenCallback implements ExpectationResponseCallback {

    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    @Override
    public HttpResponse handle(HttpRequest request) {
        Map<String, String> form = parseFormBody(request.getBodyAsString());
        String grantType = form.get("grant_type");

        OidcAuthorizationStore store = OidcAuthorizationStore.getInstance();
        OidcAuthorizationStore.Provider provider = store.providerForTokenPath(request.getPath().getValue());

        if (!"authorization_code".equals(grantType)) {
            // client_credentials, refresh_token, or unspecified — preserve existing behaviour
            String body = provider != null ? provider.tokenResponseJson : "{}";
            return response()
                .withStatusCode(200)
                .withHeader("content-type", JSON_CONTENT_TYPE)
                .withBody(body);
        }

        String code = form.get("code");
        OidcAuthorizationStore.AuthorizationCode authorizationCode = store.consumeCode(code);
        if (authorizationCode == null) {
            return tokenError("invalid_grant", "authorization code is invalid, expired, or already used");
        }

        String redirectUri = form.get("redirect_uri");
        if (authorizationCode.redirectUri != null && !authorizationCode.redirectUri.equals(redirectUri)) {
            return tokenError("invalid_grant", "redirect_uri does not match the authorization request");
        }

        if (authorizationCode.codeChallenge != null) {
            String codeVerifier = form.get("code_verifier");
            if (codeVerifier == null || codeVerifier.isEmpty()) {
                return tokenError("invalid_grant", "code_verifier is required for PKCE");
            }
            if (!verifyPkce(authorizationCode.codeChallenge, authorizationCode.codeChallengeMethod, codeVerifier)) {
                return tokenError("invalid_grant", "PKCE verification failed");
            }
        }

        return response()
            .withStatusCode(200)
            .withHeader("content-type", JSON_CONTENT_TYPE)
            .withBody(authorizationCode.tokenResponseJson);
    }

    private HttpResponse tokenError(String error, String description) {
        return response()
            .withStatusCode(400)
            .withHeader("content-type", JSON_CONTENT_TYPE)
            .withBody("{\"error\":\"" + error + "\",\"error_description\":\"" + description + "\"}");
    }

    /**
     * Verifies a PKCE code_verifier against the stored code_challenge. Defaults to S256 when the
     * method is absent; supports the {@code plain} method as well.
     */
    private static boolean verifyPkce(String codeChallenge, String codeChallengeMethod, String codeVerifier) {
        String method = (codeChallengeMethod == null || codeChallengeMethod.isEmpty()) ? "S256" : codeChallengeMethod;
        if ("plain".equalsIgnoreCase(method)) {
            return codeChallenge.equals(codeVerifier);
        }
        // S256: BASE64URL-ENCODE(SHA256(ASCII(code_verifier)))
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            return computed.equals(codeChallenge);
        } catch (NoSuchAlgorithmException e) {
            return false;
        }
    }

    private static Map<String, String> parseFormBody(String body) {
        Map<String, String> form = new HashMap<>();
        if (body == null || body.isEmpty()) {
            return form;
        }
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                form.put(urlDecode(pair), "");
            } else {
                form.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
            }
        }
        return form;
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
