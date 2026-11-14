package com.jujin.freeway.commons.logging;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link LogBootstrap}'s external SLF4J provider detection and the
 * {@code slf4j.provider} pinning that makes provider selection deterministic
 * (Logback / Log4j / slf4j-simple must win over the bundled JUL provider).
 *
 * <p>The external provider classes are faked with a custom {@link ClassLoader}
 * that defines minimal class files under the real provider binary names — no
 * external dependencies are added. {@code slf4j.provider} is saved/restored
 * around every mutation so these tests never leak global state.
 */
class LogBootstrapProviderTest {

    private static final String LOGBACK =
        "ch.qos.logback.classic.spi.LogbackServiceProvider";
    private static final String LOG4J =
        "org.apache.logging.slf4j.Log4jServiceProvider";
    private static final String SIMPLE =
        "org.slf4j.simple.SimpleServiceProvider";

    // ── detection ────────────────────────────────────────────

    @Test
    void propertyNameMatchesSlf4jContract() {
        assertEquals("slf4j.provider", LogBootstrap.SLF4J_PROVIDER_PROPERTY);
    }

    @Test
    void detectsLogbackViaCustomClassLoader() {
        ClassLoader loader = new FakeProviderLoader(
            getClass().getClassLoader(), LOGBACK);
        assertEquals(LOGBACK, LogBootstrap.detectExternalProvider(loader));
    }

    @Test
    void prefersLogbackOverLog4jOverSimple() {
        ClassLoader all = new FakeProviderLoader(
            getClass().getClassLoader(), LOG4J, SIMPLE, LOGBACK);
        assertEquals(LOGBACK, LogBootstrap.detectExternalProvider(all),
            "logback must win over log4j and simple");

        ClassLoader noLogback = new FakeProviderLoader(
            getClass().getClassLoader(), SIMPLE, LOG4J);
        assertEquals(LOG4J, LogBootstrap.detectExternalProvider(noLogback),
            "log4j must win over simple");

        ClassLoader simpleOnly = new FakeProviderLoader(
            getClass().getClassLoader(), SIMPLE);
        assertEquals(SIMPLE, LogBootstrap.detectExternalProvider(simpleOnly));
    }

    @Test
    void noExternalProviderYieldsNull() {
        ClassLoader none = new FakeProviderLoader(getClass().getClassLoader());
        assertNull(LogBootstrap.detectExternalProvider(none));
    }

    @Test
    void commonsTestClasspathHasNoExternalProvider() {
        assertNull(LogBootstrap.detectExternalProvider(getClass().getClassLoader()),
            "freeway-commons tests have no external SLF4J provider");
    }

    // ── freeway.log.system selection ──────────────────────────

    @Test
    void defaultSelectsFreewayLoggingEvenWhenLogbackIsPresent() {
        ClassLoader loader = new FakeProviderLoader(
            getClass().getClassLoader(), LOGBACK);
        LogBootstrap.Selection selection = LogBootstrap.select(null, null, loader);

        assertEquals(LogBootstrap.JUL_PROVIDER, selection.provider(),
            "the default backend is freeway-logging, not the classpath's external provider");
        assertEquals("default", selection.source());
        assertEquals(LOGBACK, selection.detectedExternal(),
            "the external provider is still detected, for the notice");

        String report = LogBootstrap.startupReport(selection);
        assertTrue(report.contains("freeway-logging (JUL)"), report);
        assertTrue(report.contains("logback is on the classpath but not selected"), report);
        assertTrue(report.contains("freeway.log.system=auto"), report);
    }

    @Test
    void explicitFreewayKeyAlsoIgnoresExternalProviders() {
        ClassLoader loader = new FakeProviderLoader(
            getClass().getClassLoader(), LOGBACK);
        LogBootstrap.Selection selection =
            LogBootstrap.select("freeway", null, loader);

        assertEquals(LogBootstrap.JUL_PROVIDER, selection.provider());
        assertEquals("freeway.log.system=freeway", selection.source());
    }

    @Test
    void autoKeepsExternalPriorityWithJulAsFallback() {
        ClassLoader logback = new FakeProviderLoader(
            getClass().getClassLoader(), LOG4J, SIMPLE, LOGBACK);
        assertEquals(LOGBACK, LogBootstrap.select("auto", null, logback).provider(),
            "auto keeps logback > log4j > simple");

        ClassLoader none = new FakeProviderLoader(getClass().getClassLoader());
        assertEquals(LogBootstrap.JUL_PROVIDER,
            LogBootstrap.select("auto", null, none).provider(),
            "auto falls back to freeway-logging when no external provider is present");
    }

