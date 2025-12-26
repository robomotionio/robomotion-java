package com.robomotion.app;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * ToolInterceptor wraps a Node to automatically handle tool requests.
 * When a node has a Tool field and receives a tool request, the interceptor will
 * automatically send a tool response after the node processes the message.
 */
public class ToolInterceptor {

    private final Node originalNode;
    private final Class<?> nodeType;
    private final boolean hasTool;

    /**
     * Creates a tool interceptor for a node.
     *
     * @param node The node to wrap
     */
    public ToolInterceptor(Node node) {
        this.originalNode = node;
        this.nodeType = node.getClass();
        this.hasTool = hasToolField(nodeType);
    }

    /**
     * Checks if a node class has a Tool field.
     */
    private static boolean hasToolField(Class<?> nodeType) {
        for (Field field : nodeType.getDeclaredFields()) {
            if (field.getType() == Tool.class) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if this node supports tools.
     */
    public boolean hasTool() {
        return hasTool;
    }

    /**
     * Handles the OnMessage call with tool interception.
     *
     * @param ctx The message context
     * @throws Exception if processing fails
     */
    public void onMessage(Context ctx) throws Exception {
        // Check if this is a tool request and this node supports tools
        if (hasTool && ToolResponse.isToolRequest(ctx)) {
            handleToolRequest(ctx);
            return;
        }

        // Pass through to original handler for normal processing
        originalNode.OnMessage(ctx);
    }

    /**
     * Automatically processes tool requests.
     */
    private void handleToolRequest(Context ctx) throws Exception {
        Exception processingError = null;

        // Call the original handler to do the actual work
        try {
            originalNode.OnMessage(ctx);
        } catch (Exception e) {
            processingError = e;
        }

        // If the original handler didn't call ToolResponse, send a default response
        if (!ToolResponse.hasBeenSent(ctx)) {
            if (processingError != null) {
                ToolResponse.sendError(ctx, processingError.getMessage());
            } else {
                // Collect output variables automatically
                Map<String, Object> outputData = collectOutputVariables(ctx);
                ToolResponse.sendSuccess(ctx, outputData);
            }
        }
    }

    /**
     * Collects output variables from the node.
     */
    private Map<String, Object> collectOutputVariables(Context ctx) {
        Map<String, Object> outputData = new HashMap<>();

        // Use reflection to find OutVariable fields and collect their values
        for (Field field : nodeType.getDeclaredFields()) {
            String typeName = field.getType().getName();

            // Check if this is an OutVariable field
            if (typeName.contains("OutVariable")) {
                try {
                    field.setAccessible(true);
                    Object outVar = field.get(originalNode);

                    if (outVar instanceof Runtime.OutVariable) {
                        Runtime.OutVariable<?> variable = (Runtime.OutVariable<?>) outVar;

                        // Get the value from context if it's a Message scope variable
                        if ("Message".equals(variable.scope)) {
                            String varName = variable.getNameString();
                            Object value = ctx.get(varName);
                            if (value != null) {
                                outputData.put(varName, value);
                            }
                        }
                    }
                } catch (Exception e) {
                    // Continue to next field
                }
            }
        }

        // If no output variables found, return basic status
        if (outputData.isEmpty()) {
            outputData.put("status", "completed");
        }

        return outputData;
    }

    /**
     * Gets the original node.
     */
    public Node getOriginalNode() {
        return originalNode;
    }
}
