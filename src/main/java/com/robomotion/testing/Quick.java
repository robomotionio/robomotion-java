package com.robomotion.testing;

import com.robomotion.app.Node;
import com.robomotion.app.Runtime;
import com.robomotion.app.Var;
import com.robomotion.app.FieldAnnotations;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Quick provides a high-level testing API for Robomotion nodes.
 * It auto-configures variables based on their type and annotations.
 * <p>
 * Example usage:
 * <pre>{@code
 * MyNode node = new MyNode();
 * node.OptModel = "gemini-2.0-flash-lite";
 *
 * Quick q = new Quick(node);
 * q.setCredential("OptApiKey", "api_key", "api_key");
 * q.setCustom("InText", "Say hello in 3 words");
 *
 * Exception err = q.run();
 * assertNull(err);
 *
 * Object text = q.getOutput("text");
 * assertNotNull(text);
 * System.out.println("Generated: " + text);
 * }</pre>
 */
public class Quick {

    private final Harness harness;
    private final Node node;
    private final Map<String, Field> variableFields;

    /**
     * Creates a new Quick helper for testing a node.
     * Automatically configures all InVariable, OutVariable, and OptVariable fields.
     *
     * @param node The node to test
     */
    public Quick(Node node) {
        if (node == null) {
            throw new IllegalArgumentException("node cannot be null");
        }
        this.node = node;
        this.harness = new Harness(node);
        this.variableFields = new HashMap<>();

        autoConfigureVariables();
    }

    /**
     * Gets the underlying Harness for advanced operations.
     *
     * @return The Harness
     */
    public Harness getHarness() {
        return harness;
    }

    /**
     * Gets the underlying MockContext.
     *
     * @return The context
     */
    public MockContext getContext() {
        return harness.getContext();
    }

    /**
     * Sets an input value in the message context.
     *
     * @param name Path in the message context
     * @param value Value to set
     * @return This Quick for chaining
     */
    public Quick setInput(String name, Object value) {
        harness.withInput(name, value);
        return this;
    }

    /**
     * Sets multiple input values in the message context.
     *
     * @param inputs Dictionary of path-value pairs
     * @return This Quick for chaining
     */
    public Quick setInputs(Map<String, Object> inputs) {
        harness.withInputs(inputs);
        return this;
    }

    /**
     * Sets a Custom scope value for a field by its field name.
     *
     * @param fieldName Field name (e.g., "InText", "OptModel")
     * @param value Value to set
     * @return This Quick for chaining
     */
    public Quick setCustom(String fieldName, Object value) {
        Field field = variableFields.get(fieldName);
        if (field != null) {
            try {
                Object fieldValue = field.get(node);
                if (fieldValue instanceof Runtime.Variable) {
                    Runtime.Variable<?> variable = (Runtime.Variable<?>) fieldValue;
                    variable.scope = "Custom";
                    variable.name = normalizeNumericValue(value);
                }
            } catch (IllegalAccessException e) {
                // Ignore
            }
        }
        return this;
    }

    /**
     * Manually configures a variable's scope and name by field name.
     *
     * @param fieldName Field name
     * @param scope Scope (Message, Custom, etc.)
     * @param name Variable name or value
     * @return This Quick for chaining
     */
    public Quick configureVariable(String fieldName, String scope, String name) {
        Field field = variableFields.get(fieldName);
        if (field != null) {
            try {
                Object fieldValue = field.get(node);
                if (fieldValue instanceof Runtime.Variable) {
                    Runtime.Variable<?> variable = (Runtime.Variable<?>) fieldValue;
                    variable.scope = scope;
                    variable.name = name;
                }
            } catch (IllegalAccessException e) {
                // Ignore
            }
        }
        return this;
    }

    /**
     * Sets a Credential field with vault and item IDs.
     *
     * @param fieldName Field name of the Credential field
     * @param vaultId Vault ID (or credential name in CredentialStore)
     * @param itemId Item ID (or same as vaultId for simple lookup)
     * @return This Quick for chaining
     */
    public Quick setCredential(String fieldName, String vaultId, String itemId) {
        Field field = variableFields.get(fieldName);
        if (field != null) {
            try {
                Object fieldValue = field.get(node);
                if (fieldValue instanceof Runtime.Credential) {
                    Runtime.Credential credential = (Runtime.Credential) fieldValue;
                    credential.scope = "Custom";
                    Map<String, Object> nameMap = new HashMap<>();
                    nameMap.put("vaultId", vaultId);
                    nameMap.put("itemId", itemId);
                    credential.name = nameMap;
                }
            } catch (IllegalAccessException e) {
                // Ignore
            }
        }
        return this;
    }

    /**
     * Runs OnMessage on the node.
     *
     * @return Exception if any, null on success
     */
    public Exception run() {
        return harness.run();
    }

    /**
     * Runs the full lifecycle: OnCreate, OnMessage, OnClose.
     *
     * @return Exception if any, null on success
     */
    public Exception runFull() {
        return harness.runFull();
    }

