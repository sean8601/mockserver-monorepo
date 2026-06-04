package org.mockserver.state;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.uuid.UUIDService;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default in-memory {@link StateBackend} that wraps today's exact data
 * structures for zero behaviour change. Every store is node-local;
 * there is no network I/O or clustering in this implementation.
 */
public class InMemoryStateBackend implements StateBackend {

    private final InMemoryExpectationKeyValueStore expectations;
    private final InMemoryKeyValueStore<String> scenarioStates;
    private final ConcurrentHashMap<String, KeyValueStore<ObjectNode>> crudStores;
    private final InMemoryBlobStore blobStore;
    private final String nodeId;
    private final List<InvalidationListener> listeners = new CopyOnWriteArrayList<>();

    public InMemoryStateBackend(int maxExpectations) {
        this.expectations = new InMemoryExpectationKeyValueStore(maxExpectations);
        this.scenarioStates = new InMemoryKeyValueStore<>();
        this.crudStores = new ConcurrentHashMap<>();
        this.blobStore = new InMemoryBlobStore();
        this.nodeId = UUIDService.getUUID();
    }

    @Override
    public KeyValueStore<ExpectationEntry> expectations() {
        return expectations;
    }

    /**
     * Returns the expectation store cast to its concrete type, so
     * callers that need the sorted-list or queue API can access it.
     */
    public InMemoryExpectationKeyValueStore expectationStore() {
        return expectations;
    }

    @Override
    public KeyValueStore<String> scenarioStates() {
        return scenarioStates;
    }

    @Override
    public KeyValueStore<ObjectNode> crudEntities(String namespace) {
        return crudStores.computeIfAbsent(namespace, ns -> new InMemoryKeyValueStore<>());
    }

    @Override
    public BlobStore blobs() {
        return blobStore;
    }

    @Override
    public void addInvalidationListener(InvalidationListener listener) {
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
        // no-op for in-memory
    }
}
