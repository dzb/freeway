package com.jujin.freeway.cloud.storage;

import com.jujin.freeway.cloud.internal.ObjectStorageDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * File-system object storage: round-trip, listing, deletion, traversal and
 * symlink defenses, domain events.
 */
class ObjectStorageTest {

    @TempDir
    Path dir;

    @Test
    void putGetDeleteRoundTrip() {
        ObjectStorageDefault storage = new ObjectStorageDefault(dir);
        byte[] data = "hello object".getBytes(StandardCharsets.UTF_8);

        var put = storage.put("assets", "a/b.txt", data, ObjectMetadata.of("text/plain"));
        assertNotNull(put.etag());
        assertNotNull(put.versionId());

        byte[] read = storage.get("assets", "a/b.txt").orElseThrow();
        assertArrayEquals(data, read, "read bytes match written bytes");

        storage.delete("assets", "a/b.txt");
        assertTrue(storage.get("assets", "a/b.txt").isEmpty(), "deleted object is gone");
    }

    @Test
    void invalidKeysFailAsStorageException() {
        // A key containing a byte the filesystem forbids must surface as the
        // declared StorageException — not leak an InvalidPathException past
        // the storage contract.
        ObjectStorageDefault storage = new ObjectStorageDefault(dir);
        assertThrows(StorageException.class, () -> storage.get("assets", "bad\u0000key"));
        assertThrows(StorageException.class,
            () -> storage.put("assets", "bad\u0000key", new byte[]{1}, null));
        assertThrows(StorageException.class, () -> storage.delete("assets", "bad\u0000key"));
    }

    @Test
    void dotBucketIsRejected() {
        // "." would resolve the bucket to the storage root itself, letting
        // keys bypass bucket isolation.
        ObjectStorageDefault storage = new ObjectStorageDefault(dir);
        assertThrows(StorageException.class, () -> storage.put(".", "x", new byte[]{1}, null));
        assertThrows(StorageException.class, () -> storage.get(".", "x"));
        assertThrows(StorageException.class, () -> storage.list(".", ""));
    }

    @Test
    void nullDataFailsFast() {
        ObjectStorageDefault storage = new ObjectStorageDefault(dir);
        assertThrows(NullPointerException.class,
            () -> storage.put("assets", "x", null, null),
            "a null body is a caller bug — fail at the call site, not mid-write");
    }

    @Test
    void listFiltersByPrefix() {
        ObjectStorageDefault storage = new ObjectStorageDefault(dir);
        storage.put("assets", "img/a.png", new byte[]{1}, ObjectMetadata.of("image/png"));
        storage.put("assets", "img/b.png", new byte[]{2}, ObjectMetadata.of("image/png"));
        storage.put("assets", "doc/c.pdf", new byte[]{3}, ObjectMetadata.of("application/pdf"));

        List<ObjectEntry> images = storage.list("assets", "img/");
        assertEquals(List.of("img/a.png", "img/b.png"), images.stream().map(ObjectEntry::key).toList());
        assertEquals(1, storage.list("assets", "doc/").size());
        assertEquals(3, storage.list("assets", "").size());
    }

    @Test
    void traversalKeysAreRejected() {
        ObjectStorageDefault storage = new ObjectStorageDefault(dir);
        assertThrows(StorageException.class, () -> storage.put("assets", "../evil", new byte[1], ObjectMetadata.of("")));
        assertThrows(StorageException.class, () -> storage.put("assets", "a/../../evil", new byte[1], ObjectMetadata.of("")));
        // "/../evil" is the sharp case: on Windows it parses as root-relative
        // "\evil", so an isAbsolute() check alone lets it escape the root.
        assertThrows(StorageException.class, () -> storage.put("assets", "/../evil", new byte[1], ObjectMetadata.of("")));
        assertThrows(StorageException.class, () -> storage.get("assets", "/abs"));
        assertThrows(StorageException.class, () -> storage.put("bad..bucket", "k", new byte[1], ObjectMetadata.of("")));
        assertThrows(StorageException.class, () -> storage.put("a/b", "k", new byte[1], ObjectMetadata.of("")));
    }

    @Test
    void symlinkAtTargetIsRemovedNotFollowed() throws Exception {
        ObjectStorageDefault storage = new ObjectStorageDefault(dir);
        Path outside = dir.resolveSibling("outside.txt");
        Files.writeString(outside, "secret");

        // Symlink inside the bucket pointing outside the root.
        Path bucket = dir.resolve("assets");
        Files.createDirectories(bucket);
        Files.createSymbolicLink(bucket.resolve("linked.txt"), outside);

        storage.put("assets", "linked.txt", "owned".getBytes(StandardCharsets.UTF_8), ObjectMetadata.of(""));
        assertEquals("owned", new String(storage.get("assets", "linked.txt").orElseThrow(), StandardCharsets.UTF_8));
        assertEquals("secret", Files.readString(outside), "the outside file is untouched");
        assertFalse(Files.isSymbolicLink(bucket.resolve("linked.txt")), "the symlink was removed before write");
    }

    @Test
    void presignedUrlUnsupportedLocally() {
        ObjectStorageDefault storage = new ObjectStorageDefault(dir);
        assertTrue(storage.presignedUrl("b", "k", java.time.Duration.ofMinutes(5)).isEmpty());
    }

    @Test
    void putAndDeleteEmitDomainEvents() {
        List<Object> events = new CopyOnWriteArrayList<>();
        ObjectStorageDefault storage = new ObjectStorageDefault(dir, events::add);

        storage.put("assets", "k.txt", new byte[]{1}, ObjectMetadata.of(""));
        storage.delete("assets", "k.txt");

        assertEquals(2, events.size());
        assertTrue(events.get(0) instanceof com.jujin.freeway.cloud.storage.ObjectStoredEvent stored
            && "k.txt".equals(stored.key()));
        assertTrue(events.get(1) instanceof com.jujin.freeway.cloud.storage.ObjectDeletedEvent deleted
            && "k.txt".equals(deleted.key()));
    }

    @Test
    void deleteAbsentKeyIsANoopWithoutAnEvent() {
        List<Object> events = new CopyOnWriteArrayList<>();
        ObjectStorageDefault storage = new ObjectStorageDefault(dir, events::add);

        storage.delete("assets", "never-existed.txt");

        assertTrue(events.isEmpty(),
            "an absent key is a no-op — no phantom deletion event");
    }

    @Test
    void listSkipsSymlinksPointingOutsideTheRoot() throws Exception {
        ObjectStorageDefault storage = new ObjectStorageDefault(dir);
        Path outside = dir.resolveSibling("outside-list.txt");
        Files.writeString(outside, "secret");
        Path bucket = dir.resolve("assets");
        Files.createDirectories(bucket);
        Files.createSymbolicLink(bucket.resolve("leak.txt"), outside);
        storage.put("assets", "owned.txt", new byte[]{1}, ObjectMetadata.of(""));

        List<ObjectEntry> entries = storage.list("assets", "");

        assertEquals(List.of("owned.txt"), entries.stream().map(ObjectEntry::key).toList(),
            "an out-of-root symlink must not appear in listings");
    }
}