    /**
     * Gets an output value from the context.
     *
     * @param name Output name/path
     * @return The value, or null if not found
     */
    public Object getOutput(String name) {
        return harness.getOutput(name);
    }

    /**
     * Gets a string output value.
     *
     * @param name Output name/path
     * @return The string value
     */
    public String getOutputString(String name) {
        return harness.getOutputString(name);
    }

    /**
     * Gets an integer output value.
     *
     * @param name Output name/path
     * @return The integer value
     */
    public long getOutputInt(String name) {
        return harness.getOutputInt(name);
    }

    /**
     * Gets a float output value.
     *
     * @param name Output name/path
     * @return The float value
     */
    public double getOutputFloat(String name) {
        return harness.getOutputFloat(name);
    }

    /**
     * Gets a boolean output value.
     *
     * @param name Output name/path
     * @return The boolean value
     */
    public boolean getOutputBool(String name) {
        return harness.getOutputBool(name);
    }

    /**
     * Gets all output values from the context.
     *
     * @return All values as a map
     */
    public Map<String, Object> getAllOutputs() {
        return harness.getAllOutputs();
    }

    /**
     * Resets the context for reuse.
     *
     * @return This Quick for chaining
     */
    public Quick reset() {
        harness.reset();
        return this;
    }

    /**
     * Auto-configures all variable fields on the node.
     */
    private void autoConfigureVariables() {
        Class<?> clazz = node.getClass();

        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                field.setAccessible(true);

                Class<?> fieldType = field.getType();

                // Check if it's a Variable type or Credential
                if (isVariableType(fieldType) || fieldType == Runtime.Credential.class) {
                    variableFields.put(field.getName(), field);

                    try {
                        Object fieldValue = field.get(node);
                        if (fieldValue != null) {
                            configureFromAnnotations(field, fieldValue);
                        }
                    } catch (IllegalAccessException e) {
                        // Ignore
                    }
                }
            }

            clazz = clazz.getSuperclass();
        }
    }

    /**
     * Configures a variable instance based on its annotations.
     */
    private void configureFromAnnotations(Field field, Object instance) {
        String scope = "Message";
        String name = getDefaultName(field);

        // Check for @Var annotation
        Var varAnnotation = field.getAnnotation(Var.class);
        if (varAnnotation != null) {
            if (!varAnnotation.defaultScope().isEmpty()) {
                scope = varAnnotation.defaultScope();
            }
            if (!varAnnotation.defaultName().isEmpty()) {
                name = varAnnotation.defaultName();
            }
            if (!varAnnotation.defaultValue().isEmpty() && instance instanceof Runtime.Variable) {
                // Direct default value - use Custom scope
                Runtime.Variable<?> variable = (Runtime.Variable<?>) instance;
                variable.scope = "Custom";
                variable.name = varAnnotation.defaultValue();
                return;
            }
        }

        // Check for @Default annotation (legacy)
        FieldAnnotations.Default defaultAnnotation = field.getAnnotation(FieldAnnotations.Default.class);
        if (defaultAnnotation != null) {
            if (!defaultAnnotation.scope().isEmpty()) {
                scope = defaultAnnotation.scope();
            }
            if (!defaultAnnotation.name().isEmpty()) {
                name = defaultAnnotation.name();
            }
            if (!defaultAnnotation.value().isEmpty() && instance instanceof Runtime.Variable) {
                Runtime.Variable<?> variable = (Runtime.Variable<?>) instance;
                variable.scope = "Custom";
                variable.name = defaultAnnotation.value();
                return;
            }
        }

        // Configure the variable
        if (instance instanceof Runtime.Variable) {
            Runtime.Variable<?> variable = (Runtime.Variable<?>) instance;
            variable.scope = scope;
            variable.name = name;
        } else if (instance instanceof Runtime.Credential) {
            Runtime.Credential credential = (Runtime.Credential) instance;
            credential.scope = scope;
        }
    }

    /**
     * Gets the default name for a variable based on its field name.
     * Converts "InUserName" -> "userName", "OutResult" -> "result", etc.
     */
    private String getDefaultName(Field field) {
        String name = field.getName();

        // Remove prefixes
        if (name.startsWith("In") && name.length() > 2) {
            name = name.substring(2);
        } else if (name.startsWith("Out") && name.length() > 3) {
            name = name.substring(3);
        } else if (name.startsWith("Opt") && name.length() > 3) {
            name = name.substring(3);
        }

        // Convert to camelCase
        if (!name.isEmpty()) {
            name = Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }

        return name;
    }

    /**
     * Checks if a type is a Variable type (InVariable, OutVariable, OptVariable).
     */
    private boolean isVariableType(Class<?> type) {
        if (type == null) return false;

        String typeName = type.getSimpleName();
        return typeName.contains("InVariable") ||
                typeName.contains("OutVariable") ||
                typeName.contains("OptVariable");
    }

    /**
     * Normalizes numeric values to match runtime expectations.
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
