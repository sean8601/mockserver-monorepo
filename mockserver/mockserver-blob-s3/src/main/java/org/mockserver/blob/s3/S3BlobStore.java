package org.mockserver.blob.s3;

import org.mockserver.state.Blob;
import org.mockserver.state.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * {@link BlobStore} implementation backed by AWS S3 (or any S3-compatible
 * store such as MinIO). Blob keys are mapped to S3 object keys with an
 * optional configurable prefix.
 * <p>
 * Metadata is stored as S3 user metadata on the object itself (the
 * {@code x-amz-meta-*} headers). This avoids a secondary metadata store
 * and keeps each blob's metadata atomically consistent with its data.
 * <p>
 * Thread-safety: {@link S3Client} is thread-safe; this class adds no
 * mutable state beyond the injected client and configuration.
 */
public class S3BlobStore implements BlobStore {

    private static final Logger LOG = LoggerFactory.getLogger(S3BlobStore.class);

    private final S3Client s3Client;
    private final String bucket;
    private final String keyPrefix;

    /**
     * Creates an S3 blob store.
     *
     * @param s3Client  the AWS S3 client (caller owns lifecycle)
     * @param bucket    the S3 bucket name
     * @param keyPrefix optional key prefix (e.g. "mockserver/"); empty string
     *                  for no prefix
     */
    public S3BlobStore(S3Client s3Client, String bucket, String keyPrefix) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.keyPrefix = keyPrefix != null ? keyPrefix : "";
    }

    private String toS3Key(String key) {
        return keyPrefix + key;
    }

    private String fromS3Key(String s3Key) {
        if (s3Key.startsWith(keyPrefix)) {
            return s3Key.substring(keyPrefix.length());
        }
        return s3Key;
    }

    @Override
    public void put(String key, byte[] data, Map<String, String> metadata) {
        String s3Key = toS3Key(key);
        Map<String, String> s3Meta = metadata != null ? new HashMap<>(metadata) : Collections.emptyMap();

        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(bucket)
            .key(s3Key)
            .metadata(s3Meta)
            .build();

        s3Client.putObject(request, RequestBody.fromBytes(data));
        LOG.debug("put blob '{}' to s3://{}/{} ({} bytes, {} metadata entries)",
            key, bucket, s3Key, data.length, s3Meta.size());
    }

    @Override
    public Optional<Blob> get(String key) {
        String s3Key = toS3Key(key);
        try {
            byte[] data;
            try (var response = s3Client.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .build())) {
                data = response.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException("failed to read blob from S3: " + key, e);
            }

            // Retrieve metadata via head (getObject response metadata
            // is available from the response but we need HeadObject to
            // get user metadata reliably on all S3-compatible backends)
            HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .build());

            Map<String, String> metadata = head.metadata() != null
                ? new HashMap<>(head.metadata())
                : Collections.emptyMap();

            return Optional.of(new Blob(key, data, metadata));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<String> list(String prefix) {
        String s3Prefix = toS3Key(prefix);

        ListObjectsV2Request request = ListObjectsV2Request.builder()
            .bucket(bucket)
            .prefix(s3Prefix)
            .build();

        List<String> keys = new ArrayList<>();
        ListObjectsV2Response response;
        do {
            response = s3Client.listObjectsV2(request);
            keys.addAll(response.contents().stream()
                .map(S3Object::key)
                .map(this::fromS3Key)
                .collect(Collectors.toList()));

            request = request.toBuilder()
                .continuationToken(response.nextContinuationToken())
                .build();
        } while (response.isTruncated());

        return keys;
    }

    @Override
    public boolean delete(String key) {
        String s3Key = toS3Key(key);
        try {
            // Check if the object exists first
            s3Client.headObject(HeadObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .build());
        } catch (NoSuchKeyException e) {
            return false;
        }

        s3Client.deleteObject(DeleteObjectRequest.builder()
            .bucket(bucket)
            .key(s3Key)
            .build());

        LOG.debug("deleted blob '{}' from s3://{}/{}", key, bucket, s3Key);
        return true;
    }
}
