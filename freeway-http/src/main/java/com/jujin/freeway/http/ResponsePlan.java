package com.jujin.freeway.http;

import java.util.Locale;
import java.util.Objects;

/**
 * One response: the final head plus the body source. The support object plans
 * it; the transport executes it. There is no separate gzip flag — the head's
 * {@code Content-Encoding} is the single statement of the encoding decision.
 */
public record ResponsePlan(ResponseHead head, ResponseBody body) {

    public ResponsePlan {
        Objects.requireNonNull(head, "head");
        Objects.requireNonNull(body, "body");
    }

    /** True when the transport must gzip the body before framing it. */
    public boolean gzip() {
        String encoding = head.header("Content-Encoding");
        return encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip");
    }
}
