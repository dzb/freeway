package com.jujin.freeway.http.body;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import com.jujin.freeway.http.HttpContext;

public final class MultipartForm {

    private static final Charset RAW = StandardCharsets.ISO_8859_1;

    private final List<Part> parts;
    private final Map<String, List<Part>> byName;

    private MultipartForm(List<Part> parts) {
        this.parts = List.copyOf(parts);
        Map<String, List<Part>> indexed = new LinkedHashMap<>();
        for (Part part : this.parts) {
            indexed
                .computeIfAbsent(part.name(), ignored -> new ArrayList<>())
                .add(part);
        }
        Map<String, List<Part>> frozen = new LinkedHashMap<>();
        indexed.forEach((name, entries) ->
            frozen.put(name, List.copyOf(entries))
        );
        this.byName = Map.copyOf(frozen);
    }

    public static MultipartForm parse(String contentType, byte[] body)
        throws IOException {
        String boundary = boundaryFromContentType(contentType);
        if (boundary == null) {
            throw new IOException(
                "Expected multipart/form-data but got " + contentType
            );
        }
        return new MultipartForm(parseParts(boundary, body));
    }

    public List<Part> parts() {
        return parts;
    }

    public List<Part> parts(String name) {
        return byName.getOrDefault(name, List.of());
    }

    public Optional<Part> part(String name) {
        return parts(name).stream().findFirst();
    }

    public List<Part> files(String name) {
        return parts(name).stream().filter(Part::isFile).toList();
    }

    public Optional<Part> file(String name) {
        return files(name).stream().findFirst();
    }

    public List<String> values(String name) {
        return parts(name)
            .stream()
            .filter(part -> !part.isFile())
            .map(Part::text)
            .toList();
    }

    public String value(String name) {
        return values(name).stream().findFirst().orElse(null);
    }

    public boolean isEmpty() {
        return parts.isEmpty();
    }

    private static List<Part> parseParts(String boundary, byte[] body)
        throws IOException {
        String raw = new String(body, RAW);
        String boundaryMarker = "--" + boundary;
        int cursor = raw.indexOf(boundaryMarker);
        if (cursor < 0) {
            throw new IOException("Multipart boundary not found");
        }
        List<Part> parts = new ArrayList<>();
        cursor += boundaryMarker.length();
        cursor = skipLineBreak(raw, cursor);
        while (cursor >= 0 && cursor < raw.length()) {
            if (raw.startsWith("--", cursor)) {
                break;
            }
            Separator headersSeparator = findHeadersSeparator(raw, cursor);
            if (headersSeparator == null) {
                throw new IOException("Invalid multipart section");
            }
            String headerBlock = raw.substring(
                cursor,
                headersSeparator.index()
            );
            int contentStart = headersSeparator.nextIndex();
            BoundaryHit nextBoundary = findNextBoundary(
                raw,
                contentStart,
                boundaryMarker
            );
            if (nextBoundary == null) {
                throw new IOException(
                    "Multipart section without closing boundary"
                );
            }
            String content = raw.substring(contentStart, nextBoundary.index());
            parts.add(parsePart(headerBlock, content));
            cursor = nextBoundary.nextIndex() + boundaryMarker.length();
            if (raw.startsWith("--", cursor)) {
                break;
            }
            cursor = skipLineBreak(raw, cursor);
        }
        return parts;
    }

    private static Part parsePart(String headerBlock, String content)
        throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line : headerBlock.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line
                .substring(0, colon)
                .trim()
                .toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.put(name, value);
        }
        String disposition = headers.get("content-disposition");
        if (disposition == null) {
            throw new IOException("Multipart part missing Content-Disposition");
        }
        String name = null;
        String filename = null;
        for (String token : disposition.split(";")) {
            String item = token.trim();
            int eq = item.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = item.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = unquote(item.substring(eq + 1).trim());
            if ("name".equals(key)) {
                name = value;
            } else if ("filename".equals(key)) {
                filename = value;
            }
        }
        if (name == null || name.isBlank()) {
            throw new IOException("Multipart part missing field name");
        }
        String contentType = headers.get("content-type");
        byte[] bytes = content.getBytes(RAW);
        return new Part(
            name,
            HttpContext.blankToNull(filename),
            HttpContext.blankToNull(contentType),
            bytes
        );
    }

    private static String boundaryFromContentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        for (String token : contentType.split(";")) {
            String item = token.trim();
            int eq = item.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = item.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            if ("boundary".equals(key)) {
                return unquote(item.substring(eq + 1).trim());
            }
        }
        return null;
    }

    private static BoundaryHit findNextBoundary(
        String raw,
        int fromIndex,
        String boundaryMarker
    ) {
        String crlfMarker = "\r\n" + boundaryMarker;
        int index = raw.indexOf(crlfMarker, fromIndex);
        if (index >= 0) {
            return new BoundaryHit(index, index + 2);
        }
        String lfMarker = "\n" + boundaryMarker;
        index = raw.indexOf(lfMarker, fromIndex);
        if (index >= 0) {
            return new BoundaryHit(index, index + 1);
        }
        return null;
    }

    private static Separator findHeadersSeparator(String raw, int fromIndex) {
        int crlf = raw.indexOf("\r\n\r\n", fromIndex);
        if (crlf >= 0) {
            return new Separator(crlf, crlf + 4);
        }
        int lf = raw.indexOf("\n\n", fromIndex);
        if (lf >= 0) {
            return new Separator(lf, lf + 2);
        }
        return null;
    }

    private static int skipLineBreak(String raw, int index) {
        if (index < 0 || index >= raw.length()) {
            return index;
        }
        if (raw.startsWith("\r\n", index)) {
            return index + 2;
        }
        if (raw.charAt(index) == '\n') {
            return index + 1;
        }
        if (raw.charAt(index) == '\r') {
            return index + 1;
        }
        return index;
    }

    private static String unquote(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (
            (value.startsWith("\"") && value.endsWith("\"")) ||
            (value.startsWith("'") && value.endsWith("'"))
        ) {
            value = value.substring(1, value.length() - 1);
        }
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private record BoundaryHit(int index, int nextIndex) {}

    private record Separator(int index, int nextIndex) {}

    public record Part(
        String name,
        String filename,
        String contentType,
        byte[] bytes
    ) {
        public Part {
            name = Objects.requireNonNull(name, "name");
            bytes = bytes != null ? bytes.clone() : new byte[0];
            filename = HttpContext.blankToNull(filename);
            contentType = HttpContext.blankToNull(contentType);
        }

        public boolean isFile() {
            return filename != null;
        }

        public long size() {
            return bytes.length;
        }

        public byte[] bytes() {
            return bytes.clone();
        }

        public InputStream openStream() {
            return new ByteArrayInputStream(bytes);
        }

        public String text() {
            return text(charset());
        }

        public String text(Charset charset) {
            return new String(
                bytes,
                charset == null ? StandardCharsets.UTF_8 : charset
            );
        }

        public Path saveTo(Path path) throws IOException {
            Files.write(path, bytes);
            return path;
        }

        public Charset charset() {
            if (contentType != null) {
                for (String token : contentType.split(";")) {
                    String item = token.trim();
                    int eq = item.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String key = item
                        .substring(0, eq)
                        .trim()
                        .toLowerCase(Locale.ROOT);
                    if ("charset".equals(key)) {
                        try {
                            return Charset.forName(
                                unquote(item.substring(eq + 1).trim())
                            );
                        } catch (IllegalArgumentException ignored) {
                            return StandardCharsets.UTF_8;
                        }
                    }
                }
            }
            return StandardCharsets.UTF_8;
        }
    }
}
