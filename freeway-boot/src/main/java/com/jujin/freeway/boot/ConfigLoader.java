package com.jujin.freeway.boot;

/**
 * Strategy for loading application configuration.
 * <p>
 * The default implementation reads the standard config cascade:
 * {@code application.properties} → {@code application.json} →
 * profile variants → environment variables → CLI args.
 * <p>
 * Implement this interface to supply configuration from custom sources
 * (YAML files, remote config servers, etc.).
 */
@FunctionalInterface
public interface ConfigLoader {
    /**
     * Load application configuration from available sources.
     *
     * @param loader the class loader to use for resource lookup
     * @param args   command-line arguments (may be empty, never null)
     * @return the loaded configuration
     */
    AppConfig load(ClassLoader loader, String... args);
}
