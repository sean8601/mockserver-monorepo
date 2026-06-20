package org.mockserver.oidc;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * In-memory state backing the mock OIDC authorization-code flow.
 *
 * <p>Two pieces of state are held:
 * <ul>
 *   <li><b>Providers</b> — one {@link Provider} per generated OIDC provider, keyed by the
 *       {@code authorizePath}/{@code tokenPath} it serves. Each provider carries its configuration
 *       and the {@link OidcTokenMinter} (with its signing key pair) so tokens are minted per request,
 *       not pre-baked. The {@code /authorize} callback looks up its provider by the request path to
 *       bind the per-request context (redirect_uri, PKCE challenge, scope, nonce) to a newly issued
 *       authorization code.</li>
 *   <li><b>Codes</b> — one {@link AuthorizationCode} per issued authorization code, keyed by the
 *       opaque code string. The {@code /token} callback consumes the code to validate the
 *       {@code authorization_code} grant (redirect_uri match + optional PKCE), then mints the token
 *       response at request time. Codes are single-use and expire after {@link #CODE_TTL_MILLIS}.</li>
 * </ul>
 *
 * <p>This is a process-wide singleton (mirroring {@code GrpcHealthRegistry} and the other in-memory
 * registries) because the {@code /authorize} and {@code /token} class callbacks are instantiated
 * fresh per request and therefore cannot share instance state.
 */
public class OidcAuthorizationStore {

    /**
     * Lifetime of an unredeemed authorization code, in milliseconds. Matches real authorization
     * servers, where codes are short-lived (RFC 6749 §4.1.2 recommends ≤ 10 minutes; 60s is a typical
     * issuer default). A code older than this is treated as expired/not-found at {@code /token} and is
     * evicted, so the codes map cannot grow unbounded with never-redeemed codes.
     */
    static final long CODE_TTL_MILLIS = 60_000L;

    private static final OidcAuthorizationStore INSTANCE = new OidcAuthorizationStore();

    private final List<Provider> providers = new CopyOnWriteArrayList<>();
    private final Map<String, AuthorizationCode> codes = new ConcurrentHashMap<>();

    OidcAuthorizationStore() {
    }

    long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    public static OidcAuthorizationStore getInstance() {
        return INSTANCE;
    }

    /**
     * Registers (or replaces) the provider serving the given authorize/token paths. The most
     * recently registered provider for a path wins, so re-running {@code PUT /mockserver/oidc}
     * with the same paths refreshes the minted tokens.
     */
    public synchronized void registerProvider(Provider provider) {
        // remove any existing provider serving the same authorize/token path so the latest wins;
        // synchronized so the remove+add compound is atomic against concurrent PUT /mockserver/oidc
        // (otherwise two threads can both pass removeIf and leave duplicate stale entries).
        providers.removeIf(existing ->
            existing.authorizePath.equals(provider.authorizePath) || existing.tokenPath.equals(provider.tokenPath));
        providers.add(0, provider);
    }

    /**
     * Finds the provider serving the given authorize path, or {@code null} if none registered.
     */
    public Provider providerForAuthorizePath(String authorizePath) {
        for (Provider provider : providers) {
            if (provider.authorizePath.equals(authorizePath)) {
                return provider;
            }
        }
        return null;
    }

    /**
     * Finds the provider serving the given token path, or {@code null} if none registered.
     */
    public Provider providerForTokenPath(String tokenPath) {
        for (Provider provider : providers) {
            if (provider.tokenPath.equals(tokenPath)) {
                return provider;
            }
        }
        return null;
    }

    public void putCode(String code, AuthorizationCode authorizationCode) {
        long now = currentTimeMillis();
        authorizationCode.issuedAtMillis = now;
        // Opportunistically evict codes that expired before this write so the map cannot grow
        // unbounded with authorization codes that are never redeemed.
        codes.values().removeIf(existing -> isExpired(existing, now));
        codes.put(code, authorizationCode);
    }

    /**
     * Consumes (removes and returns) the authorization code, or {@code null} if unknown or expired.
     * Codes are single-use (removed on consume) and short-lived (older than {@link #CODE_TTL_MILLIS}
     * is treated as not-found), mirroring real authorization servers.
     */
    public AuthorizationCode consumeCode(String code) {
        if (code == null) {
            return null;
        }
        AuthorizationCode authorizationCode = codes.remove(code);
        if (authorizationCode == null || isExpired(authorizationCode, currentTimeMillis())) {
            return null;
        }
        return authorizationCode;
    }

    private static boolean isExpired(AuthorizationCode authorizationCode, long now) {
        return now - authorizationCode.issuedAtMillis > CODE_TTL_MILLIS;
    }

    /** Number of authorization codes currently retained (test visibility for TTL eviction). */
    int codeCount() {
        return codes.size();
    }

    public void reset() {
        providers.clear();
        codes.clear();
    }

    /**
     * Immutable description of a generated OIDC provider. Carries the live {@link OidcTokenMinter}
     * (configuration + signing key pair) so the {@code /token} callback can mint tokens at request
     * time — embedding per-request context such as the authorize {@code nonce} — rather than serving
     * a pre-baked token JSON. The minter's key pair is the same one published at the JWKS endpoint.
     */
    public static class Provider {
        final String authorizePath;
        final String tokenPath;
        final OidcProviderConfiguration config;
        final OidcTokenMinter tokenMinter;

        public Provider(String authorizePath, String tokenPath,
                        OidcProviderConfiguration config, OidcTokenMinter tokenMinter) {
            this.authorizePath = authorizePath;
            this.tokenPath = tokenPath;
            this.config = config;
            this.tokenMinter = tokenMinter;
        }
    }

    /**
     * Immutable record of an issued authorization code: the redirect_uri it was bound to, optional
     * PKCE challenge, the requested scope, and the {@code nonce} echoed from the authorize request.
     * The tokens are minted (not pre-baked) when the code is exchanged at {@code /token}.
     */
    public static class AuthorizationCode implements Serializable {
        final String redirectUri;
        final String codeChallenge;
        final String codeChallengeMethod;
        final String scope;
        final String nonce;
        /** Wall-clock time the code was stored ({@link #putCode}); drives TTL expiry. */
        volatile long issuedAtMillis;

        public AuthorizationCode(String redirectUri, String codeChallenge, String codeChallengeMethod,
                                 String scope, String nonce) {
            this.redirectUri = redirectUri;
            this.codeChallenge = codeChallenge;
            this.codeChallengeMethod = codeChallengeMethod;
            this.scope = scope;
            this.nonce = nonce;
        }
    }
}
