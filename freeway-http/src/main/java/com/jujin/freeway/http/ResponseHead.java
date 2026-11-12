package com.jujin.freeway.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of a response head at commit time: status, whether the
 * request was a {@code HEAD}, and every header in wire order.
 *
 * <p>The snapshot is what transports serialize; a handler that changes the
 * context after the response was committed cannot alter it. {@code
 * Content-Length} lives only in {@link #headers()} — there is no second field
 * that could disagree with the header the transport writes.</p>
 *
 * @param status      the HTTP status code
 * @param headRequest true when the request method was {@code HEAD}
 * @param headers     response headers in insertion order, one entry per value
 */
public record ResponseHead(int status, boolean headRequest, List<Header> headers) {

    /** One response header field. */
    public record Header(String name, String value) {
        public Header {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }
    }

    public ResponseHead {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException(
                "Invalid HTTP status code: " + status + " (must be between 100 and 599)");
        }
        headers = List.copyOf(headers);
    }

    /** True when this status permits a message body: 204, 205 and 304 do not. */
    public boolean allowsBody() {
        return status != 204 && status != 205 && status != 304;
    }

    /**
     * True when body bytes may go on the wire: the status permits a body and
     * the request was not {@code HEAD}. {@code HEAD} keeps the headers of the
     * equivalent {@code GET}, including {@code Content-Length}.
     */
    public boolean sendsBodyBytes() {
        return allowsBody() && !headRequest;
    }

    /** True when a header with this name is present, case-insensitively. */
    public boolean hasHeader(String name) {
        for (Header header : headers) {
            if (header.name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** First value for the name, or null when absent. */
    public String header(String name) {
        for (Header header : headers) {
            if (header.name().equalsIgnoreCase(name)) return header.value();
        }
        return null;
    }

    /** All values for the name in insertion order, or an empty list. */
    public List<String> headers(String name) {
        List<String> values = new ArrayList<>();
        for (Header header : headers) {
            if (header.name().equalsIgnoreCase(name)) values.add(header.value());
        }
        return List.copyOf(values);
    }

    /** Returns a copy with every value for the name replaced by one value. */
    public ResponseHead withHeader(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        List<Header> copy = new ArrayList<>(headers.size() + 1);
        for (Header header : headers) {
            if (!header.name().equalsIgnoreCase(name)) copy.add(header);
        }
        copy.add(new Header(name, value));
        return new ResponseHead(status, headRequest, copy);
    }

    /** Returns a copy with every value for the name removed. */
    public ResponseHead withoutHeader(String name) {
        List<Header> copy = new ArrayList<>(headers.size());
        for (Header header : headers) {
            if (!header.name().equalsIgnoreCase(name)) copy.add(header);
        }
        return new ResponseHead(status, headRequest, copy);
    }
}
