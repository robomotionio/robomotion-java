package com.robomotion.testing;

import com.robomotion.app.Context;
import com.robomotion.app.Node;
import com.robomotion.app.Runtime;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Harness provides a low-level testing API for Robomotion nodes.
 * It allows full control over variable configuration and node lifecycle.
 * <p>
 * Example usage:
 * <pre>{@code
 * MyNode node = new MyNode();
 * Harness h = new Harness(node)
 *     .withInput("data", "test value")
 *     .configureInVariable(node.inField, "Message", "data")
 *     .configureOutVariable(node.outField, "Message", "result");
 *
 * Exception err = h.run();
 * assertNull(err);
 *
 * Object result = h.getOutput("result");
 * }</pre>
 */
public class Harness {

    private final Node node;
    private MockContext context;
    private boolean createCalled;

    /**
     * Creates a new Harness for testing a node.
     *
     * @param node The node to test
     */
    public Harness(Node node) {
        if (node == null) {
            throw new IllegalArgumentException("node cannot be null");
        }
        this.node = node;
        this.context = new MockContext();
        this.createCalled = false;
    }

    /**
     * Gets the underlying MockContext.
     *
     * @return The context
     */
    public MockContext getContext() {
        return context;
    }

    /**
     * Sets an input value in the message context.
     *
     * @param name Path in the message context
     * @param value Value to set
     * @return This Harness for chaining
     */
    public Harness withInput(String name, Object value) {
        context.set(name, value);
        return this;
    }

    /**
     * Sets multiple input values in the message context.
     *
     * @param inputs Dictionary of path-value pairs
     * @return This Harness for chaining
     */
    public Harness withInputs(Map<String, Object> inputs) {
        if (inputs != null) {
            for (Map.Entry<String, Object> entry : inputs.entrySet()) {
                context.set(entry.getKey(), entry.getValue());
            }
        }
        return this;
    }

    /**
     * Uses a custom MockContext instead of the default empty one.
     *
     * @param ctx Custom context to use
     * @return This Harness for chaining
     */
    public Harness withContext(MockContext ctx) {
        this.context = ctx != null ? ctx : new MockContext();
        return this;
    }

    /**
     * Configures an InVariable with scope and name.
     *
     * @param variable The variable to configure
     * @param scope Scope (Message, Custom, etc.)
     * @param name Variable name or path
     * @param <T> The variable type
     * @return This Harness for chaining
     */
    public <T> Harness configureInVariable(Runtime.InVariable<T> variable, String scope, String name) {
        if (variable != null) {
            variable.scope = scope;
            variable.name = name;
        }
        return this;
    }

    /**
     * Configures an OutVariable with scope and name.
     *
     * @param variable The variable to configure
     * @param scope Scope (Message, Custom, etc.)
     * @param name Variable name or path
     * @param <T> The variable type
     * @return This Harness for chaining
     */
    public <T> Harness configureOutVariable(Runtime.OutVariable<T> variable, String scope, String name) {
        if (variable != null) {
            variable.scope = scope;
            variable.name = name;
        }
        return this;
    }

    /**
     * Configures an OptVariable with scope and name.
     *
     * @param variable The variable to configure
     * @param scope Scope (Message, Custom, etc.)
     * @param name Variable name or path
     * @param <T> The variable type
     * @return This Harness for chaining
     */
    public <T> Harness configureOptVariable(Runtime.OptVariable<T> variable, String scope, String name) {
        if (variable != null) {
            variable.scope = scope;
            variable.name = name;
        }
        return this;
    }

    /**
     * Configures an InVariable for Custom scope with a direct value.
     * For Custom scope, the name field holds the actual value.
     *
     * @param variable The variable to configure
     * @param value The value to set
     * @param <T> The variable type
     * @return This Harness for chaining
     */
    public <T> Harness configureCustomInput(Runtime.InVariable<T> variable, Object value) {
        if (variable != null) {
            variable.scope = "Custom";
            variable.name = normalizeNumericValue(value);
        }
        return this;
    }

