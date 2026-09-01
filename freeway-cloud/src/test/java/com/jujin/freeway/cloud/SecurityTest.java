package com.jujin.freeway.cloud;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.context.Baggage;
import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.PrincipalContext;
import com.jujin.freeway.cloud.internal.AuthPropagator;
import com.jujin.freeway.cloud.internal.TransportSecurityDefault;
import com.jujin.freeway.cloud.rpc.TransportSecurity;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security layer: verified-identity propagation, secret-backed symbol
 * resolution and file-backed mTLS context building.
 */
class SecurityTest {
    @BeforeEach
    void randomPort() {
        System.setProperty(com.jujin.freeway.http.HttpConfigKeys.SERVER_PORT, "0");
    }


    @TempDir
    Path dir;

    @AfterEach
    void clearProperties() {
        System.clearProperty(com.jujin.freeway.cloud.CloudConfigKeys.SECRET_FILE);
        System.clearProperty("freeway.config.file");
    }

    @Test
    void authPropagatorRoundTripsPrincipalAndRolesWhenEnabled() {
        System.setProperty(com.jujin.freeway.cloud.CloudConfigKeys.AUTH_EXTRACT_ENABLED, "true");
        try {
            AuthPropagator propagator = new AuthPropagator(sysProps());
            PrincipalContext principal = PrincipalContext.of("alice", List.of("admin", "user"));
            InvocationContext ctx = InvocationContext.of(null, principal, Baggage.empty());

            Map<String, String> headers = new HashMap<>();
            propagator.inject(ctx, headers);
            assertEquals("alice", headers.get(AuthPropagator.HEADER_PRINCIPAL));
            assertEquals("admin,user", headers.get(AuthPropagator.HEADER_ROLES));

            InvocationContext extracted = propagator.extract(headers);
            assertNotNull(extracted.principal());
            assertEquals("alice", extracted.principal().name());
            assertTrue(extracted.principal().hasRole("admin"));
        } finally {
            System.clearProperty(com.jujin.freeway.cloud.CloudConfigKeys.AUTH_EXTRACT_ENABLED);
        }
    }

    @Test
    void authPropagatorIgnoresInboundIdentityByDefault() {
        // Security default: a client-supplied x-principal must NOT be trusted
        // unless extraction was explicitly enabled.
        AuthPropagator propagator = new AuthPropagator(sysProps());
        Map<String, String> forged = Map.of(
            AuthPropagator.HEADER_PRINCIPAL, "admin",
            AuthPropagator.HEADER_ROLES, "admin,superuser");

        InvocationContext extracted = propagator.extract(forged);
        assertNull(extracted.principal(),
            "inbound identity headers must be ignored when extraction is disabled");
    }

    @Test
    void secretSymbolSourceResolvesSecrets() throws Exception {
        Path secrets = dir.resolve("secrets.properties");
        Files.writeString(secrets, "db.password=hunter2\n");
        System.setProperty(com.jujin.freeway.cloud.CloudConfigKeys.SECRET_FILE, secrets.toString());
        try (AppRuntime app = FreewayApp.run(new CloudModule())) {
            SymbolSource symbols = app.get(SymbolSource.class);
            assertEquals("hunter2", symbols.resolve("db.password"));
            assertNull(symbols.resolve("db.username", null), "absent secret stays absent");
        }
    }

    @Test
    void secretOutranksConfigInSymbolResolution() throws Exception {
        Path secrets = dir.resolve("secrets.properties");
        Files.writeString(secrets, "db.password=secret-value\n");
        Path config = dir.resolve("config.properties");
        Files.writeString(config, "db.password=config-value\napp.feature=true\n");
        System.setProperty(com.jujin.freeway.cloud.CloudConfigKeys.SECRET_FILE, secrets.toString());
        System.setProperty("freeway.config.file", config.toString());
        try (AppRuntime app = FreewayApp.run(new CloudModule())) {
            SymbolSource symbols = app.get(SymbolSource.class);
            assertEquals("secret-value", symbols.resolve("db.password"),
                "the secret tier (15) outranks the framework file tier (20)");
            assertEquals("true", symbols.resolve("app.feature"), "non-secret keys fall through to files");
        }
    }

    @Test
    void secretAllowlistConfinesLookupsToDeclaredNames() throws Exception {
        // The provider answers for every name by default, and the store checks
        // the environment first — so an unrelated symbol can silently pick up
        // an environment variable. Declaring keys confines it.
        Path secrets = dir.resolve("secrets.properties");
        Files.writeString(secrets, "db.password=secret-value\napi.token=token-value\n");
        Path config = dir.resolve("config.properties");
        Files.writeString(config, "api.token=config-token\n");
        System.setProperty(com.jujin.freeway.cloud.CloudConfigKeys.SECRET_FILE, secrets.toString());
        System.setProperty("freeway.config.file", config.toString());
        System.setProperty(com.jujin.freeway.cloud.CloudConfigKeys.SECRET_KEYS, "db.password");
        try (AppRuntime app = FreewayApp.run(new CloudModule())) {
            SymbolSource symbols = app.get(SymbolSource.class);
            assertEquals("secret-value", symbols.resolve("db.password"),
                "a declared key still resolves from the secret store");
            assertEquals("config-token", symbols.resolve("api.token"),
                "an undeclared key falls through to files instead of the secret store");
        } finally {
            System.clearProperty(com.jujin.freeway.cloud.CloudConfigKeys.SECRET_KEYS);
            System.clearProperty("freeway.config.file");
        }
    }

    @Test
    void transportSecurityDefaultsToNone() {
        try (AppRuntime app = FreewayApp.run(new CloudModule())) {
            TransportSecurity security = app.get(TransportSecurity.class);
            assertNull(security.sslContext(), "plaintext development default");
        }
    }

    @Test
    void buildsMtlsContextFromKeystoreFiles() throws Exception {
        Path keyStore = dir.resolve("client.p12");
        keytool("-genkeypair", "-alias", "client", "-keyalg", "RSA", "-storetype", "PKCS12",
            "-keystore", keyStore.toString(), "-storepass", "changeit", "-dname", "CN=client", "-validity", "30");

        TransportSecurityDefault security =
            TransportSecurityDefault.fromKeyStore(keyStore, "changeit", keyStore, "changeit");
        assertNotNull(security.sslContext(), "self-signed keystore builds a working TLS context");
    }

    private void keytool(String... args) throws Exception {
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(keytool);
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        int exit = process.waitFor();
        assertEquals(0, exit, "keytool failed: " + new String(process.getInputStream().readAllBytes()));
    }
    /** SymbolSource backed by system properties with ${name:default} expansion
     *  (mirrors the framework contract AuthPropagator relies on). */
    private static com.jujin.freeway.ioc.symbol.SymbolSource sysProps() {
        return new com.jujin.freeway.ioc.symbol.SymbolSource() {
            @Override
            public String resolve(String name) {
                String v = System.getProperty(name);
                if (v == null) {
                    throw new IllegalArgumentException("Unknown symbol: " + name);
                }
                return v;
            }

            @Override
            public String expand(String input) {
                if (input.startsWith("${") && input.endsWith("}")) {
                    String inner = input.substring(2, input.length() - 1);
                    int colon = inner.indexOf(':');
                    String name = colon < 0 ? inner : inner.substring(0, colon);
                    String def = colon < 0 ? null : inner.substring(colon + 1);
                    try {
                        return resolve(name);
                    } catch (IllegalArgumentException e) {
                        return def;
                    }
                }
                return input;
            }
        };
    }
}
