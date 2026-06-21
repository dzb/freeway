package com.jujin.freeway.http.engine.ws;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * JMH benchmark for {@link WebSocketFrame} read, write, and construction.
 *
 * <p>Covers text, binary, and close frame types across small and large
 * payload sizes. Placed in the {@code ws} package to access package-private
 * {@link WebSocketFrame#read(java.io.InputStream)} and
 * {@link WebSocketFrame#write(java.io.OutputStream)} methods.
 */
@State(Scope.Thread)
public class WebSocketFrameBenchmark {

    private WebSocketFrame textFrame;
    private WebSocketFrame binaryFrame;
    private WebSocketFrame largeTextFrame;

    private byte[] smallTextFrameBytes;
    private byte[] largeTextFrameBytes;
    private byte[] binaryFrameBytes;

    @Setup
    public void setup() throws IOException {
        textFrame = new WebSocketFrame(OpCode.Text, true, "ping");
        binaryFrame = new WebSocketFrame(OpCode.Binary, true, new byte[]{0x01, 0x02, 0x03});
        largeTextFrame = new WebSocketFrame(OpCode.Text, true, "p".repeat(4000));

        // Pre-serialized bytes for read benchmarks
        smallTextFrameBytes = serialize(textFrame);
        largeTextFrameBytes = serialize(largeTextFrame);
        binaryFrameBytes = serialize(binaryFrame);
    }

    // --- Read (parse from wire) ---

    @Benchmark
    public WebSocketFrame readSmallText() throws IOException {
        return WebSocketFrame.read(new ByteArrayInputStream(smallTextFrameBytes));
    }

    @Benchmark
    public WebSocketFrame readLargeText() throws IOException {
        return WebSocketFrame.read(new ByteArrayInputStream(largeTextFrameBytes));
    }

    @Benchmark
    public WebSocketFrame readBinary() throws IOException {
        return WebSocketFrame.read(new ByteArrayInputStream(binaryFrameBytes));
    }

    // --- Write (serialize to wire) ---

    @Benchmark
    public void writeSmallText() throws IOException {
        textFrame.write(OutputStream.nullOutputStream());
    }

    @Benchmark
    public void writeLargeText() throws IOException {
        largeTextFrame.write(OutputStream.nullOutputStream());
    }

    // --- Construction ---

    @Benchmark
    public WebSocketFrame constructSmallText() {
        return new WebSocketFrame(OpCode.Text, true, "ping");
    }

    @Benchmark
    public WebSocketFrame constructLargeText() {
        return new WebSocketFrame(OpCode.Text, true, LARGE_PAYLOAD);
    }

    // --- helpers ---

    private static final String LARGE_PAYLOAD = "p".repeat(4000);

    private static byte[] serialize(WebSocketFrame frame) throws IOException {
        var baos = new ByteArrayOutputStream(64);
        frame.write(baos);
        return baos.toByteArray();
    }
}
