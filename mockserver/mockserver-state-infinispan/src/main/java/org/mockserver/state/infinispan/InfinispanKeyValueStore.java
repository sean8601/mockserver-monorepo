package org.mockserver.state.infinispan;

import org.infinispan.Cache;
import org.mockserver.state.InvalidationListener;
import org.mockserver.state.KeyValueStore;
import org.mockserver.state.Versioned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * {@link KeyValueStore} backed by an Infinispan {@link Cache} in LOCAL
 * (non-clustered) mode. Versioning is managed explicitly via a
 * {@link VersionedWrapper} stored as the cache value, since LOCAL caches
 * do not support Infinispan's metadata versioning API.
 * <p>
 * CAS ({@link #compareAndSet}) uses {@code cache.replace(key, oldValue, newValue)}
 * which is atomic in Infinispan LOCAL mode, ensuring no lost updates.
 * <p>
 * Phase 2b: single-node only. Cluster pub/sub invalidation is deferred to
 * phase 2c (listeners are fired locally only).
 *
 * @param <V> the value type
 */
public class InfinispanKeyValueStore<V> implements KeyValueStore<V> {

    private static final Logger LOG = LoggerFactory.getLogger(InfinispanKeyValueStore.class);

    private final Cache<String, VersionedWrapper<V>> cache;
    private final List<InvalidationListener> listeners = new CopyOnWriteArrayList<>();

    public InfinispanKeyValueStore(Cache<String, VersionedWrapper<V>> cache) {
        this.cache = cache;
    }

    @Override
    public Optional<Versioned<V>> get(String key) {
        VersionedWrapper<V> wrapper = cache.get(key);
        if (wrapper == null) {
            return Optional.empty();
        }
        return Optional.of(new Versioned<>(wrapper.getValue(), wrapper.getVersion()));
    }

    private static final int MAX_CAS_RETRIES = 100;

    @Override
    public long put(String key, V value) {
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            VersionedWrapper<V> existing = cache.get(key);
            if (existing == null) {
                VersionedWrapper<V> newWrapper = new VersionedWrapper<>(value, 1L);
                VersionedWrapper<V> prev = cache.putIfAbsent(key, newWrapper);
                if (prev == null) {
                    fireChanged(key);
                    return 1L;
                }
                // Lost race — retry with the existing entry
                existing = prev;
            }
            long newVersion = existing.getVersion() + 1;
            VersionedWrapper<V> updated = new VersionedWrapper<>(value, newVersion);
            if (cache.replace(key, existing, updated)) {
                fireChanged(key);
                return newVersion;
            }
            // CAS failed — retry
        }
        throw new IllegalStateException("put() CAS retry limit exceeded for key: " + key);
    }

    @Override
    public boolean compareAndSet(String key, long expectedVersion, V value) {
        VersionedWrapper<V> existing = cache.get(key);
        if (existing == null || existing.getVersion() != expectedVersion) {
            return false;
        }
        long newVersion = expectedVersion + 1;
        VersionedWrapper<V> updated = new VersionedWrapper<>(value, newVersion);
        boolean swapped = cache.replace(key, existing, updated);
        if (swapped) {
            fireChanged(key);
        }
        return swapped;
    }

    @Override
    public boolean compareAndRemove(String key, long expectedVersion) {
        VersionedWrapper<V> existing = cache.get(key);
        if (existing == null || existing.getVersion() != expectedVersion) {
            return false;
        }
        boolean removed = cache.remove(key, existing);
        if (removed) {
            fireChanged(key);
        }
        return removed;
    }

    @Override
    public boolean remove(String key) {
        boolean removed = cache.remove(key) != null;
        if (removed) {
            fireChanged(key);
        }
        return removed;
    }

    @Override
    public Stream<Entry<V>> entries() {
        return cache.entrySet().stream().map(e -> {
            VersionedWrapper<V> w = e.getValue();
            return new Entry<>(e.getKey(), new Versioned<>(w.getValue(), w.getVersion()));
        });
    }

    @Override
    public int size() {
        return cache.size();
    }

    @Override
    public void clear() {
        cache.clear();
        fireCleared();
    }

    @Override
    public void addInvalidationListener(InvalidationListener listener) {
        listeners.add(listener);
    }

    private void fireChanged(String key) {
        for (InvalidationListener listener : listeners) {
            try {
                listener.onChanged(key);
            } catch (Exception e) {
                LOG.warn("invalidation listener threw on onChanged({})", key, e);
            }
        }
    }

    private void fireCleared() {
        for (InvalidationListener listener : listeners) {
            try {
                listener.onCleared();
            } catch (Exception e) {
                LOG.warn("invalidation listener threw on onCleared()", e);
            }
        }
    }
}
