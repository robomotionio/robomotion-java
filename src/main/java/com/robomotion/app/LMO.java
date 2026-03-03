package com.robomotion.app;

import com.github.luben.zstd.Zstd;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * LMO provides BlobRef resolution for the content-addressed blob store.
 * The Go runtime handles packing (extracting large fields as blobs).
 * The Java SDK only needs to resolve BlobRefs in incoming messages.
 */
public class LMO {

    public static final int MAGIC = 20260301;

    /**
     * Walks the JSON payload and replaces every BlobRef with its decompressed
     * blob data. Returns the original data unchanged if no BlobRefs are found.
     */
    public static byte[] resolveAll(byte[] data) {
        if (data == null || data.length == 0) {
            return data;
        }

        String json;
        try {
            json = new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return data;
        }

        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (Exception e) {
            return data;
        }

        if (!parsed.isJsonObject()) {
            return data;
        }

        JsonObject root = parsed.getAsJsonObject();
        boolean modified = false;

        for (Map.Entry<String, JsonElement> entry : root.entrySet().toArray(new Map.Entry[0])) {
            JsonElement value = entry.getValue();
            JsonElement resolved = resolveValue(value);
            if (resolved != null) {
                root.add(entry.getKey(), resolved);
                modified = true;
            }
        }

        if (!modified) {
            return data;
        }

        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Resolves a single JSON value. Returns the resolved element if changed, null otherwise.
     * BlobRefs are resolved to their decompressed data. Nested objects are recursed.
     */
    private static JsonElement resolveValue(JsonElement value) {
        if (!value.isJsonObject()) {
            return null;
        }

        JsonObject obj = value.getAsJsonObject();

        if (isBlobRef(obj)) {
            try {
                return resolveRef(obj);
            } catch (Exception e) {
                System.err.println("lmo: failed to resolve blob: " + e.getMessage());
                return null;
            }
        }

        // Recurse into nested objects
        boolean modified = false;
        for (Map.Entry<String, JsonElement> entry : obj.entrySet().toArray(new Map.Entry[0])) {
            JsonElement child = entry.getValue();
            JsonElement resolved = resolveValue(child);
            if (resolved != null) {
                obj.add(entry.getKey(), resolved);
                modified = true;
            }
        }

        return modified ? obj : null;
    }

    /**
     * Returns true if the JSON object is a BlobRef marker.
     */
    static boolean isBlobRef(JsonObject obj) {
        JsonElement magic = obj.get("__magic");
        JsonElement ref = obj.get("__ref");
        if (magic == null || ref == null) {
            return false;
        }
        try {
            return magic.getAsInt() == MAGIC
                    && ref.isJsonPrimitive()
                    && !ref.getAsString().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Resolves a BlobRef by reading and decompressing the blob from disk.
     */
    private static JsonElement resolveRef(JsonObject blobRef) throws Exception {
        String ref = blobRef.get("__ref").getAsString();
        String blobPath = blobRef.get("__path").getAsString();
        byte[] blob = getBlob(ref, blobPath);
        String json = new String(blob, StandardCharsets.UTF_8);
        return JsonParser.parseString(json);
    }

    /**
     * Reads and decompresses a blob from the store.
     * Path: configDir/store/{path}/blobs/{hash[:2]}/{hash[2:]}
     */
    static byte[] getBlob(String ref, String storePath) throws Exception {
        String hash = ref.startsWith("xxh3:") ? ref.substring(5) : ref;
        String dir = hash.substring(0, 2);
        String file = hash.substring(2);

        Path blobFile = Paths.get(getConfigDir(), "store", storePath, "blobs", dir, file);
        byte[] compressed = Files.readAllBytes(blobFile);
        return Zstd.decompress(compressed, (int) Zstd.decompressedSize(compressed));
    }

    /**
     * Returns the platform-specific config directory.
     */
    static String getConfigDir() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return home + "\\AppData\\Local\\Robomotion";
        }
        return home + "/.config/robomotion";
    }
}
