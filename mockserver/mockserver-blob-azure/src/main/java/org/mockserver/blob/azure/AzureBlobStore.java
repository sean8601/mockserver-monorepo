package org.mockserver.blob.azure;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobListDetails;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import org.mockserver.state.Blob;
import org.mockserver.state.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * {@link BlobStore} implementation backed by Azure Blob Storage.
 * Blob keys are mapped to Azure blob names with an optional
 * configurable prefix.
 * <p>
 * Metadata is stored as Azure blob metadata (custom key-value pairs
 * on the blob itself).
 * <p>
 * Thread-safety: {@link BlobServiceClient} is thread-safe; this class
 * adds no mutable state beyond the injected client and configuration.
 */
public class AzureBlobStore implements BlobStore {

    private static final Logger LOG = LoggerFactory.getLogger(AzureBlobStore.class);

    private final BlobContainerClient containerClient;
    private final String keyPrefix;

    /**
     * Creates an Azure blob store.
     *
     * @param containerClient the Azure container client (caller owns lifecycle)
     * @param keyPrefix       optional key prefix; empty string for no prefix
     */
    public AzureBlobStore(BlobContainerClient containerClient, String keyPrefix) {
        this.containerClient = containerClient;
        this.keyPrefix = keyPrefix != null ? keyPrefix : "";
    }

    private String toAzureName(String key) {
        return keyPrefix + key;
    }

    private String fromAzureName(String azureName) {
        if (azureName.startsWith(keyPrefix)) {
            return azureName.substring(keyPrefix.length());
        }
        return azureName;
    }

    @Override
    public void put(String key, byte[] data, Map<String, String> metadata) {
        String azureName = toAzureName(key);
        // Azure metadata keys must match [a-zA-Z_][a-zA-Z0-9_]* -- sanitize if needed
        Map<String, String> sanitizedMeta = metadata != null ? sanitizeMetadataKeys(metadata) : Collections.emptyMap();

        var blobClient = containerClient.getBlobClient(azureName);
        blobClient.upload(new ByteArrayInputStream(data), data.length, true);
        if (!sanitizedMeta.isEmpty()) {
            blobClient.setMetadata(sanitizedMeta);
        }

        LOG.debug("put blob '{}' to azure://{}/{} ({} bytes, {} metadata entries)",
            key, containerClient.getBlobContainerName(), azureName, data.length, sanitizedMeta.size());
    }

    @Override
    public Optional<Blob> get(String key) {
        String azureName = toAzureName(key);
        var blobClient = containerClient.getBlobClient(azureName);

        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            blobClient.downloadStream(outputStream);
            byte[] data = outputStream.toByteArray();

            var properties = blobClient.getProperties();
            Map<String, String> metadata = properties.getMetadata() != null
                ? new HashMap<>(properties.getMetadata())
                : Collections.emptyMap();

            return Optional.of(new Blob(key, data, metadata));
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public List<String> list(String prefix) {
        String azurePrefix = toAzureName(prefix);

        ListBlobsOptions options = new ListBlobsOptions()
            .setPrefix(azurePrefix)
            .setDetails(new BlobListDetails().setRetrieveMetadata(false));

        return containerClient.listBlobs(options, null).stream()
            .map(BlobItem::getName)
            .map(this::fromAzureName)
            .collect(Collectors.toList());
    }

    @Override
    public boolean delete(String key) {
        String azureName = toAzureName(key);
        var blobClient = containerClient.getBlobClient(azureName);

        try {
            if (!blobClient.exists()) {
                return false;
            }
            blobClient.delete();
            LOG.debug("deleted blob '{}' from azure://{}/{}", key,
                containerClient.getBlobContainerName(), azureName);
            return true;
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Sanitize metadata keys for Azure compatibility. Azure requires
     * metadata keys to be valid C# identifiers: start with letter or
     * underscore, followed by letters, digits, or underscores.
     */
    private static Map<String, String> sanitizeMetadataKeys(Map<String, String> metadata) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String key = entry.getKey().replaceAll("[^a-zA-Z0-9_]", "_");
            if (!key.isEmpty() && Character.isDigit(key.charAt(0))) {
                key = "_" + key;
            }
            result.put(key, entry.getValue());
        }
        return result;
    }
}
