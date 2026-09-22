package com.jujin.freeway.http;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The startup notice for the retired {@code freeway.web.*} prefix: the one
 * shape that silently fails — retired twin present while the current key is
 * absent — must be reported with its rename, while every shape whose
 * configuration still takes effect stays silent. A dead key whose own value
 * is broken must be reported, never thrown: nothing reads it, so its content
 * cannot be allowed to stop startup before the notice names it.
 */
class HttpModuleRetiredPrefixTest {

    private static final String RETIRED_PORT = "freeway.web.server.port";
    private static final String CURRENT_PORT = "freeway.http.server.port";

    @Test
    void deadRetiredTwinIsReportedWithItsRename() {
        var notices = HttpModule.retiredPrefixNotices(
            source(Map.of(RETIRED_PORT, "19081")));
        assertEquals(List.of(RETIRED_PORT + " → " + CURRENT_PORT), notices,
            "an old-prefix key with no current twin runs on defaults and must name its fix");
    }

    @Test
    void presentCurrentKeySilencesTheNotice() {
        assertTrue(HttpModule.retiredPrefixNotices(
            source(Map.of(CURRENT_PORT, "8081"))).isEmpty(),
            "no retired key configured, nothing dead to report");
        assertTrue(HttpModule.retiredPrefixNotices(source(Map.of(
            RETIRED_PORT, "19081", CURRENT_PORT, "8081"))).isEmpty(),
            "a current key means the value reaches the server — no silence to report");
    }

    @Test
    void currentKeyReferencingTheRetiredKeyStillCountsAsPresent() {
        assertTrue(HttpModule.retiredPrefixNotices(source(Map.of(
            RETIRED_PORT, "19081",
            CURRENT_PORT, "${" + RETIRED_PORT + "}"))).isEmpty(),
            "an explicit ${freeway.web.*} reference is configuration that takes effect");
    }

    @Test
    void brokenValueInADeadKeyIsReportedNotThrown() {
        assertEquals(List.of(RETIRED_PORT + " → " + CURRENT_PORT),
            HttpModule.retiredPrefixNotices(
                source(Map.of(RETIRED_PORT, "${no-such-symbol}"))),
            "a dead key's unexpandable value must not stop startup before the notice names it");
    }

    @Test
    void retiredPrefixJunkOutsideTheKeyTableIsNotReported() {
        assertTrue(HttpModule.retiredPrefixNotices(
            source(Map.of("freeway.web.no-such-key", "1"))).isEmpty(),
            "only twins of keys the table declares are this module's to report");
    }

    private static SymbolSource source(Map<String, String> values) {
        return SymbolSource.of(new CoercerDefault(),
            SymbolProvider.of(() -> values, SymbolProvider.TIER_FILES));
    }
}