    @Test
    void namedValuesMapToKnownProvidersAndIgnoreCase() {
        ClassLoader loader = new FakeProviderLoader(
            getClass().getClassLoader(), LOGBACK, LOG4J, SIMPLE);

        assertEquals(LOGBACK, LogBootstrap.select("logback", null, loader).provider());
        assertEquals(LOGBACK, LogBootstrap.select("Logback", null, loader).provider());
        assertEquals(LOG4J, LogBootstrap.select("log4j2", null, loader).provider());
        assertEquals(LOG4J, LogBootstrap.select("log4j", null, loader).provider());
        assertEquals(SIMPLE, LogBootstrap.select("simple", null, loader).provider());
    }

    @Test
    void fullyQualifiedProviderClassIsAccepted() {
        String custom = "com.example.MyCustomProvider";
        ClassLoader loader = new FakeProviderLoader(
            getClass().getClassLoader(), custom);

        assertEquals(custom, LogBootstrap.select(custom, null, loader).provider(),
            "an arbitrary SLF4JServiceProvider class name is a valid selection");
    }

    @Test
    void explicitMissingProviderFailsLoudly() {
        ClassLoader loader = new FakeProviderLoader(getClass().getClassLoader());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> LogBootstrap.select("logback", null, loader));
        assertTrue(failure.getMessage().contains("not on the classpath"),
            failure.getMessage());
        assertTrue(failure.getMessage().contains(LOGBACK),
            "the message names the provider that is missing: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("freeway/auto"),
            "and the way out: " + failure.getMessage());
    }

    @Test
    void slf4jProviderWinsOverTheFreewayKeyAndSaysSo() {
        ClassLoader loader = new FakeProviderLoader(
            getClass().getClassLoader(), LOGBACK);
        LogBootstrap.Selection selection =
            LogBootstrap.select("logback", "com.example.Native", loader);

        assertEquals("com.example.Native", selection.provider(),
            "the JVM-level slf4j.provider is the escape hatch and wins");
        assertEquals(LogBootstrap.SLF4J_PROVIDER_PROPERTY, selection.source());
        assertNotNull(selection.conflict(), "a disagreeing freeway key must be reported");
        assertTrue(selection.conflict().contains("takes precedence"), selection.conflict());
        assertTrue(selection.conflict().contains("logback"), selection.conflict());
    }

    @Test
    void agreeingSelectionsProduceNoConflict() {
        ClassLoader loader = new FakeProviderLoader(
            getClass().getClassLoader(), LOGBACK);
        LogBootstrap.Selection selection =
            LogBootstrap.select("logback", LOGBACK, loader);

        assertNull(selection.conflict());
        assertEquals(LOGBACK, selection.provider());
    }

    @Test
    void startupReportNamesCustomProviderAndSource() {
        String custom = "com.example.MyProvider";
        ClassLoader loader = new FakeProviderLoader(
            getClass().getClassLoader(), custom);

        String report = LogBootstrap.startupReport(
            LogBootstrap.select(custom, null, loader));
        assertTrue(report.contains("provider=" + custom), report);
        assertTrue(report.contains("selected by freeway.log.system=" + custom), report);
    }

    // ── fake provider classloader ─────────────────────────────

    /**
     * A classloader that defines minimal valid class files under the given
     * binary names, so {@link LogBootstrap#detectExternalProvider} sees the
     * provider as "present" without any external dependency.
     */
    private static final class FakeProviderLoader extends ClassLoader {

        private final Map<String, byte[]> fakeClasses;

        FakeProviderLoader(ClassLoader parent, String... fakeClassNames) {
            super(parent);
            Map<String, byte[]> classes = new HashMap<>();
            for (String name : fakeClassNames) {
                classes.put(name, minimalClassFile(name));
            }
            this.fakeClasses = Map.copyOf(classes);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = fakeClasses.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /**
     * Builds a minimal, valid class file (Java 8, no members) for the given
     * binary name — just enough for {@code Class.forName(name, false, loader)}
     * to succeed.
     */
    private static byte[] minimalClassFile(String className) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeInt(0xCAFEBABE);   // magic
            out.writeShort(0);          // minor version
            out.writeShort(52);         // major version: Java 8
            out.writeShort(5);          // constant pool count (indices 1..4)
            writeUtf8(out, className.replace('.', '/'));
            writeUtf8(out, "java/lang/Object");
            out.writeByte(7);           // CONSTANT_Class
            out.writeShort(1);          //   name_index → this class
            out.writeByte(7);           // CONSTANT_Class
            out.writeShort(2);          //   name_index → java/lang/Object
            out.writeShort(0x0021);     // access_flags: ACC_PUBLIC | ACC_SUPER
            out.writeShort(3);          // this_class
            out.writeShort(4);          // super_class
            out.writeShort(0);          // interfaces_count
            out.writeShort(0);          // fields_count
            out.writeShort(0);          // methods_count
            out.writeShort(0);          // attributes_count
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void writeUtf8(DataOutputStream out, String value)
            throws IOException {
        out.writeByte(1);               // CONSTANT_Utf8
        out.writeUTF(value);
    }
}
