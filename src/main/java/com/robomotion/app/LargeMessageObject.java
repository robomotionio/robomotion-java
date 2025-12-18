package com.robomotion.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * LargeMessageObject (LMO) provides support for handling large messages efficiently.
 * When a message exceeds a certain size limit, it is stored in a temporary file
 * and a reference is passed instead of the actual data.
 */
public class LargeMessageObject {

    public static final int LMO_MAGIC = 0x1343B7E;
    public static final int LMO_LIMIT = 256 * 1024; // 256KB
    public static final int LMO_VERSION = 0x01;
    public static final int LMO_HEAD = 100;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String BASE32_ALPHABET = "ybndrfg8ejkmcpqxot1uwisza345h769";
    private static final SecureRandom random = new SecureRandom();

    @JsonProperty("magic")
    private int magic;

    @JsonProperty("version")
    private int version;

    @JsonProperty("id")
    private String id;

    @JsonProperty("head")
    private String head;

    @JsonProperty("size")
    private long size;

    @JsonProperty("data")
    private Object data;

    public LargeMessageObject() {
    }

    public LargeMessageObject(int magic, int version, String id, String head, long size, Object data) {
        this.magic = magic;
        this.version = version;
        this.id = id;
        this.head = head;
        this.size = size;
        this.data = data;
    }

    // Getters and setters
    public int getMagic() {
        return magic;
    }

    public void setMagic(int magic) {
        this.magic = magic;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHead() {
        return head;
    }

    public void setHead(String head) {
        this.head = head;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    /**
     * Extracts the underlying data from a LargeMessageObject after deserializing it.
     */
    public Object getValue() {
        return data;
    }

    /**
     * Generates a new unique ID for LMO files.
     */
    public static String newId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);

        StringBuilder sb = new StringBuilder(26);
        int bits = 0;
        int value = 0;

        for (byte b : bytes) {
            value = (value << 8) | (b & 0xff);
            bits += 8;

            while (bits >= 5) {
                int index = (value >> (bits - 5)) & 0x1f;
                sb.append(BASE32_ALPHABET.charAt(index));
                bits -= 5;
            }
        }

        if (bits > 0) {
            int index = (value << (5 - bits)) & 0x1f;
            sb.append(BASE32_ALPHABET.charAt(index));
        }

        return sb.substring(0, 26);
    }

    /**
     * Gets the temp directory for LMO files.
     */
    private static Path getTempDir() throws RuntimeNotInitializedException {
        String robotId = Runtime.GetRobotID();
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase();

        String basePath;
        if (os.contains("win")) {
            basePath = home + "\\AppData\\Local\\Robomotion\\temp";
        } else {
            basePath = home + "/.config/robomotion/temp";
        }

        return Paths.get(basePath, "robots", robotId);
    }

    /**
     * Serializes a value to a LargeMessageObject if its size exceeds the LMO_LIMIT.
     * Returns null if the value doesn't need to be stored as LMO.
     *
     * @param value The value to serialize
     * @return The LMO reference (with data set to null for storage), or null if not needed
     */
    public static LargeMessageObject serialize(Object value) throws Exception {
        if (!Runtime.IsLMOCapable()) {
            return null;
        }

        byte[] data = objectMapper.writeValueAsBytes(value);
        int dataLen = data.length;

        if (dataLen < LMO_LIMIT) {
            return null;
        }

        String id = newId();
        String head = new String(data, 0, Math.min(LMO_HEAD, dataLen), StandardCharsets.UTF_8);

        LargeMessageObject lmo = new LargeMessageObject(
                LMO_MAGIC,
                LMO_VERSION,
                id,
                head,
                dataLen,
                value
        );

        // Save to file
        Path dir = getTempDir();
        Files.createDirectories(dir);
        Path file = dir.resolve(id + ".lmo");

        byte[] lmoJson = objectMapper.writeValueAsBytes(lmo);
        Files.write(file, lmoJson);

        // Return reference without data
        lmo.data = null;
        return lmo;
    }

