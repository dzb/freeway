package com.jujin.freeway.http;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.engine.FreewayHttpEngine;
import java.time.Duration;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpModuleSeamTest {
    @Test
    void sslReloaderClosesInjectedScheduler() {
        var scheduler = new ScheduledThreadPoolExecutor(1);
        var engine = new FreewayHttpEngine(new JsonCodecDefault(), new CoercerDefault());
        var settings = new HttpModule.SslSettings(true, "/tmp/key", "secret", "PKCS12",
            false, null, null, "PKCS12", false, null, null, null, Duration.ofSeconds(1));
        var reloader = new HttpModule().new SslReloader(engine, settings, scheduler,
            path -> new java.nio.file.attribute.BasicFileAttributes() {
                public java.nio.file.attribute.FileTime lastModifiedTime() { return java.nio.file.attribute.FileTime.fromMillis(1); }
                public java.nio.file.attribute.FileTime lastAccessTime() { return lastModifiedTime(); }
                public java.nio.file.attribute.FileTime creationTime() { return lastModifiedTime(); }
                public boolean isRegularFile() { return true; }
                public boolean isDirectory() { return false; }
                public boolean isSymbolicLink() { return false; }
                public boolean isOther() { return false; }
                public long size() { return 1; }
                public Object fileKey() { return path; }
            });
        reloader.close();
        assertTrue(scheduler.isShutdown());
    }
}
