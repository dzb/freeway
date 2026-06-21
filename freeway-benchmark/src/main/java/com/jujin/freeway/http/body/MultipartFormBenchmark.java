package com.jujin.freeway.http.body;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Thread)
public class MultipartFormBenchmark {

    private String contentType;
    private byte[] body;

    @Setup
    public void setup() {
        contentType = "multipart/form-data; boundary=----freeway-bench";
        String raw =
            "------freeway-bench\r\n" +
            "Content-Disposition: form-data; name=\"title\"\r\n" +
            "\r\n" +
            "freeway\r\n" +
            "------freeway-bench\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"bench.txt\"\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" +
            "hello world\r\n" +
            "------freeway-bench--\r\n";
        body = raw.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Benchmark
    public MultipartForm parse() throws IOException {
        return MultipartForm.parse(contentType, body);
    }
}
