package com.robomotion.testing;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * DotEnv provides utilities for loading environment variables from .env files.
 * <p>
 * Example usage:
 * <pre>{@code
 * // Load .env file from current directory
 * DotEnv.load(".env");
 *
 * // Load from specific path
 * DotEnv.load("/path/to/.env");
 *
 * // Load from parent directory (common for test projects)
 * DotEnv.load("../.env");
 * }</pre>
 */
public class DotEnv {

    private DotEnv() {
        // Utility class
    }

    /**
     * Loads environment variables from a .env file.
     * Does not fail if the file doesn't exist.
     *
     * @param filename Path to the .env file
     * @return True if file was loaded successfully, false if not found or error
     */
    public static boolean load(String filename) {
        Path path = Paths.get(filename);

        if (!Files.exists(path)) {
            return false;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(path.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // Find the first = sign
                int eqIndex = line.indexOf('=');
                if (eqIndex <= 0) {
                    continue;
                }

                String key = line.substring(0, eqIndex).trim();
                String value = line.substring(eqIndex + 1).trim();

                // Remove surrounding quotes
                if (value.length() >= 2) {
                    if ((value.startsWith("\"") && value.endsWith("\"")) ||
                            (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                }

                // Set environment variable using ProcessBuilder workaround
                // Note: System.setenv is not available, but we can use a property or
                // the tests can access it via a utility method
                setEnvironmentVariable(key, value);
            }

            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Sets an environment variable.
     * Uses system properties as a fallback since Java doesn't allow setting env vars directly.
     *
     * @param key The variable name
     * @param value The variable value
     */
    private static void setEnvironmentVariable(String key, String value) {
        // Store in system properties as a workaround
        System.setProperty("env." + key, value);

        // Also try to set via reflection (works in some JVM implementations)
        try {
            var processEnvironment = Class.forName("java.lang.ProcessEnvironment");
            var theEnvironment = processEnvironment.getDeclaredField("theEnvironment");
            theEnvironment.setAccessible(true);
            @SuppressWarnings("unchecked")
            var env = (java.util.Map<String, String>) theEnvironment.get(null);
            env.put(key, value);

            var theCaseInsensitiveEnvironment = processEnvironment.getDeclaredField("theCaseInsensitiveEnvironment");
            theCaseInsensitiveEnvironment.setAccessible(true);
            @SuppressWarnings("unchecked")
            var cienv = (java.util.Map<String, String>) theCaseInsensitiveEnvironment.get(null);
            cienv.put(key, value);
        } catch (Exception e) {
            // Reflection approach failed, fall back to system properties only
        }
    }

    /**
     * Gets an environment variable, checking both System.getenv and our property fallback.
     *
     * @param key The variable name
     * @return The value, or null if not found
     */
    public static String get(String key) {
        // Try actual environment variable first
        String value = System.getenv(key);
        if (value != null) {
            return value;
        }

        // Fall back to system property
        return System.getProperty("env." + key);
    }

    /**
     * Gets an environment variable with a default value.
     *
     * @param key The variable name
     * @param defaultValue The default value if not found
     * @return The value, or defaultValue if not found
     */
    public static String get(String key, String defaultValue) {
        String value = get(key);
        return value != null ? value : defaultValue;
    }
}