    /**
     * Configures an OptVariable for Custom scope with a direct value.
     *
     * @param variable The variable to configure
     * @param value The value to set
     * @param <T> The variable type
     * @return This Harness for chaining
     */
    public <T> Harness configureCustomOpt(Runtime.OptVariable<T> variable, Object value) {
        if (variable != null) {
            variable.scope = "Custom";
            variable.name = normalizeNumericValue(value);
        }
        return this;
    }

    /**
     * Configures a Credential field with vault and item IDs.
     *
     * @param credential The credential to configure
     * @param vaultId Vault ID
     * @param itemId Item ID
     * @return This Harness for chaining
     */
    public Harness configureCredential(Runtime.Credential credential, String vaultId, String itemId) {
        if (credential != null) {
            credential.scope = "Custom";
            Map<String, Object> nameMap = new HashMap<>();
            nameMap.put("vaultId", vaultId);
            nameMap.put("itemId", itemId);
            credential.name = nameMap;
        }
        return this;
    }

    /**
     * Runs OnMessage on the node.
     * Calls OnCreate first if not already called.
     *
     * @return Exception if any, null on success
     */
    public Exception run() {
        try {
            if (!createCalled) {
                node.OnCreate();
                createCalled = true;
            }

            node.OnMessage(context);
            return null;
        } catch (Exception ex) {
            return ex;
        }
    }

    /**
     * Runs OnCreate explicitly, then OnMessage.
     *
     * @return Exception if any, null on success
     */
    public Exception runWithCreate() {
        try {
            node.OnCreate();
            createCalled = true;

            node.OnMessage(context);
            return null;
        } catch (Exception ex) {
            return ex;
        }
    }

    /**
     * Runs the full lifecycle: OnCreate, OnMessage, OnClose.
     *
     * @return Exception if any, null on success
     */
    public Exception runFull() {
        try {
            node.OnCreate();
            createCalled = true;

            node.OnMessage(context);

            node.OnClose();
            return null;
        } catch (Exception ex) {
            return ex;
        }
    }

    /**
     * Gets an output value from the context.
     *
     * @param name Output name/path
     * @return The value, or null if not found
     */
    public Object getOutput(String name) {
        return context.get(name);
    }

    /**
     * Gets a string output value.
     *
     * @param name Output name/path
     * @return The string value
     */
    public String getOutputString(String name) {
        return context.getString(name);
    }

    /**
     * Gets an integer output value.
     *
     * @param name Output name/path
     * @return The integer value
     */
    public long getOutputInt(String name) {
        return context.getInt(name);
    }

    /**
     * Gets a float output value.
     *
     * @param name Output name/path
     * @return The float value
     */
    public double getOutputFloat(String name) {
        return context.getFloat(name);
    }

    /**
     * Gets a boolean output value.
     *
     * @param name Output name/path
     * @return The boolean value
     */
    public boolean getOutputBool(String name) {
        return context.getBool(name);
    }

    /**
     * Gets all output values from the context.
     *
     * @return All values as a map
     */
    public Map<String, Object> getAllOutputs() {
        return context.getAll();
    }

    /**
     * Resets the context and state for reuse.
     *
     * @return This Harness for chaining
     */
    public Harness reset() {
        this.context = new MockContext();
        this.createCalled = false;
        return this;
    }

    /**
     * Normalizes numeric values to match runtime expectations.
     *
     * @param value The value to normalize
     * @return The normalized value
     */
    private Object normalizeNumericValue(Object value) {
        if (value == null) return null;

        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return ((Number) value).longValue();
        }
        if (value instanceof Long) {
            return value;
        }
        if (value instanceof Float) {
            return ((Float) value).doubleValue();
        }
        if (value instanceof Double) {
            return value;
        }

        return value;
    }
}
