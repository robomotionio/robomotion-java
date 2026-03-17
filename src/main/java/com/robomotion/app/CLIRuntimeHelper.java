package com.robomotion.app;

import java.util.Map;

import com.robomotion.testing.TestRuntimeHelper;

/**
 * Implements TestRuntimeHelper for CLI mode without gRPC.
 * In CLI mode, credentials come from vault flags; all other runtime
 * operations are no-ops or stubs.
 */
public class CLIRuntimeHelper implements TestRuntimeHelper {

    private Map<String, Object> credentials;

    @Override
    public Map<String, Object> getVaultItem(String vaultId, String itemId) {
        return credentials;
    }

    @Override
    public Map<String, Object> setVaultItem(String vaultId, String itemId, byte[] data) {
        throw new UnsupportedOperationException("setVaultItem not supported in CLI mode");
    }

    public void setCredentials(Map<String, Object> creds) {
        this.credentials = creds;
    }
}