    /**
     * Deserializes a LargeMessageObject from its stored file.
     *
     * @param id The LMO ID
     * @return The deserialized LMO with data
     */
    public static LargeMessageObject deserialize(String id) throws Exception {
        Path dir = getTempDir();
        Path file = dir.resolve(id + ".lmo");

        byte[] content = Files.readAllBytes(file);
        return objectMapper.readValue(content, LargeMessageObject.class);
    }

    /**
     * Deserializes a LargeMessageObject from a map representation.
     */
    @SuppressWarnings("unchecked")
    public static LargeMessageObject deserializeFromMap(Map<String, Object> map) throws Exception {
        String id = (String) map.get("id");
        if (id == null) {
            throw new IllegalArgumentException("Failed to deserialize LMO: missing id");
        }
        return deserialize(id);
    }

    /**
     * Packs message bytes, converting large values to LMO references.
     */
    @SuppressWarnings("unchecked")
    public static byte[] packMessageBytes(byte[] inMsg) throws Exception {
        if (!Runtime.IsLMOCapable() || inMsg.length < LMO_LIMIT) {
            return inMsg;
        }

        Map<String, Object> msg = objectMapper.readValue(inMsg, Map.class);
        packMessage(msg);
        return objectMapper.writeValueAsBytes(msg);
    }

    /**
     * Packs a message map, converting large values to LMO references.
     */
    public static void packMessage(Map<String, Object> msg) throws Exception {
        if (!Runtime.IsLMOCapable()) {
            return;
        }

        for (String key : msg.keySet()) {
            Object value = msg.get(key);
            LargeMessageObject lmo = serialize(value);
            if (lmo != null) {
                // Convert LMO to map for storage
                Map<String, Object> lmoMap = new HashMap<>();
                lmoMap.put("magic", lmo.magic);
                lmoMap.put("version", lmo.version);
                lmoMap.put("id", lmo.id);
                lmoMap.put("head", lmo.head);
                lmoMap.put("size", lmo.size);
                msg.put(key, lmoMap);
            }
        }
    }

    /**
     * Unpacks message bytes, resolving LMO references to actual values.
     */
    @SuppressWarnings("unchecked")
    public static byte[] unpackMessageBytes(byte[] inMsg) throws Exception {
        Map<String, Object> msg = new HashMap<>();
        unpackMessage(inMsg, msg);
        return objectMapper.writeValueAsBytes(msg);
    }

    /**
     * Unpacks a message, resolving LMO references to actual values.
     */
    @SuppressWarnings("unchecked")
    public static void unpackMessage(byte[] inMsg, Map<String, Object> msg) throws Exception {
        if (!Runtime.IsLMOCapable()) {
            return;
        }

        Map<String, Object> parsed = objectMapper.readValue(inMsg, Map.class);
        msg.putAll(parsed);

        for (String key : msg.keySet()) {
            Object value = msg.get(key);

            if (!(value instanceof Map)) {
                continue;
            }

            Map<String, Object> mapValue = (Map<String, Object>) value;
            Object magicValue = mapValue.get("magic");

            if (magicValue == null) {
                continue;
            }

            long magic;
            if (magicValue instanceof Number) {
                magic = ((Number) magicValue).longValue();
            } else {
                continue;
            }

            if (magic != LMO_MAGIC) {
                continue;
            }

            String id = (String) mapValue.get("id");
            if (id == null) {
                continue;
            }

            LargeMessageObject lmo = deserialize(id);
            msg.put(key, lmo.getData());
        }
    }

    /**
     * Checks if a value is an LMO reference.
     */
    @SuppressWarnings("unchecked")
    public static boolean isLMO(Object value) {
        if (!Runtime.IsLMOCapable()) {
            return false;
        }

        if (value instanceof Map) {
            Map<String, Object> mapVal = (Map<String, Object>) value;
            Object magicVal = mapVal.get("magic");
            if (magicVal instanceof Number) {
                return ((Number) magicVal).longValue() == LMO_MAGIC;
            }
        }

        return false;
    }

    /**
     * Deletes an LMO file by ID.
     */
    public static void deleteById(String id) {
        try {
            Path dir = getTempDir();
            Path file = dir.resolve(id + ".lmo");
            Files.deleteIfExists(file);
        } catch (Exception e) {
            // Ignore
        }
    }
}
