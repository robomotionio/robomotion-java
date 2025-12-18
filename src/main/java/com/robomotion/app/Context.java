package com.robomotion.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.function.Function;

/**
 * Context interface for message handling in Robomotion nodes.
 * Provides type-safe access to message data.
 */
public interface Context {

    /**
     * Gets the message ID
     */
    String getID();

    /**
     * Sets a value at the given path
     */
    void set(String path, Object value);

    /**
     * Gets a value at the given path
     */
    Object get(String path);

    /**
     * Gets a string value at the given path
     */
    String getString(String path);

    /**
     * Gets a boolean value at the given path
     */
    boolean getBool(String path);

    /**
     * Gets an integer value at the given path
     */
    long getInt(String path);

    /**
     * Gets a double value at the given path
     */
    double getFloat(String path);

    /**
     * Gets the raw message bytes
     */
    byte[] getRaw();

    /**
     * Gets the raw message bytes with options
     */
    byte[] getRaw(Function<byte[], byte[]>... options);

    /**
     * Sets the raw message bytes
     */
    void setRaw(byte[] data);

    /**
     * Sets the raw message bytes with options
     */
    void setRaw(byte[] data, Function<byte[], byte[]>... options);

    /**
     * Checks if the context is empty
     */
    boolean isEmpty();
}
