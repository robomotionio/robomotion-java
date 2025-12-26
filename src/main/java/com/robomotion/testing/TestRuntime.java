package com.robomotion.testing;

import com.robomotion.app.Runtime;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * TestRuntime provides methods to initialize and clean up mock credentials for testing.
 * <p>
 * Example usage in a JUnit test:
 * <pre>{@code
 * public class MyNodeTest {
 *     private static CredentialStore credStore;
 *
 *     @BeforeAll
 *     static void setup() {
 *         // Load .env file
 *         DotEnv.load(".env");
 *
 *         // Initialize credential store
 *         credStore = new CredentialStore();
 *         credStore.loadFromEnv("GEMINI", "api_key");
 *
 *         // Initialize runtime with mock credentials
 *         TestRuntime.initCredentials(credStore);
 *     }
 *
 *     @AfterAll
 *     static void cleanup() {
 *         TestRuntime.clearCredentials();
 *     }
 *
 *     @Test
 *     void testNode() {
 *         // Skip if no credentials
 *         if (!credStore.has("api_key")) {
 *             return;
 *         }
 *
 *         MyNode node = new MyNode();
 *         Quick q = new Quick(node);
 *         q.setCredential("OptApiKey", "api_key", "api_key");
 *         // ...
 *     }
 * }
 * }</pre>
 */
public class TestRuntime {

    private static TestRuntimeHelper testHelper;

    private TestRuntime() {
        // Utility class
    }

    /**
     * Initializes the runtime with mock credentials from the given store.
     *
     * @param store The credential store to use
     */
    public static void initCredentials(CredentialStore store) {
        CredentialStore.setInstance(store);
        testHelper = new MockRuntimeHelper(store);

        // Set the test helper in Runtime class via reflection
        setRuntimeTestHelper(testHelper);
    }

    /**
     * Clears mock credentials.
     */
    public static void clearCredentials() {
        CredentialStore.setInstance(null);
        testHelper = null;

        // Clear the test helper in Runtime class
        setRuntimeTestHelper(null);
    }

    /**
     * Gets the current test helper.
     *
     * @return The test helper, or null if not initialized
     */
    public static TestRuntimeHelper getTestHelper() {
        return testHelper;
    }

    /**
     * Sets the test helper in the Runtime class using reflection.
     */
    private static void setRuntimeTestHelper(TestRuntimeHelper helper) {
        try {
            Field field = Runtime.class.getDeclaredField("testHelper");
            field.setAccessible(true);
            field.set(null, helper);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // Field doesn't exist yet - this is OK, we'll handle it in Runtime
        }
    }

    /**
     * Internal mock runtime helper implementation.
     */
    private static class MockRuntimeHelper implements TestRuntimeHelper {
        private final CredentialStore store;

        public MockRuntimeHelper(CredentialStore store) {
            this.store = store;
        }

        @Override
        public Map<String, Object> getVaultItem(String vaultId, String itemId) {
            if (store == null) {
                return null;
            }
            return store.getVaultItem(vaultId, itemId);
        }

        @Override
        public Map<String, Object> setVaultItem(String vaultId, String itemId, byte[] data) {
            // Not implemented for testing - just return empty map
            return Map.of();
        }
    }
}
