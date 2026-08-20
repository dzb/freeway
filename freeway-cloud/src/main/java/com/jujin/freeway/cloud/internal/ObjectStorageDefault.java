package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.storage.ObjectEntry;
import com.jujin.freeway.cloud.storage.ObjectMetadata;
import com.jujin.freeway.cloud.storage.ObjectStorage;
import com.jujin.freeway.cloud.storage.PutResult;
import com.jujin.freeway.cloud.storage.StorageException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File-system-backed {@link ObjectStorage}: {@code root/bucket/key}.
 *
 * <p>Path safety (mirrors the freeway-http staticfile regression bar):
 * bucket/key are validated against traversal ({@code ..}, absolute paths,
 * separators in buckets); reads verify the resolved real path stays inside
 * the mount root; writes delete a pre-existing symlink at the target (no
 * symlink poisoning), so a resolved path can never escape the root.
 *
 * <p>{@code presignedUrl} has no meaning on a local file system — empty.
 * Domain events ({@link com.jujin.freeway.cloud.storage.ObjectStoredEvent} /
 * {@link com.jujin.freeway.cloud.storage.ObjectDeletedEvent}) are emitted via
 * the optional {@code events} consumer.
 */
public final class ObjectStorageDefault implements ObjectStorage {

    private static final Logger LOG = LoggerFactory.getLogger(ObjectStorageDefault.class);

    private final Path root;
    private final Consumer<Object> events;

    public ObjectStorageDefault(Path root) {
        this(root, null);
    }

    public ObjectStorageDefault(Path root, Consumer<Object> events) {
        this.root = root.toAbsolutePath().normalize();
        this.events = events;
    }

    @Override
    public Optional<byte[]> get(String bucket, String key) throws StorageException {
        Path target = resolve(bucket, key);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        // Symlink defense: the resolved real path must stay inside the root.
        try {
            Path real = target.toRealPath();
            if (!real.startsWith(root.toRealPath())) {
                throw new StorageException("object path escapes storage root: " + bucket + "/" + key);
            }
            return Optional.of(Files.readAllBytes(real));
        } catch (IOException e) {
            throw new StorageException("Failed to read " + bucket + "/" + key, e);
        }
    }

    @Override
    public PutResult put(String bucket, String key, byte[] data, ObjectMetadata metadata) throws StorageException {
        Path target = resolve(bucket, key);
        try {
            Path parent = target.getParent();
            if (!Files.isDirectory(parent)) {
                Files.createDirectories(parent);
            }
            // Symlink defense (mirrors the read path): after creating the
            // parent chain, verify its real path still sits inside the real
            // root — a symlinked intermediate directory (root/bucket -> /etc)
            // would otherwise let the write escape the mount root.
            Path rootReal = root.toRealPath();
            if (!parent.toRealPath().startsWith(rootReal)) {
                throw new StorageException(
                    "Path escapes storage root: " + bucket + "/" + key);
            }
            // No symlink poisoning: a link at the target is removed before write.
            if (Files.isSymbolicLink(target)) {
                Files.delete(target);
            }
            Files.write(target, data);
        } catch (IOException e) {
            throw new StorageException("Failed to write " + bucket + "/" + key, e);
        }
        String etag = etag(data);
        long size = data.length;
        emit(new com.jujin.freeway.cloud.storage.ObjectStoredEvent(bucket, key, size, etag));
        return new PutResult(etag, UUID.randomUUID().toString());
    }

    @Override
    public void delete(String bucket, String key) throws StorageException {
        Path target = resolve(bucket, key);
        try {
            Path parent = target.getParent();
            if (Files.isDirectory(parent)
                    && !parent.toRealPath().startsWith(root.toRealPath())) {
                throw new StorageException(
                    "Path escapes storage root: " + bucket + "/" + key);
            }
            if (Files.isSymbolicLink(target)) {
                Files.delete(target);
            } else if (Files.isRegularFile(target)) {
                Files.delete(target);
            }
        } catch (IOException e) {
            throw new StorageException("Failed to delete " + bucket + "/" + key, e);
        }
        if (!Files.exists(target)) {
            emit(new com.jujin.freeway.cloud.storage.ObjectDeletedEvent(bucket, key));
        }
    }

    @Override
    public List<ObjectEntry> list(String bucket, String prefix) throws StorageException {
        String safeBucket = requireBucket(bucket);
        Path dir = root.resolve(safeBucket).normalize();
        String safePrefix = prefix == null ? "" : prefix;
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream
                .filter(Files::isRegularFile)
                .map(path -> {
                    String relative = dir.relativize(path).toString().replace('\\', '/');
                    return relative;
                })
                .filter(relative -> relative.startsWith(safePrefix))
                .map(relative -> entry(dir, relative))
                .sorted(Comparator.comparing(ObjectEntry::key))
                .toList();
        } catch (IOException e) {
            throw new StorageException("Failed to list " + bucket + "/" + safePrefix, e);
        }
    }

    private ObjectEntry entry(Path dir, String relative) {
        Path path = dir.resolve(relative);
        try {
            long size = Files.size(path);
            Instant modified = Files.getLastModifiedTime(path).toInstant();
            return new ObjectEntry(relative, size, modified,
                size + "-" + modified.toEpochMilli()); // derived tag, not a content hash
        } catch (IOException e) {
            throw new StorageException("Failed to stat " + relative, e);
        }
    }

    private Path resolve(String bucket, String key) {
        String safeBucket = requireBucket(bucket);
        if (key == null || key.isBlank()) {
            throw new StorageException("key must not be blank");
        }
        Path relative = Path.of(key).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new StorageException("key escapes storage root: " + key);
        }
        return root.resolve(safeBucket).resolve(relative).normalize();
    }

    private static String requireBucket(String bucket) {
        if (bucket == null || bucket.isBlank()
                || bucket.contains("..") || bucket.contains("/") || bucket.contains("\\")) {
            throw new StorageException("invalid bucket name: " + bucket);
        }
        return bucket;
    }

    private void emit(Object event) {
        Consumer<Object> sink = events;
        if (sink != null) {
            try {
                sink.accept(event);
            } catch (Exception e) {
                LOG.warn("Object storage event publish failed: {}", e.getMessage());
            }
        }
    }

    private static String etag(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
