package com.robomotion.app;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * Message context implementation for Robomotion nodes.
 * Provides JSON-based message handling with path-based access.
 */
public class Message implements Context {

    private static final Gson gson = new Gson();

    private String id;
    private byte[] data;
    private JsonObject jsonObject;

    public Message(byte[] data) {
        this.data = data;
        if (data != null && data.length > 0) {
            try {
                String jsonStr = new String(data, StandardCharsets.UTF_8);
                this.jsonObject = JsonParser.parseString(jsonStr).getAsJsonObject();
                if (this.jsonObject.has("id")) {
                    this.id = this.jsonObject.get("id").getAsString();
                }
            } catch (Exception e) {
                this.jsonObject = new JsonObject();
            }
        } else {
            this.jsonObject = new JsonObject();
        }
    }

    /**
     * Creates a new context from data bytes
     */
    public static Context newContext(byte[] data) {
        return new Message(data);
    }

    @Override
    public String getID() {
        return id;
    }

    // Legacy method for backward compatibility
    public String GetID() {
        return getID();
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

    // Legacy method for backward compatibility
    public void Set(String key, Object value) {
        set(key, value);
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

    // Legacy method for backward compatibility
    @SuppressWarnings("unchecked")
    public <T> T Get(String key, Class<T> cls) {
        Object value = get(key);
        if (value == null) {
            return null;
        }
        if (cls != null && cls.isInstance(value)) {
            return cls.cast(value);
        }
        // Try to convert using Gson
        try {
            JsonElement element = getJsonElement(convertPath(key));
            if (element != null) {
                return gson.fromJson(element, cls);
            }
        } catch (Exception e) {
            // Fall through
        }
        return (T) value;
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

    // Legacy method for backward compatibility
    public byte[] GetRaw() {
        return getRaw();
    }

    @SafeVarargs
    @Override
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

    // Legacy method for backward compatibility
    public void SetRaw(byte[] data) {
        setRaw(data);
    }

    @SafeVarargs
    @Override
    public final void setRaw(byte[] data, Function<byte[], byte[]>... options) {
        byte[] result = data;
        for (Function<byte[], byte[]> option : options) {
            result = option.apply(result);
        }
        setRaw(result);
    }

    @Override
    public boolean isEmpty() {
        return data == null || data.length == 0;
    }

    // Legacy method for backward compatibility
    public boolean IsEmpty() {
        return isEmpty();
    }

    // Helper methods

    private String convertPath(String path) {
        return path.replace("[", ".").replace("]", "");
    }

    private JsonElement getJsonElement(String path) {
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
                // Try to return appropriate number type
                Number num = primitive.getAsNumber();
                if (num.doubleValue() == num.longValue()) {
                    return num.longValue();
                }
                return num.doubleValue();
            }
            return primitive.getAsString();
        }
        if (element.isJsonArray()) {
            return gson.fromJson(element, java.util.List.class);
        }
        if (element.isJsonObject()) {
            return gson.fromJson(element, java.util.Map.class);
        }
        return null;
    }
}
