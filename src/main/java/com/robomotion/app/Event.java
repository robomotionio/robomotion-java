package com.robomotion.app;

import com.google.protobuf.ByteString;

public class Event {
    public static void EmitDebug(String guid, String name, Object message) throws RuntimeNotInitializedException {
        RuntimeHelperGrpc.RuntimeHelperBlockingStub client = Runtime.GetClient();
        if (client == null)
            throw new RuntimeNotInitializedException();

        DebugRequest request = DebugRequest.newBuilder()
                .setGuid(guid)
                .setName(name)
                .setMessage(ByteString.copyFrom(Runtime.Serialize(message)))
                .build();

        client.debug(request);
    }

    public static void EmitOutput(String guid, byte[] output, int port) throws RuntimeNotInitializedException {
        RuntimeHelperGrpc.RuntimeHelperBlockingStub client = Runtime.GetClient();
        if (client == null)
            throw new RuntimeNotInitializedException();

        EmitOutputRequest request = EmitOutputRequest.newBuilder()
                .setGuid(guid)
                .setOutput(ByteString.copyFrom(output))
                .setPort(port)
                .build();

        client.emitOutput(request);
    }

    public static void EmitInput(String guid, byte[] input) throws RuntimeNotInitializedException {
        RuntimeHelperGrpc.RuntimeHelperBlockingStub client = Runtime.GetClient();
        if (client == null)
            throw new RuntimeNotInitializedException();

        EmitInputRequest request = EmitInputRequest.newBuilder()
                .setGuid(guid)
                .setInput(ByteString.copyFrom(input))
                .build();

        client.emitInput(request);
    }

    public static void EmitError(String guid, String name, String message) throws RuntimeNotInitializedException {
        RuntimeHelperGrpc.RuntimeHelperBlockingStub client = Runtime.GetClient();
        if (client == null)
            throw new RuntimeNotInitializedException();

        EmitErrorRequest request = EmitErrorRequest.newBuilder()
                .setGuid(guid)
                .setName(name)
                .setMessage(message)
                .build();

        client.emitError(request);
    }

    public static void EmitFlowEvent(String guid, String name) throws RuntimeNotInitializedException {
        RuntimeHelperGrpc.RuntimeHelperBlockingStub client = Runtime.GetClient();
        if (client == null)
            throw new RuntimeNotInitializedException();

        EmitFlowEventRequest request = EmitFlowEventRequest.newBuilder()
                .setGuid(guid)
                .setName(name)
                .build();

        client.emitFlowEvent(request);
    }
}
