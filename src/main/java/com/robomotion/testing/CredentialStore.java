package com.robomotion.testing;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CredentialStore provides mock credential storage for testing nodes that require vault access.
 * <p>
 * Example usage:
 * <pre>{@code
 * CredentialStore store = new CredentialStore();
 * store.setAPIKey("api_key", "AIza...");
 * store.setLogin("my_login", "user@example.com", "password123");
 * TestRuntime.initCredentials(store);
 * }</pre>
 */
public class CredentialStore {

    private final Map<String, Map<String, Object>> credentials;
    private static volatile CredentialStore instance;

    /**
     * Creates a new empty CredentialStore.
     */
    public CredentialStore() {
        this.credentials = new ConcurrentHashMap<>();
    }

    /**
     * Gets the global credential store instance for testing.
     *
     * @return The global instance, or null if not set
     */
    public static CredentialStore getInstance() {
        return instance;
    }

    /**
     * Sets the global credential store instance.
     *
     * @param store The store to set as global instance
     */
    public static void setInstance(CredentialStore store) {
        instance = store;
    }

    /**
     * Sets an API key credential (category 4).
     *
     * @param name The credential name
     * @param apiKey The API key value
     * @return This CredentialStore for chaining
     */
    public CredentialStore setAPIKey(String name, String apiKey) {
        Map<String, Object> cred = new HashMap<>();
        cred.put("value", apiKey);
        credentials.put(name, cred);
        return this;
    }

    /**
     * Sets a login credential (category 1).
     *
     * @param name The credential name
     * @param username The username
     * @param password The password
     * @return This CredentialStore for chaining
     */
    public CredentialStore setLogin(String name, String username, String password) {
        Map<String, Object> cred = new HashMap<>();
        cred.put("username", username);
        cred.put("password", password);
        credentials.put(name, cred);
        return this;
    }

    /**
     * Sets a login credential with server.
     *
     * @param name The credential name
     * @param username The username
     * @param password The password
     * @param server The server URL
     * @return This CredentialStore for chaining
     */
    public CredentialStore setLogin(String name, String username, String password, String server) {
        Map<String, Object> cred = new HashMap<>();
        cred.put("username", username);
        cred.put("password", password);
        cred.put("server", server);
        credentials.put(name, cred);
        return this;
    }

    /**
     * Sets a database credential (category 5).
     *
     * @param name The credential name
     * @param config The database configuration
     * @return This CredentialStore for chaining
     */
    public CredentialStore setDatabase(String name, DatabaseCredential config) {
        Map<String, Object> cred = new HashMap<>();
        cred.put("server", config.getServer());
        cred.put("port", config.getPort());
        cred.put("database", config.getDatabase());
        cred.put("username", config.getUsername());
        cred.put("password", config.getPassword());
        credentials.put(name, cred);
        return this;
    }

    /**
     * Sets a document credential (category 6).
     *
     * @param name The credential name
     * @param content The document content
     * @return This CredentialStore for chaining
     */
    public CredentialStore setDocument(String name, String content) {
        Map<String, Object> cred = new HashMap<>();
        cred.put("content", content);
        credentials.put(name, cred);
        return this;
    }

    /**
     * Sets a document credential with filename.
     *
     * @param name The credential name
     * @param filename The document filename
     * @param content The document content
     * @return This CredentialStore for chaining
     */
    public CredentialStore setDocument(String name, String filename, String content) {
        Map<String, Object> cred = new HashMap<>();
        cred.put("filename", filename);
        cred.put("content", content);
        credentials.put(name, cred);
        return this;
    }

    /**
     * Sets a custom credential with arbitrary data.
     *
     * @param name The credential name
     * @param data The credential data
     * @return This CredentialStore for chaining
     */
    public CredentialStore setCustom(String name, Map<String, Object> data) {
        credentials.put(name, new HashMap<>(data));
        return this;
    }

    /**
     * Adds a credential (alias for setCustom).
     *
     * @param name The credential name
     * @param data The credential data
     * @return This CredentialStore for chaining
     */
    public CredentialStore addCredential(String name, Map<String, Object> data) {
        return setCustom(name, data);
    }

    /**
     * Gets a credential by name.
     *
     * @param name The credential name
     * @return The credential data, or null if not found
     */
    public Map<String, Object> get(String name) {
        Map<String, Object> cred = credentials.get(name);
        if (cred != null) {
            return new HashMap<>(cred);
        }
        return null;
    }

    /**
     * Checks if a credential exists.
     *
     * @param name The credential name
     * @return True if exists
     */
    public boolean has(String name) {
        return credentials.containsKey(name);
    }

    /**
     * Loads credentials from environment variables with a prefix.
     * Searches for: PREFIX_API_KEY, PREFIX_KEY, PREFIX_TOKEN, PREFIX_VALUE, etc.
     *
     * @param prefix Environment variable prefix (e.g., "GEMINI")
     * @param credName Name to store the credential under
     * @return This CredentialStore for chaining
     */
    public CredentialStore loadFromEnv(String prefix, String credName) {
        Map<String, Object> data = new HashMap<>();
        boolean found = false;

        // Map environment variable suffixes to credential keys
        Map<String, String> mappings = Map.ofEntries(
                Map.entry("_API_KEY", "value"),
                Map.entry("_KEY", "value"),
                Map.entry("_TOKEN", "value"),
                Map.entry("_VALUE", "value"),
                Map.entry("_USERNAME", "username"),
                Map.entry("_USER", "username"),
                Map.entry("_PASSWORD", "password"),
                Map.entry("_PASS", "password"),
                Map.entry("_SERVER", "server"),
                Map.entry("_HOST", "server"),
                Map.entry("_PORT", "port"),
                Map.entry("_DATABASE", "database"),
                Map.entry("_DB", "database"),
                Map.entry("_CONTENT", "content")
        );

        String upperPrefix = prefix.toUpperCase();

        for (Map.Entry<String, String> mapping : mappings.entrySet()) {
            String envKey = upperPrefix + mapping.getKey();
            String envValue = System.getenv(envKey);

            if (envValue != null && !envValue.isEmpty()) {
                String credKey = mapping.getValue();

                // Handle port as integer
                if (credKey.equals("port")) {
                    try {
                        data.put(credKey, Integer.parseInt(envValue));
                    } catch (NumberFormatException e) {
                        data.put(credKey, envValue);
                    }
                } else {
                    data.put(credKey, envValue);
                }
                found = true;
            }
        }

        if (found) {
            credentials.put(credName, data);
        }

        return this;
    }

    /**
     * Clears all credentials.
     */
    public void clear() {
        credentials.clear();
    }

    /**
     * Gets a vault item by vault ID and item ID.
     * Used internally by the mock runtime helper.
     *
     * @param vaultId The vault ID
     * @param itemId The item ID
     * @return The credential data, or null if not found
     */
    Map<String, Object> getVaultItem(String vaultId, String itemId) {
        // Try itemId first
        Map<String, Object> item = get(itemId);
        if (item != null) {
            return item;
        }

        // Try vaultId
        item = get(vaultId);
        if (item != null) {
            return item;
        }

        // Try combined key
        String combined = vaultId + ":" + itemId;
        return get(combined);
    }
}
