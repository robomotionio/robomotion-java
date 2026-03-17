package com.robomotion.app;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Direct vault API access for CLI mode without gRPC/robot runtime.
 * Auth via env vars ROBOMOTION_API_TOKEN + ROBOMOTION_ROBOT_ID.
 */
public class CLIVaultClient {

    private final String apiBaseURL;
    private final String accessToken;
    private final String robotID;
    private final RSAPrivateKey robotPrivateKey;
    private final Map<String, byte[]> vaultSecrets;

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    public CLIVaultClient() throws Exception {
        accessToken = System.getenv("ROBOMOTION_API_TOKEN");
        if (accessToken == null || accessToken.isEmpty()) {
            throw new Exception("ROBOMOTION_API_TOKEN env var is required");
        }

        robotID = System.getenv("ROBOMOTION_ROBOT_ID");
        if (robotID == null || robotID.isEmpty()) {
            throw new Exception("ROBOMOTION_API_TOKEN set but ROBOMOTION_ROBOT_ID is missing");
        }

        String apiURL = System.getenv("ROBOMOTION_API_URL");
        apiBaseURL = (apiURL != null && !apiURL.isEmpty()) ? apiURL : "https://api.robomotion.io";

        robotPrivateKey = loadRobotPrivateKey(robotID);
        vaultSecrets = loadVaultSecrets(robotID);
    }

    // --- Vault item fetch and decrypt ---

    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchVaultItem(String vaultID, String itemID) throws Exception {
        // 1. Fetch encrypted item from API
        String endpoint = String.format("/v1/vaults.items.get?vault_id=%s&item_id=%s", vaultID, itemID);
        byte[] body = apiGet(endpoint);

        Map<String, Object> resp = mapper.readValue(body, Map.class);
        if (!Boolean.TRUE.equals(resp.get("ok"))) {
            String error = resp.get("error") != null ? resp.get("error").toString() : "";
            if (!error.isEmpty()) {
                throw new Exception(String.format("vault error: %s (vault=%s item=%s)", error, vaultID, itemID));
            }
            throw new Exception(String.format("vault item not found: vault=%s item=%s", vaultID, itemID));
        }

        // 2. Decode encrypted data from hex
        String dataHex = resp.get("data").toString();
        byte[] encData = hexDecode(dataHex);

        // 3. Get IV from item metadata
        Map<String, Object> item = (Map<String, Object>) resp.get("item");
        String ivHex = item.get("iv").toString();

        // 4. Get vault key and decrypt
        byte[] vaultKey = getVaultKey(vaultID);
        byte[] plaintext = decryptAESCBC(vaultKey, encData, ivHex);

        // 5. Parse decrypted JSON into credential map
        return mapper.readValue(plaintext, Map.class);
    }

    // --- Vault key derivation ---

    private byte[] getVaultKey(String vaultID) throws Exception {
        // Fetch vault metadata for encrypted vault key
        Map<String, Object> vault = fetchVault(vaultID);

        // RSA-OAEP decrypt the vault key
        String encVaultKeyB64 = vault.get("enc_vault_key").toString();
        byte[] encVaultKey = Base64.getDecoder().decode(encVaultKeyB64);
        byte[] vaultKey = rsaOAEPDecrypt(robotPrivateKey, encVaultKey);

        // Get and decrypt secret key from .vaults files
        byte[] encSecretKey = getSecretKey(vaultID);
        byte[] secretKey = rsaOAEPDecrypt(robotPrivateKey, encSecretKey);

        // XOR vault key with secret key
        if (secretKey.length != vaultKey.length) {
            throw new Exception("key length mismatch");
        }
        byte[] xored = new byte[secretKey.length];
        for (int i = 0; i < secretKey.length; i++) {
            xored[i] = (byte) (secretKey[i] ^ vaultKey[i]);
        }

        return xored;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchVault(String vaultID) throws Exception {
        String endpoint = "/v1/vaults.list";
        if (robotID != null && !robotID.isEmpty()) {
            endpoint += "?robot_id=" + robotID;
        }
        byte[] body = apiGet(endpoint);

        Map<String, Object> resp = mapper.readValue(body, Map.class);
        List<Map<String, Object>> vaults = (List<Map<String, Object>>) resp.get("vaults");

        if (vaults != null) {
            for (Map<String, Object> v : vaults) {
                if (vaultID.equals(v.get("id"))) {
                    return v;
                }
            }
        }

        throw new Exception("vault " + vaultID + " not found");
    }

    private byte[] getSecretKey(String vaultID) throws Exception {
        byte[] secret = vaultSecrets.get(vaultID);
        if (secret != null) {
            return secret;
        }
        throw new Exception("no vault secret for " + vaultID + " in .vaults files");
    }

    // --- Name resolution ---

    @SuppressWarnings("unchecked")
    public String resolveVaultByName(String name) throws Exception {
        String endpoint = "/v1/vaults.list";
        if (robotID != null && !robotID.isEmpty()) {
            endpoint += "?robot_id=" + robotID;
        }
        byte[] body = apiGet(endpoint);

        Map<String, Object> resp = mapper.readValue(body, Map.class);
        List<Map<String, Object>> vaults = (List<Map<String, Object>>) resp.get("vaults");

        List<Map<String, Object>> matches = new java.util.ArrayList<>();
        if (vaults != null) {
            for (Map<String, Object> v : vaults) {
                if (name.equalsIgnoreCase(v.get("name").toString())) {
                    matches.add(v);
                }
            }
        }

        if (matches.isEmpty()) {
            throw new Exception("no vault found with name \"" + name + "\"");
        }
        if (matches.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("ambiguous vault name \"%s\" matches %d vaults:", name, matches.size()));
            for (Map<String, Object> m : matches) {
                sb.append(String.format("\n  --vault-id=%s  (%s)", m.get("id"), m.get("name")));
            }
            throw new Exception(sb.toString());
        }
        return matches.get(0).get("id").toString();
    }

