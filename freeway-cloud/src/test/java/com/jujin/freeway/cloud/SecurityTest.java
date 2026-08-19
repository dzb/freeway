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

    @TempDir
    Path dir;

    @AfterEach
    void clearProperties() {
        System.clearProperty(com.jujin.freeway.cloud.CloudConfigKeys.SECRET_FILE);
        System.clearProperty(com.jujin.freeway.cloud.CloudConfigKeys.CONFIG_FILE);
    }

    @Test
    void authPropagatorRoundTripsPrincipalAndRoles() {
        AuthPropagator propagator = new AuthPropagator();
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
        System.setProperty(com.jujin.freeway.cloud.CloudConfigKeys.CONFIG_FILE, config.toString());
        try (AppRuntime app = FreewayApp.run(new CloudModule())) {
            SymbolSource symbols = app.get(SymbolSource.class);
            assertEquals("secret-value", symbols.resolve("db.password"),
                "the secret provider is registered before the config provider");
            assertEquals("true", symbols.resolve("app.feature"), "non-secret keys fall through to config");
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
}
