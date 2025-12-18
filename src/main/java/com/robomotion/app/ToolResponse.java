package com.robomotion.app;

import java.util.Map;

/**
 * ToolResponse provides utilities for handling AI tool requests and sending responses back to the LLM Agent.
 */
public class ToolResponse {

    private static final String MESSAGE_TYPE_KEY = "__message_type__";
    private static final String TOOL_REQUEST_TYPE = "tool_request";
    private static final String TOOL_RESPONSE_TYPE = "tool_response";
    private static final String TOOL_CALLER_ID_KEY = "__tool_caller_id__";
    private static final String AGENT_NODE_ID_KEY = "__agent_node_id__";
    private static final String TOOL_STATUS_KEY = "__tool_status__";
    private static final String TOOL_ERROR_KEY = "__tool_error__";
    private static final String TOOL_DATA_KEY = "__tool_data__";

    /**
     * Checks if the current message is a tool request from an AI agent.
     *
     * @param ctx The message context
     * @return true if this is a tool request
     */
    public static boolean isToolRequest(Context ctx) {
        Object msgType = ctx.get(MESSAGE_TYPE_KEY);
        return TOOL_REQUEST_TYPE.equals(msgType);
    }

    /**
     * Sends a success response back to the LLM Agent and prevents message flow to next node.
     *
     * @param ctx The message context
     * @param data The response data to send back
     * @throws RuntimeNotInitializedException if runtime is not initialized
     */
    public static void sendSuccess(Context ctx, Map<String, Object> data) throws RuntimeNotInitializedException {
        send(ctx, "success", data, null);
    }

    /**
     * Sends an error response back to the LLM Agent and prevents message flow to next node.
     *
     * @param ctx The message context
     * @param errorMessage The error message to send back
     * @throws RuntimeNotInitializedException if runtime is not initialized
     */
    public static void sendError(Context ctx, String errorMessage) throws RuntimeNotInitializedException {
        send(ctx, "error", null, errorMessage);
    }

    /**
     * Sends a response back to the LLM Agent and prevents message flow to next node.
     *
     * @param ctx The message context
     * @param status The status ("success" or "error")
     * @param data The response data (can be null)
     * @param errorMsg The error message (can be null)
     * @throws RuntimeNotInitializedException if runtime is not initialized
     */
    public static void send(Context ctx, String status, Map<String, Object> data, String errorMsg) throws RuntimeNotInitializedException {
        if (!isToolRequest(ctx)) {
            return; // Not a tool request
        }

        Object callerId = ctx.get(TOOL_CALLER_ID_KEY);
        Object agentNodeId = ctx.get(AGENT_NODE_ID_KEY);

        // Create response context with required fields
        Message responseCtx = new Message("{}".getBytes());

        // Copy essential fields from the original message
        Object msgId = ctx.get("id");
        if (msgId != null) {
            responseCtx.set("id", msgId);
        }

        // Copy session information if present
        Object sessionId = ctx.get("session_id");
        if (sessionId != null) {
            responseCtx.set("session_id", sessionId);
        }

        // Copy query information if present
        Object query = ctx.get("query");
        if (query != null) {
            responseCtx.set("query", query);
        }

        // Set tool response specific fields
        responseCtx.set(MESSAGE_TYPE_KEY, TOOL_RESPONSE_TYPE);
        responseCtx.set(TOOL_CALLER_ID_KEY, callerId);
        responseCtx.set(TOOL_STATUS_KEY, status);

        if (errorMsg != null && !errorMsg.isEmpty()) {
            responseCtx.set(TOOL_ERROR_KEY, errorMsg);
        }
        if (data != null) {
            responseCtx.set(TOOL_DATA_KEY, data);
        }

        // Send response back to LLM Agent
        if (agentNodeId instanceof String && !((String) agentNodeId).isEmpty()) {
            Runtime.EmitInput((String) agentNodeId, responseCtx.getRaw());
        }

        // Prevent message flow to next node by clearing context
        ctx.setRaw(null);
    }

    /**
     * Checks if ToolResponse has already been sent.
     *
     * @param ctx The message context
     * @return true if a tool response was already sent
     */
    public static boolean hasBeenSent(Context ctx) {
        byte[] raw = ctx.getRaw();
        return raw == null || raw.length == 0;
    }
}
