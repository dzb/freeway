package com.jujin.freeway.cloud.events;

/**
 * Reassembles a WebSocket text message from its frames. A client listener sees
 * raw frames — unlike the server side, whose engine hands over a completed
 * message — so a peer's oversized event arrives as a CONTINUATION sequence and
 * must be stitched back together before it can be parsed.
 *
 * <p>Not thread-safe by design: a WebSocket delivers its frames serially, and
 * one assembler belongs to one connection.</p>
 */
final class TextMessageAssembler {

    private final int limit;
    private StringBuilder pending;

    TextMessageAssembler(int limit) {
        this.limit = limit;
    }

    /**
     * @return the complete message, or {@code null} while more fragments are due
     * @throws IllegalStateException if the message grows past the configured
     *         limit — a peer that never sets FIN must not cost unbounded memory
     */
    String accept(CharSequence data, boolean last) {
        if (pending == null) {
            if (data.length() > limit) {
                throw tooBig();
            }
            if (last) {
                return data.toString(); // the single-frame case, no buffer touched
            }
            pending = new StringBuilder().append(data);
            return null;
        }
        pending.append(data);
        if (pending.length() > limit) {
            throw tooBig();
        }
        if (!last) {
            return null;
        }
        String text = pending.toString();
        pending = null;
        return text;
    }

    /** Releases the buffer: a rejected message must not leak into the next one. */
    private IllegalStateException tooBig() {
        pending = null;
        return new IllegalStateException("inbound message exceeds " + limit + " characters");
    }
}
