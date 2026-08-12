package com.jujin.freeway.http;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Optional;

/**
 * Internal capabilities of the framework's {@link HttpContext} implementation.
 *
 * <p>Not part of the public application API: framework components (CORS
 * filter, static-file mounts, the built-in engine) use this interface to
 * reach response features that application handlers do not need. Custom
 * {@code HttpContext} implementations are free to ignore it — consumers must
 * fall back gracefully when a context does not expose these capabilities.</p>
 */
public interface HttpContextInternal {

    /** Returns a response header value before the response is committed. */
    Optional<String> responseHeaderValue(String name);

    /**
     * Streams a file channel as the response body. The caller hands over
     * ownership: this method closes the channel when the transfer finishes
     * (including failure paths).
     */
    HttpContext outputFile(FileChannel channel, long offset, long length)
        throws IOException;
}
