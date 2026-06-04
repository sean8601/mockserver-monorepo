package org.mockserver.state;

import org.mockserver.configuration.Configuration;

/**
 * Pluggable factory for the {@link StateBackend}.
 * <p>
 * Mirrors the {@link org.mockserver.mock.ExpectationStoreFactory} pattern:
 * a volatile-registry with a default in-memory implementation. An optional
 * clustered backend (phase 2b, in a separate module) can register a
 * {@link Factory} that returns a clustering-aware {@code StateBackend}
 * without {@code mockserver-core} depending on the data grid.
 * <p>
 * Thread-safety: the factory reference is {@code volatile}; register/reset
 * are expected at startup.
 */
public final class StateBackendFactory {

    /**
     * Creates a {@link StateBackend} for an {@code HttpState} instance.
     */
    @FunctionalInterface
    public interface Factory {
        StateBackend create(Configuration configuration);
    }

    private static final Factory DEFAULT_FACTORY =
        configuration -> new InMemoryStateBackend(configuration.maxExpectations());

    private static volatile Factory factory = DEFAULT_FACTORY;

    private StateBackendFactory() {
    }

    /**
     * Register a custom factory (e.g. a clustered backend). Passing
     * {@code null} resets to the default in-memory factory.
     */
    public static void register(Factory customFactory) {
        factory = (customFactory != null) ? customFactory : DEFAULT_FACTORY;
    }

    /**
     * Reset to the default in-memory factory (primarily for tests).
     */
    public static void resetToDefault() {
        factory = DEFAULT_FACTORY;
    }

    /**
     * @return {@code true} if a non-default factory is registered.
     */
    public static boolean isCustomFactoryRegistered() {
        return factory != DEFAULT_FACTORY;
    }

    /**
     * Create the state backend via the registered factory (default:
     * the standard in-memory backend).
     */
    public static StateBackend create(Configuration configuration) {
        return factory.create(configuration);
    }
}
