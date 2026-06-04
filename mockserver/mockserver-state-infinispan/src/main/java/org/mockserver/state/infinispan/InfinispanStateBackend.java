package org.mockserver.state.infinispan;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.StorageType;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.eviction.EvictionStrategy;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.mockserver.state.*;
import org.mockserver.uuid.UUIDService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Infinispan-backed {@link StateBackend} using LOCAL (non-clustered) caches.
 * <p>
 * Phase 2b: single-node, no JGroups network transport, no cluster
 * listeners. The invalidation listener fires locally only; full cluster
 * pub/sub invalidation is deferred to phase 2c.
 * <p>
 * Configuration:
 * <ul>
 *   <li>Global: non-clustered (no JGroups transport), allow Java
 *       serialization for all packages (required for storing
 *       {@link VersionedWrapper} and domain objects).</li>
 *   <li>Expectations cache: bounded by {@code maxExpectations} using
 *       Infinispan's {@code maxCount} eviction with REMOVE strategy.
 *       Eviction is APPROXIMATE in a clustered deployment (each node
 *       evicts locally); in-memory backend keeps exact ordering.</li>
 *   <li>Scenario/CRUD/blob caches: unbounded (same as in-memory).</li>
 * </ul>
 * <p>
 * Serialization: uses Java serialization (the simplest and least-fragile
 * approach for phase 2b). The allow-list is configured to permit all
 * packages ({@code ".*"}) since this is an embedded, non-networked cache.
 * Phase 2c may switch to ProtoStream for clustered serialization if needed.
 */
public class InfinispanStateBackend implements StateBackend {

    private static final Logger LOG = LoggerFactory.getLogger(InfinispanStateBackend.class);

    private static final String EXPECTATIONS_CACHE = "expectations";
    private static final String SCENARIO_STATES_CACHE = "scenarioStates";
    private static final String BLOBS_CACHE = "blobs";
    private static final String CRUD_CACHE_PREFIX = "crud-";

    private final EmbeddedCacheManager cacheManager;
    private final InfinispanKeyValueStore<ExpectationEntry> expectations;
    private final InfinispanKeyValueStore<String> scenarioStates;
    private final ConcurrentHashMap<String, KeyValueStore<ObjectNode>> crudStores;
    private final InfinispanBlobStore blobStore;
    private final String nodeId;
    private final List<InvalidationListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Creates an Infinispan state backend with LOCAL (non-clustered) caches.
     *
     * @param maxExpectations the maximum number of expectations (used for
     *                        Infinispan eviction maxCount)
     */
    public InfinispanStateBackend(int maxExpectations) {
        this.nodeId = UUIDService.getUUID();
        this.cacheManager = createCacheManager(maxExpectations);

        @SuppressWarnings("unchecked")
        Cache<String, VersionedWrapper<ExpectationEntry>> expCache =
            (Cache<String, VersionedWrapper<ExpectationEntry>>) (Cache<?, ?>) cacheManager.getCache(EXPECTATIONS_CACHE);
        this.expectations = new InfinispanKeyValueStore<>(expCache);

        @SuppressWarnings("unchecked")
        Cache<String, VersionedWrapper<String>> scenarioCache =
            (Cache<String, VersionedWrapper<String>>) (Cache<?, ?>) cacheManager.getCache(SCENARIO_STATES_CACHE);
        this.scenarioStates = new InfinispanKeyValueStore<>(scenarioCache);

        this.blobStore = new InfinispanBlobStore(cacheManager.getCache(BLOBS_CACHE));
        this.crudStores = new ConcurrentHashMap<>();

        LOG.info("InfinispanStateBackend started (LOCAL mode, nodeId={})", nodeId);
    }

    private EmbeddedCacheManager createCacheManager(int maxExpectations) {
        GlobalConfigurationBuilder global = new GlobalConfigurationBuilder();
        // Non-clustered: no JGroups transport (LOCAL caches only)
        global.nonClusteredDefault();
        // Allow Java serialization for all packages — this is an embedded,
        // non-networked cache, so deserialization attacks are not a concern.
        // Phase 2c (clustered) may tighten this to specific packages or
        // switch to ProtoStream.
        // TODO(phase-2c): replace the ".*" allow-list with an explicit package allow-list
        // or ProtoStream BEFORE enabling clustering — P0 security gate.
        global.serialization().allowList().addRegexp(".*");

        // Expectations cache: bounded by maxExpectations
        ConfigurationBuilder expectationsConfig = new ConfigurationBuilder();
        expectationsConfig
            .memory()
                .storage(StorageType.HEAP)
                .maxCount(maxExpectations)
                .whenFull(EvictionStrategy.REMOVE);

        // Unbounded caches for scenario states, CRUD entities, blobs
        ConfigurationBuilder unboundedConfig = new ConfigurationBuilder();
        unboundedConfig.memory().storage(StorageType.HEAP);

        DefaultCacheManager manager = new DefaultCacheManager(global.build());
        manager.defineConfiguration(EXPECTATIONS_CACHE, expectationsConfig.build());
        manager.defineConfiguration(SCENARIO_STATES_CACHE, unboundedConfig.build());
        manager.defineConfiguration(BLOBS_CACHE, unboundedConfig.build());

        return manager;
    }

    @Override
    public KeyValueStore<ExpectationEntry> expectations() {
        return expectations;
    }

    @Override
    public KeyValueStore<String> scenarioStates() {
        return scenarioStates;
    }

    @Override
    public KeyValueStore<ObjectNode> crudEntities(String namespace) {
        return crudStores.computeIfAbsent(namespace, ns -> {
            String cacheName = CRUD_CACHE_PREFIX + ns;
            ConfigurationBuilder crudConfig = new ConfigurationBuilder();
            crudConfig.memory().storage(StorageType.HEAP);
            cacheManager.defineConfiguration(cacheName, crudConfig.build());

            @SuppressWarnings("unchecked")
            Cache<String, VersionedWrapper<ObjectNode>> crudCache =
                (Cache<String, VersionedWrapper<ObjectNode>>) (Cache<?, ?>) cacheManager.getCache(cacheName);
            return new InfinispanKeyValueStore<>(crudCache);
        });
    }

    @Override
    public BlobStore blobs() {
        return blobStore;
    }

    @Override
    public void addInvalidationListener(InvalidationListener listener) {
        // Phase 2b: wire listeners to the local KV stores only.
        // Full cluster pub/sub invalidation (Infinispan @Listener) is
        // deferred to phase 2c.
        listeners.add(listener);
        expectations.addInvalidationListener(listener);
        scenarioStates.addInvalidationListener(listener);
    }

    @Override
    public String nodeId() {
        return nodeId;
    }

    @Override
    public void close() {
        LOG.info("stopping InfinispanStateBackend (nodeId={})", nodeId);
        if (cacheManager != null) {
            cacheManager.stop();
        }
    }
}
