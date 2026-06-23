package com.jujin.freeway.ioc;

import org.slf4j.Logger;

/**
 * Owner-aware logger factory. Injected loggers are named after the
 * declaring service type by default.
 *
 * <p>Example:
 * <pre>{@code
 * @Inject private Logger log;           // name = "com.example.UserService"
 * @Inject("audit") private Logger log;  // name = "audit"
 * }</pre>
 */
public interface LoggerSource {
    Logger get(Class<?> ownerType);

    Logger get(String name);
}
