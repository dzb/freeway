package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.storage.ObjectEntry;
import com.jujin.freeway.cloud.storage.ObjectMetadata;
import com.jujin.freeway.cloud.storage.ObjectStorage;
import com.jujin.freeway.cloud.storage.PutResult;
import com.jujin.freeway.cloud.storage.StorageException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * the mount root; writes go through a temp file and an atomic replace, so a
 * symlink planted at the target (or between check and write) is replaced as
 * a link, never followed.
 *
 * <p>{@code presignedUrl} has no meaning on a local file system — empty.
 * Domain events ({@link com.jujin.freeway.cloud.storage.ObjectStoredEvent} /
 * {@link com.jujin.freeway.cloud.storage.ObjectDeletedEvent}) are emitted via
 * the optional {@code events} consumer — the deleted event only when an
 * object was actually removed.
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
        java.util.Objects.requireNonNull(data, "data");
        Path target = resolve(bucket, key);
        try {
            Path parent = target.getParent();
            if (!Files.isDirectory(parent)) {
                Files.createDirectories(parent);
            }
            // A symlinked intermediate directory (root/bucket -> /etc) must
            // not steer the write out of the mount root.
            Path rootReal = root.toRealPath();
            if (!parent.toRealPath().startsWith(rootReal)) {
                throw new StorageException(
                    "Path escapes storage root: " + bucket + "/" + key);
            }
            // Write-then-rename: the final move is atomic within the bucket
            // and replaces whatever sits at the target (including a symlink
            // planted after the check above) without following it.
            Path temp = Files.createTempFile(parent, ".upload-", ".tmp");
            try {
                Files.write(temp, data);
                try {
                    Files.move(temp, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException writeFailed) {
                throw new StorageException("Failed to write " + bucket + "/" + key, writeFailed);
            } finally {
                try {
                    Files.deleteIfExists(temp); // no-op after a successful move
                } catch (IOException cleanupFailed) {
                    // best-effort cleanup — never mask the primary failure
                }
            }
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
        boolean deleted = false;
        try {
            Path parent = target.getParent();
            if (Files.isDirectory(parent)
                    && !parent.toRealPath().startsWith(root.toRealPath())) {
                throw new StorageException(
                    "Path escapes storage root: " + bucket + "/" + key);
            }
            // A symlink is removed as a link (never followed); a regular file
            // is removed directly. Anything else — absent key, directory —
            // stays untouched, so no event is emitted for it.
            if (Files.isSymbolicLink(target) || Files.isRegularFile(target)) {
                Files.delete(target);
                deleted = true;
            }
        } catch (IOException e) {
            throw new StorageException("Failed to delete " + bucket + "/" + key, e);
        }
        if (deleted) {
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
        try {
            Path rootReal = root.toRealPath();
            try (Stream<Path> stream = Files.walk(dir)) {
                return stream
                    .filter(Files::isRegularFile)
                    // A symlinked file pointing outside the root is skipped:
                    // get() would refuse it, so listing it would leak names.
                    .filter(path -> insideRoot(path, rootReal))
                    .map(path -> dir.relativize(path).toString().replace('\\', '/'))
                    .filter(relative -> relative.startsWith(safePrefix))
                    .map(relative -> entry(dir, relative))
                    .sorted(Comparator.comparing(ObjectEntry::key))
                    .toList();
            }
        } catch (IOException e) {
            throw new StorageException("Failed to list " + bucket + "/" + safePrefix, e);
        }
    }

    /** True when the entry's real path stays inside the mount root. */
    private boolean insideRoot(Path path, Path rootReal) {
        try {
            return path.toRealPath().startsWith(rootReal);
        } catch (IOException inaccessible) {
            return false; // vanished or unreadable mid-walk — not listable
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
        // A key with bytes the filesystem forbids (NUL, invalid UTF-16 for
        // the platform) must fail as a StorageException like every other
        // invalid key — not leak an InvalidPathException past the contract.
        Path relative;
        try {
            relative = Path.of(key).normalize();
        } catch (java.nio.file.InvalidPathException e) {
            throw new StorageException("key is not a valid path: " + key, e);
        }
        // Root component, not isAbsolute(): on Windows "/abs" parses as the
        // root-relative "\abs" — isAbsolute() is false, yet resolving it
        // discards the base path entirely ("D:/abs", "D:/evil" for
        // "/../evil"), which escapes the mount root.
        if (relative.getRoot() != null || relative.startsWith("..")) {
            throw new StorageException("key escapes storage root: " + key);
        }
        return root.resolve(safeBucket).resolve(relative).normalize();
    }

    private static String requireBucket(String bucket) {
        if (bucket == null || bucket.isBlank()
                || bucket.contains("..") || bucket.contains("/") || bucket.contains("\\")
                // "." would resolve the bucket to the storage root itself,
                // letting keys bypass bucket isolation.
                || bucket.equals(".")) {
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
