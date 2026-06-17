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
 *       {@code authorizePath}/{@code tokenPath} it serves. The {@code /authorize} callback looks
 *       up its provider by the request path to discover the pre-minted token response to bake into
 *       a newly issued authorization code.</li>
 *   <li><b>Codes</b> — one {@link AuthorizationCode} per issued authorization code, keyed by the
 *       opaque code string. The {@code /token} callback consumes the code to validate the
 *       {@code authorization_code} grant (redirect_uri match + optional PKCE) and return the
 *       associated token response.</li>
 * </ul>
 *
 * <p>This is a process-wide singleton (mirroring {@code GrpcHealthRegistry} and the other in-memory
 * registries) because the {@code /authorize} and {@code /token} class callbacks are instantiated
 * fresh per request and therefore cannot share instance state.
 */
public class OidcAuthorizationStore {

    private static final OidcAuthorizationStore INSTANCE = new OidcAuthorizationStore();

    private final List<Provider> providers = new CopyOnWriteArrayList<>();
    private final Map<String, AuthorizationCode> codes = new ConcurrentHashMap<>();

    OidcAuthorizationStore() {
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
        codes.put(code, authorizationCode);
    }

    /**
     * Consumes (removes and returns) the authorization code, or {@code null} if unknown. Codes are
     * single-use, mirroring real authorization servers.
     */
    public AuthorizationCode consumeCode(String code) {
        if (code == null) {
            return null;
        }
        return codes.remove(code);
    }

    public void reset() {
        providers.clear();
        codes.clear();
    }

    /**
     * Immutable description of a generated OIDC provider, holding the pre-minted token response so
     * the authorization-code exchange can hand back exactly the tokens {@code /token} would mint.
     */
    public static class Provider {
        final String authorizePath;
        final String tokenPath;
        final String tokenResponseJson;

        public Provider(String authorizePath, String tokenPath, String tokenResponseJson) {
            this.authorizePath = authorizePath;
            this.tokenPath = tokenPath;
            this.tokenResponseJson = tokenResponseJson;
        }
    }

    /**
     * Immutable record of an issued authorization code: the redirect_uri it was bound to, optional
     * PKCE challenge, and the token response to return when the code is exchanged.
     */
    public static class AuthorizationCode implements Serializable {
        final String redirectUri;
        final String codeChallenge;
        final String codeChallengeMethod;
        final String tokenResponseJson;

        public AuthorizationCode(String redirectUri, String codeChallenge, String codeChallengeMethod, String tokenResponseJson) {
            this.redirectUri = redirectUri;
            this.codeChallenge = codeChallenge;
            this.codeChallengeMethod = codeChallengeMethod;
            this.tokenResponseJson = tokenResponseJson;
        }
    }
}
