package com.robomotion.testing;

import java.util.Map;

/**
 * Interface for providing test runtime helper functionality.
 * This allows tests to provide mock credentials without needing the full gRPC runtime.
 */
public interface TestRuntimeHelper {

    /**
     * Gets a vault item for testing.
     *
     * @param vaultId The vault ID
     * @param itemId The item ID
     * @return The credential data as a map, or null if not found
     */
    Map<String, Object> getVaultItem(String vaultId, String itemId);

    /**
     * Sets a vault item for testing.
     *
     * @param vaultId The vault ID
     * @param itemId The item ID
     * @param data The credential data
     * @return The updated credential data as a map
     */
    Map<String, Object> setVaultItem(String vaultId, String itemId, byte[] data);
}
