package com.robomotion.testing;

import com.robomotion.app.Context;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * MockContext provides a test implementation of the Context interface.
 * It stores message data in memory using JSON for testing nodes without the full runtime.
 */
public class MockContext implements Context {

    private static final Gson gson = new Gson();

    private String id;
    private byte[] data;
    private JsonObject jsonObject;

    /**
     * Creates a new empty MockContext.
     */
    public MockContext() {
        this.id = UUID.randomUUID().toString();
        this.jsonObject = new JsonObject();
        this.data = "{}".getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Creates a MockContext with initial data.
     *
     * @param initialData Initial data as a map
     */
    public MockContext(Map<String, Object> initialData) {
        this.id = UUID.randomUUID().toString();
        if (initialData != null) {
            this.jsonObject = gson.toJsonTree(initialData).getAsJsonObject();
            this.data = this.jsonObject.toString().getBytes(StandardCharsets.UTF_8);

            if (initialData.containsKey("id")) {
                this.id = String.valueOf(initialData.get("id"));
            }
        } else {
            this.jsonObject = new JsonObject();
            this.data = "{}".getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Creates a MockContext from JSON bytes.
     *
     * @param jsonData JSON data as bytes
     */
    public MockContext(byte[] jsonData) {
        this.id = UUID.randomUUID().toString();
        if (jsonData != null && jsonData.length > 0) {
            try {
                String jsonStr = new String(jsonData, StandardCharsets.UTF_8);
                this.jsonObject = JsonParser.parseString(jsonStr).getAsJsonObject();
                this.data = jsonData;

                if (this.jsonObject.has("id")) {
                    this.id = this.jsonObject.get("id").getAsString();
                }
            } catch (Exception e) {
                this.jsonObject = new JsonObject();
                this.data = "{}".getBytes(StandardCharsets.UTF_8);
            }
        } else {
            this.jsonObject = new JsonObject();
            this.data = "{}".getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Creates a MockContext from a JSON string.
     *
     * @param json JSON string
     * @return A new MockContext
     */
    public static MockContext fromJSON(String json) {
        if (json == null || json.isEmpty()) {
            return new MockContext();
        }
        return new MockContext(json.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String getID() {
        return id;
    }

    @Override
    public void set(String path, Object value) {
        String normalizedPath = convertPath(path);
        String[] parts = normalizedPath.split("\\.");

        JsonObject current = jsonObject;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            if (!current.has(part) || !current.get(part).isJsonObject()) {
                current.add(part, new JsonObject());
            }
            current = current.getAsJsonObject(part);
        }

        String lastKey = parts[parts.length - 1];
        current.add(lastKey, gson.toJsonTree(value));

        // Update raw data
        this.data = jsonObject.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Object get(String path) {
        String normalizedPath = convertPath(path);
        JsonElement element = getJsonElement(normalizedPath);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return jsonElementToObject(element);
    }

    @Override
    public String getString(String path) {
        String normalizedPath = convertPath(path);
        JsonElement element = getJsonElement(normalizedPath);
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        return element.toString();
    }

    @Override
    public boolean getBool(String path) {
        String normalizedPath = convertPath(path);
        JsonElement element = getJsonElement(normalizedPath);
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (element.isJsonPrimitive()) {
            try {
                return element.getAsBoolean();
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    @Override
    public long getInt(String path) {
        String normalizedPath = convertPath(path);
        JsonElement element = getJsonElement(normalizedPath);
        if (element == null || element.isJsonNull()) {
            return 0;
        }
        if (element.isJsonPrimitive()) {
            try {
                return element.getAsLong();
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }

    @Override
    public double getFloat(String path) {
        String normalizedPath = convertPath(path);
        JsonElement element = getJsonElement(normalizedPath);
        if (element == null || element.isJsonNull()) {
            return 0.0;
        }
        if (element.isJsonPrimitive()) {
            try {
                return element.getAsDouble();
            } catch (Exception e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    @Override
    public byte[] getRaw() {
        if (data == null) {
            return jsonObject.toString().getBytes(StandardCharsets.UTF_8);
        }
        return data;
    }

    @Override
    @SafeVarargs
    public final byte[] getRaw(Function<byte[], byte[]>... options) {
        byte[] result = getRaw();
        for (Function<byte[], byte[]> option : options) {
            result = option.apply(result);
        }
        return result;
    }

    @Override
    public void setRaw(byte[] data) {
        this.data = data;
        if (data != null && data.length > 0) {
            try {
                String jsonStr = new String(data, StandardCharsets.UTF_8);
                this.jsonObject = JsonParser.parseString(jsonStr).getAsJsonObject();
            } catch (Exception e) {
                this.jsonObject = new JsonObject();
            }
        } else {
            this.jsonObject = new JsonObject();
        }
    }

    @Override
    @SafeVarargs
    public final void setRaw(byte[] data, Function<byte[], byte[]>... options) {
        byte[] result = data;
        for (Function<byte[], byte[]> option : options) {
            result = option.apply(result);
        }
        setRaw(result);
    }

    @Override
    public boolean isEmpty() {
        return data == null || data.length == 0 || !jsonObject.entrySet().iterator().hasNext();
    }

    /**
     * Gets all data as a map.
     *
     * @return All context data as a map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAll() {
        return gson.fromJson(jsonObject, Map.class);
    }

    /**
     * Gets the current state as a JSON string.
     *
     * @return JSON string representation
     */
    public String toJSON() {
        return jsonObject.toString();
    }

    /**
     * Clears all data from the context.
     */
    public void clear() {
        this.jsonObject = new JsonObject();
        this.data = "{}".getBytes(StandardCharsets.UTF_8);
    }

    // Helper methods

    private String convertPath(String path) {
        return path.replace("[", ".").replace("]", "");
    }

    private JsonElement getJsonElement(String path) {
        if (path == null || path.isEmpty()) {
            return jsonObject;
        }

        String[] parts = path.split("\\.");
        JsonElement current = jsonObject;

        for (String part : parts) {
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            current = current.getAsJsonObject().get(part);
        }

        return current;
    }

    private Object jsonElementToObject(JsonElement element) {
        if (element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            var primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            if (primitive.isNumber()) {
                Number num = primitive.getAsNumber();
                if (num.doubleValue() == num.longValue()) {
                    return num.longValue();
                }
                return num.doubleValue();
            }
            return primitive.getAsString();
        }
        if (element.isJsonArray()) {
            return gson.fromJson(element, List.class);
        }
        if (element.isJsonObject()) {
            return gson.fromJson(element, Map.class);
        }
        return null;
    }
}
