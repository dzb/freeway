package com.jujin.freeway.ioc;

import java.util.concurrent.atomic.AtomicReference;

import com.jujin.freeway.ioc.extension.Contribution;

/**
 * Shared scaffolding for the split container test files: system-property
 * keys with save/restore, and cross-test mutable handles. Static state is
 * safe — JUnit runs these classes sequentially on one thread by default.
 */
final class FreewayTestSupport {
    static final String PORT_KEY = "freeway.test.port";
    static final String NAME_KEY = "freeway.test.name";
    static final String ENDPOINT_KEY = "freeway.test.endpoint";
    static final String TIMEOUT_KEY = "freeway.test.timeout";
    static final String NEST_KEY = "freeway.test.nested";
    static final String LIST_KEY = "freeway.test.list";
    static final String APP_NAME_KEY = "freeway.test.app.name";

    /** Handle shared between class-contribution ordering tests. */
    static final AtomicReference<Contribution> postReadHandle =
        new AtomicReference<>();

    private static String previousPort;
    private static String previousName;
    private static String previousEndpoint;
    private static String previousTimeout;
    private static String previousAppName;

    private FreewayTestSupport() {}

    static void capture() {
        previousPort = System.getProperty(PORT_KEY);
        previousName = System.getProperty(NAME_KEY);
        previousEndpoint = System.getProperty(ENDPOINT_KEY);
        previousTimeout = System.getProperty(TIMEOUT_KEY);
        previousAppName = System.getProperty(APP_NAME_KEY);
        GreeterImpl.created.set(0);
    }

    static void restore() {
        restoreProperty(PORT_KEY, previousPort);
        restoreProperty(NAME_KEY, previousName);
        restoreProperty(ENDPOINT_KEY, previousEndpoint);
        restoreProperty(TIMEOUT_KEY, previousTimeout);
        restoreProperty(APP_NAME_KEY, previousAppName);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
