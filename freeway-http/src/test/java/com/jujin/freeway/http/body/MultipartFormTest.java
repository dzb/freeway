package com.jujin.freeway.http.body;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.http.body.MultipartForm;

class MultipartFormTest {
    @Test
    void parsesFieldsAndFiles() throws Exception {
        String boundary = "----FreewayBoundary";
        byte[] body = (
            "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n"
                + "\r\n"
                + "avatar\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"hello.txt\"\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "\r\n"
                + "hello world\r\n"
                + "--" + boundary + "--\r\n"
        ).getBytes(StandardCharsets.ISO_8859_1);

        MultipartForm form = MultipartForm.parse("multipart/form-data; boundary=" + boundary, body);

        assertEquals("avatar", form.value("title"));
        assertEquals(1, form.parts("file").size());
        MultipartForm.Part file = form.file("file").orElseThrow();
        assertTrue(file.isFile());
        assertEquals("hello.txt", file.filename());
        assertEquals("hello world", file.text());
        assertEquals(11, file.size());
    }
}
