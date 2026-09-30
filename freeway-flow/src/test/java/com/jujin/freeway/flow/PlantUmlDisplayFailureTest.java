package com.jujin.freeway.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A user-supplied PlantUML display function that throws is reported, not
 * swallowed.
 *
 * <p>It used to be caught and the default rendering emitted instead. The
 * resulting diagram was well-formed and completely wrong, with nothing on
 * screen or in the log to say the customization had been dropped — so the
 * function looked like it simply had no effect, and the only way to find out
 * why was to read this class. That is the opposite of the framework's
 * standing rule that a failing user callback names itself and its fix.
 */
class PlantUmlDisplayFailureTest {

    private static Graph graph() {
        return Graph.create("display_failure", spec -> {
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a").task("@task1").linkAdd("e");
            spec.addEnd("e");
        });
    }

    @Test
    void aThrowingDisplayFunctionIsReportedWithTheFix() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> graph().toPlantUml(ctx -> {
                throw new IllegalArgumentException("boom");
            }));

        assertTrue(ex.getMessage().contains("display function failed"),
            "the message must name what failed: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("HIDDEN") && ex.getMessage().contains("ofDefault"),
            "the message must point at the three real outcomes of a display "
                + "function, not just report a throw: " + ex.getMessage());
        assertEquals(IllegalArgumentException.class, ex.getCause().getClass(),
            "the user's own exception must be preserved as the cause");
    }

    @Test
    void aThrowingDisplayFunctionOnALinkIsAlsoReported() {
        // The same function runs over link labels; swallowing there was the
        // same defect on a different path.
        Graph withCondition = Graph.create("display_failure_link", spec -> {
            spec.addStart("s").linkAdd("a");
            spec.addActivity("a").task("@task1")
                .linkAdd("e", ld -> ld.when("x > 5"));
            spec.addEnd("e");
        });
        assertThrows(IllegalStateException.class,
            () -> withCondition.toPlantUml(ctx -> {
                throw new IllegalStateException("link boom");
            }));
    }

    @Test
    void aWorkingDisplayFunctionStillRelabels() {
        String puml = graph().toPlantUml(
            ctx -> PlantUmlDisplayResult.of("custom-" + ctx.id()));

        assertTrue(puml.contains("custom-"),
            "the successful path must be unchanged: " + puml);
    }

    @Test
    void hiddenAndDefaultStillWork() {
        assertFalse(graph().toPlantUml(ctx -> PlantUmlDisplayResult.HIDDEN)
            .contains("@task1"),
            "HIDDEN must drop the label entirely");

        String withDefault = graph().toPlantUml(ctx -> PlantUmlDisplayResult.ofDefault());
        assertTrue(withDefault.contains("@task1"),
            "ofDefault must keep the default label: " + withDefault);
    }
}