    @SuppressWarnings("unchecked")
    public String resolveItemByName(String vaultID, String name) throws Exception {
        String endpoint = String.format("/v1/vaults.items.list?vault_id=%s", vaultID);
        byte[] body = apiGet(endpoint);

        Map<String, Object> resp = mapper.readValue(body, Map.class);
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.get("items");

        List<Map<String, Object>> matches = new java.util.ArrayList<>();
        if (items != null) {
            for (Map<String, Object> item : items) {
                if (name.equalsIgnoreCase(item.get("name").toString())) {
                    matches.add(item);
                }
            }
        }

        if (matches.isEmpty()) {
            throw new Exception(
                    String.format("no item found with name \"%s\" in vault %s", name, vaultID));
        }
        if (matches.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("ambiguous item name \"%s\" matches %d items:", name, matches.size()));
            for (Map<String, Object> m : matches) {
                sb.append(String.format("\n  --item-id=%s  (%s)", m.get("id"), m.get("name")));
            }
            throw new Exception(sb.toString());
        }
        return matches.get(0).get("id").toString();
    }

    // --- HTTP ---

    private byte[] apiGet(String endpoint) throws Exception {
        String url = apiBaseURL.replaceAll("/+$", "") + endpoint;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() == 401) {
            throw new Exception("authentication expired; run 'robomotion login' again");
        }
        if (response.statusCode() != 200) {
            throw new Exception(String.format("API error %d: %s",
                    response.statusCode(), new String(response.body())));
        }

        return response.body();
    }

    // --- Crypto ---

    private static RSAPrivateKey loadRobotPrivateKey(String robotID) throws Exception {
        Path keysDir = defaultKeysDir();
        Path privPath = keysDir.resolve(robotID);

        byte[] pemData = Files.readAllBytes(privPath);
        String pem = new String(pemData).trim();

        // Strip PEM headers/footers and decode base64
        String base64Key = pem
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] derBytes = Base64.getDecoder().decode(base64Key);

        // Try PKCS#8 first
        try {
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(derBytes);
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(keySpec);
        } catch (Exception e) {
            // Fall through to PKCS#1 handling
        }

        // PKCS#1 format: wrap DER bytes into PKCS#8 structure
        byte[] pkcs8Bytes = wrapPKCS1InPKCS8(derBytes);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(pkcs8Bytes);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(keySpec);
    }

    /**
     * Wraps PKCS#1 RSA private key DER bytes into PKCS#8 format.
     * Uses a fixed 26-byte ASN.1 prefix for RSA keys.
     */
    private static byte[] wrapPKCS1InPKCS8(byte[] pkcs1Bytes) {
        // PKCS#8 header for RSA: SEQUENCE { version, AlgorithmIdentifier { OID rsaEncryption, NULL }, OCTET STRING }
        byte[] pkcs8Header = new byte[] {
                0x30, (byte) 0x82, 0x00, 0x00,  // SEQUENCE (length placeholder)
                0x02, 0x01, 0x00,                // INTEGER version = 0
                0x30, 0x0D,                      // SEQUENCE (AlgorithmIdentifier)
                0x06, 0x09,                      // OID
                0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01,  // rsaEncryption
                0x05, 0x00,                      // NULL
                0x04, (byte) 0x82, 0x00, 0x00   // OCTET STRING (length placeholder)
        };

        // Set OCTET STRING length
        int octetLen = pkcs1Bytes.length;
        pkcs8Header[pkcs8Header.length - 2] = (byte) ((octetLen >> 8) & 0xFF);
        pkcs8Header[pkcs8Header.length - 1] = (byte) (octetLen & 0xFF);

        // Set outer SEQUENCE length (everything after the first 4 bytes)
        int seqLen = pkcs8Header.length - 4 + pkcs1Bytes.length;
        pkcs8Header[2] = (byte) ((seqLen >> 8) & 0xFF);
        pkcs8Header[3] = (byte) (seqLen & 0xFF);

        byte[] result = new byte[pkcs8Header.length + pkcs1Bytes.length];
        System.arraycopy(pkcs8Header, 0, result, 0, pkcs8Header.length);
        System.arraycopy(pkcs1Bytes, 0, result, pkcs8Header.length, pkcs1Bytes.length);

        return result;
    }

    private static Map<String, byte[]> loadVaultSecrets(String robotID) {
        Map<String, byte[]> secrets = new HashMap<>();
        Path keysDir = defaultKeysDir();

        // Try robot-specific file first
        Path robotFile = keysDir.resolve(robotID + ".vaults");
        if (Files.exists(robotFile)) {
            try {
                String data = Files.readString(robotFile);
                parseVaultsFile(data, secrets);
                return secrets;
            } catch (IOException e) {
                // Fall through
            }
        }

        // Fallback: read all .vaults files
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(keysDir, "*.vaults")) {
            for (Path entry : stream) {
                try {
                    String data = Files.readString(entry);
                    parseVaultsFile(data, secrets);
                } catch (IOException e) {
                    // Skip
                }
            }
        } catch (IOException e) {
            // No .vaults files found
        }

        return secrets;
    }

    /**
     * Parses a simple YAML vaults file.
     * Format:
     *   vaults:
     *     <uuid>: <base64>
     */
    private static void parseVaultsFile(String data, Map<String, byte[]> secrets) {
        boolean inVaults = false;
        for (String line : data.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.equals("---")) {
                continue;
            }
            if (trimmed.equals("vaults:")) {
                inVaults = true;
                continue;
            }
            if (!inVaults) {
                continue;
            }
            // Entries are indented
            if (!line.startsWith(" ") && !line.startsWith("\t")) {
                break; // new top-level key
            }
            int idx = trimmed.indexOf(": ");
            if (idx < 0) {
                continue;
            }
            String vaultID = trimmed.substring(0, idx);
            String b64Value = trimmed.substring(idx + 2);
            try {
                byte[] decoded = Base64.getDecoder().decode(b64Value);
                secrets.put(vaultID, decoded);
            } catch (Exception e) {
                // Skip invalid entries
            }
        }
    }

    private static Path defaultKeysDir() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("windows")) {
            String homeDrive = System.getenv("HOMEDRIVE");
            String homePath = System.getenv("HOMEPATH");
            String localHome = (homeDrive != null ? homeDrive : "") + (homePath != null ? homePath : "");
            if (localHome.isEmpty()) {
                localHome = System.getenv("USERPROFILE");
            }
            if (localHome != null && !localHome.isEmpty()) {
                return Paths.get(localHome, "AppData", "Local", "Robomotion", "keys");
            }
        }
        return Paths.get(home, ".config", "robomotion", "keys");
    }

    private static byte[] rsaOAEPDecrypt(RSAPrivateKey privateKey, byte[] encrypted) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return cipher.doFinal(encrypted);
    }

    private static byte[] decryptAESCBC(byte[] key, byte[] data, String ivHex) throws Exception {
        byte[] iv = hexDecode(ivHex);

        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] plaintext = cipher.doFinal(data);

        return pkcs7Unpad(plaintext);
    }

    private static byte[] pkcs7Unpad(byte[] data) {
        if (data == null || data.length == 0) return data;
        int padding = data[data.length - 1] & 0xFF;
        if (padding > data.length || padding == 0) return data;
        return Arrays.copyOf(data, data.length - padding);
    }

    private static byte[] hexDecode(String hex) {
        int len = hex.length();
        byte[] result = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            result[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return result;
    }
}
