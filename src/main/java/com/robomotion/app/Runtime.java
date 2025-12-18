package com.robomotion.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.protobuf.ByteString;
import com.google.protobuf.Value;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;

/**
 * Runtime provides static utilities for node registration, variable access,
 * vault operations, and runtime communication with the Robomotion platform.
 */
public class Runtime {
    private static RuntimeHelperGrpc.RuntimeHelperBlockingStub client;
    private static Map<String, NodeFactory> factories = new HashMap<>();
    private static Map<String, Node> nodes = new HashMap<>();
    public static int activeNodes = 0;
    public static Boolean started = false;
    private static List<Class<?>> handlers;

    // Properties for configuration
    private static Properties props = new Properties();
    private static Map<String, Object> robotInfo;

    // Capability flags
    public static final long CAPABILITY_LMO = 1L;
    private static long packageCapabilities = CAPABILITY_LMO;

    // Static initialization
    static {
        loadProperties();
    }

    private static void loadProperties() {
        try {
            String home = System.getProperty("user.home");
            Path propsPath = Paths.get(home, ".config", "robomotion", "config.properties");
            if (Files.exists(propsPath)) {
                try (FileInputStream fis = new FileInputStream(propsPath.toFile())) {
                    props.load(fis);
                }
            }
        } catch (Exception e) {
            // Ignore - use defaults
        }
    }

    // Client management
    public static void SetClient(RuntimeHelperGrpc.RuntimeHelperBlockingStub cli) {
        client = cli;
    }

    public static RuntimeHelperGrpc.RuntimeHelperBlockingStub GetClient() {
        return client;
    }

    public static void CheckRunnerConn(ManagedChannel ch) {
        while (true) {
            try {
                ConnectivityState state = ch.getState(true);

                switch (state) {
                    case CONNECTING:
                    case IDLE:
                    case READY:
                        break;

                    default:
                        App.latch.countDown();
                        return;
                }
                Thread.sleep(1000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // Node registration
    public static void CreateNode(String name, NodeFactory factory) {
        factories.put(name, factory);
    }

    public static Map<String, NodeFactory> Factories() {
        return factories;
    }

    public static void AddNode(String guid, Node node) {
        nodes.put(guid, node);
    }

    public static Map<String, Node> Nodes() {
        return nodes;
    }

    public static void RegisterNodes(Class<?>... handlers) {
        Runtime.handlers = List.of(handlers);
    }

    public static List<Class<?>> RegisteredNodes() {
        return Runtime.handlers;
    }

    // Compression utilities
    public static byte[] Compress(byte[] data) {
        if (!getPropertyBool("robomotion.compress", true)) {
            return data;
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
                gos.write(data);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] Decompress(byte[] data) {
        if (!getPropertyBool("robomotion.compress", true)) {
            return data;
        }
        try {
            try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                 GZIPInputStream gis = new GZIPInputStream(bais);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

                byte[] buffer = new byte[1024];
                int len;
                while ((len = gis.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
                return baos.toByteArray();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // Serialization utilities
    public static byte[] Serialize(Object object) {
        try {
            return (new ObjectMapper()).writeValueAsBytes(object);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T Deserialize(byte[] data, Class<T> classOfT) {
        Gson g = new Gson();
        return g.fromJson(new String(data, StandardCharsets.UTF_8), classOfT);
    }

    // Properties utilities
    public static String getProperty(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    public static boolean getPropertyBool(String key, boolean defaultValue) {
        String value = props.getProperty(key);
        if (value == null) return defaultValue;
        return Boolean.parseBoolean(value);
    }

    public static Properties getProperties() {
        return props;
    }

    // Runtime helper methods
    public static void Close() throws RuntimeNotInitializedException {
        if (client == null)
            throw new RuntimeNotInitializedException();

        Empty request = Empty.newBuilder().build();
        client.close(request);
    }

    @SuppressWarnings("unchecked")
    public static <T> T GetVariable(Variable<T> variable, Context ctx) throws RuntimeNotInitializedException {
        if (variable.scope.equals("Custom"))
            return (T) variable.name;
        if (variable.scope.equals("Message")) {
            return (T) ctx.get(variable.name);
        }

        if (client == null)
            throw new RuntimeNotInitializedException();

        com.robomotion.app.Variable var = com.robomotion.app.Variable.newBuilder()
                .setScope(variable.scope)
                .setName(variable.name)
                .setPayload(ByteString.copyFrom(ctx.getRaw()))
                .build();

        GetVariableRequest request = GetVariableRequest.newBuilder().setVariable(var).build();

        GetVariableResponse response = client.getVariable(request);
        Struct st = new Struct(response.getValue());
        return (T) st.Parse();
    }

    public static <T> void SetVariable(Variable<T> variable, Context ctx, T value) throws RuntimeNotInitializedException {
        if (variable.scope.equals("Message")) {
            ctx.set(variable.name, value);
            return;
        }

        if (client == null)
            throw new RuntimeNotInitializedException();

        Value val = Struct.ToValue(value);
        com.google.protobuf.Struct st = com.google.protobuf.Struct.newBuilder().putFields("value", val).build();

        com.robomotion.app.Variable var = com.robomotion.app.Variable.newBuilder()
                .setScope(variable.scope)
                .setName(variable.name)
                .build();

        SetVariableRequest request = SetVariableRequest.newBuilder().setVariable(var).setValue(st).build();
        client.setVariable(request);
    }

    // Robot info methods
    @SuppressWarnings("unchecked")
    public static Map<String, Object> GetRobotInfo() throws RuntimeNotInitializedException {
        if (client == null)
            throw new RuntimeNotInitializedException();

        if (robotInfo == null) {
            Empty request = Empty.newBuilder().build();
            GetRobotInfoResponse response = client.getRobotInfo(request);
            Struct st = new Struct(response.getRobot());
            robotInfo = (Map<String, Object>) st.Parse();
        }
        return robotInfo;
    }

    public static String GetRobotVersion() throws RuntimeNotInitializedException {
        Map<String, Object> info = GetRobotInfo();
        Object version = info.get("version");
        return version != null ? version.toString() : "";
    }

    public static String GetRobotID() throws RuntimeNotInitializedException {
        Map<String, Object> info = GetRobotInfo();
        Object id = info.get("id");
        return id != null ? id.toString() : "";
    }

    // Capability check
    @SuppressWarnings("unchecked")
    public static boolean IsLMOCapable() {
        try {
            Map<String, Object> info = GetRobotInfo();
            Object capsObj = info.get("capabilities");
            if (capsObj instanceof Map) {
                Map<String, Object> caps = (Map<String, Object>) capsObj;
                Object lmo = caps.get("lmo");
                return Boolean.TRUE.equals(lmo);
            }
        } catch (Exception e) {
            // Ignore
        }
        return false;
    }

    // Event emission methods
    public static void EmitDebug(String guid, String name, Object message) throws RuntimeNotInitializedException {
        if (client == null)
            throw new RuntimeNotInitializedException();

        byte[] msgBytes = Serialize(message);
        DebugRequest request = DebugRequest.newBuilder()
                .setGuid(guid)
                .setName(name)
                .setMessage(ByteString.copyFrom(msgBytes != null ? msgBytes : new byte[0]))
                .build();

        client.debug(request);
    }

    public static void EmitOutput(String guid, byte[] output, int port) throws RuntimeNotInitializedException {
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
        if (client == null)
            throw new RuntimeNotInitializedException();

        EmitInputRequest request = EmitInputRequest.newBuilder()
                .setGuid(guid)
                .setInput(ByteString.copyFrom(input))
                .build();

        client.emitInput(request);
    }

    public static void EmitError(String guid, String name, String message) throws RuntimeNotInitializedException {
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
        if (client == null)
            throw new RuntimeNotInitializedException();

        EmitFlowEventRequest request = EmitFlowEventRequest.newBuilder()
                .setGuid(guid)
                .setName(name)
                .build();

        client.emitFlowEvent(request);
    }

    // App request methods
    public static byte[] AppRequest(byte[] data, int timeout) throws RuntimeNotInitializedException {
        if (client == null)
            throw new RuntimeNotInitializedException();

        AppRequestRequest request = AppRequestRequest.newBuilder()
                .setRequest(ByteString.copyFrom(data))
                .setTimeout(timeout)
                .build();

        AppRequestResponse response = client.appRequest(request);
        return response.getResponse().toByteArray();
    }

    public static byte[] AppRequestV2(byte[] data) throws RuntimeNotInitializedException {
        if (client == null)
            throw new RuntimeNotInitializedException();

        AppRequestV2Request request = AppRequestV2Request.newBuilder()
                .setRequest(ByteString.copyFrom(data))
                .build();

        AppRequestV2Response response = client.appRequestV2(request);
        return response.getResponse().toByteArray();
    }

    public static void AppPublish(byte[] data) throws RuntimeNotInitializedException {
        if (client == null)
            throw new RuntimeNotInitializedException();

        AppPublishRequest request = AppPublishRequest.newBuilder()
                .setRequest(ByteString.copyFrom(data))
                .build();

        client.appPublish(request);
    }

    public static String AppDownload(String id, String directory, String file) throws RuntimeNotInitializedException {
        if (client == null)
            throw new RuntimeNotInitializedException();

        AppDownloadRequest request = AppDownloadRequest.newBuilder()
                .setId(id)
                .setDirectory(directory)
                .setFile(file)
                .build();

        AppDownloadResponse response = client.appDownload(request);
        return response.getPath();
    }

    public static String AppUpload(String id, String path) throws RuntimeNotInitializedException {
        if (client == null)
            throw new RuntimeNotInitializedException();

        AppUploadRequest request = AppUploadRequest.newBuilder()
                .setId(id)
                .setPath(path)
                .build();

        AppUploadResponse response = client.appUpload(request);
        return response.getUrl();
    }

    // Gateway/Proxy request methods
    public static GatewayRequestResponse GatewayRequest(String method, String endpoint, String body, Map<String, String> headers) throws RuntimeNotInitializedException {
        if (client == null)
            throw new RuntimeNotInitializedException();

        GatewayRequestRequest.Builder builder = GatewayRequestRequest.newBuilder()
                .setMethod(method)
                .setEndpoint(endpoint)
                .setBody(body != null ? body : "");

        if (headers != null) {
            builder.putAllHeaders(headers);
        }

        return client.gatewayRequest(builder.build());
    }

    public static HttpResponse ProxyRequest(HttpRequest request) throws RuntimeNotInitializedException {
        if (client == null)
            throw new RuntimeNotInitializedException();

        return client.proxyRequest(request);
    }

    // Running state
    public static boolean IsRunning() throws RuntimeNotInitializedException {
        if (client == null)
            throw new RuntimeNotInitializedException();

        Empty request = Empty.newBuilder().build();
        IsRunningResponse response = client.isRunning(request);
        return response.getIsRunning();
    }

    // Port connections
    public static List<NodeInfo> GetPortConnections(String guid, int port) throws RuntimeNotInitializedException {
        if (client == null)
            throw new RuntimeNotInitializedException();

        GetPortConnectionsRequest request = GetPortConnectionsRequest.newBuilder()
                .setGuid(guid)
                .setPort(port)
                .build();

        GetPortConnectionsResponse response = client.getPortConnections(request);
        return response.getNodesList();
    }

    // Instance access
    public static InstanceAccess GetInstanceAccess() throws RuntimeNotInitializedException {
        if (client == null)
            throw new RuntimeNotInitializedException();

        Empty request = Empty.newBuilder().build();
        GetInstanceAccessResponse response = client.getInstanceAccess(request);

        return new InstanceAccess(
                response.getAmqEndpoint(),
                response.getApiEndpoint(),
                response.getAccessToken()
        );
    }

    // Download file (legacy)
    public static void DownloadFile(String url, String path) throws RuntimeNotInitializedException {
        if (client == null)
            throw new RuntimeNotInitializedException();

        DownloadFileRequest request = DownloadFileRequest.newBuilder()
                .setUrl(url)
                .setPath(path)
                .build();

        client.downloadFile(request);
    }

    // Variable types
    public static class Variable<T> {
        public String scope;
        public String name;

        public Variable(String scope, String name) {
            this.scope = scope;
            this.name = name;
        }
    }

    public static class InVariable<T> extends Variable<T> {
        public InVariable(String scope, String name) {
            super(scope, name);
        }

        public T Get(Context ctx) throws RuntimeNotInitializedException {
            return Runtime.GetVariable(this, ctx);
        }
    }

    public static class OutVariable<T> extends Variable<T> {
        public OutVariable(String scope, String name) {
            super(scope, name);
        }

        public void Set(Context ctx, T value) throws RuntimeNotInitializedException {
            Runtime.SetVariable(this, ctx, value);
        }
    }

    public static class OptVariable<T> extends Variable<T> {
        public OptVariable(String scope, String name) {
            super(scope, name);
        }

        public T Get(Context ctx) throws RuntimeNotInitializedException {
            return Runtime.GetVariable(this, ctx);
        }
    }

    // Credential class
    @SuppressWarnings("unchecked")
    public static class Credential {
        public String scope;
        public Object name;

        @Deprecated
        public String vaultId;
        @Deprecated
        public String itemId;

        public Credential(String scope, Object name, String vaultId, String itemId) {
            this.scope = scope;
            this.name = name;
            this.vaultId = vaultId;
            this.itemId = itemId;
        }

        public Map<String, Object> Get(Context ctx) throws RuntimeNotInitializedException {
            if (client == null)
                throw new RuntimeNotInitializedException();

            CredentialInfo creds;
            if (this.vaultId != null && this.itemId != null) {
                creds = new CredentialInfo(this.vaultId, this.itemId);
            } else {
                Object cr = this.name;
                if (this.scope.equals("Message")) {
                    InVariable<Object> v = new InVariable<>("Message", this.name.toString());
                    cr = v.Get(ctx);
                }

                Map<String, Object> crMap = (Map<String, Object>) cr;
                creds = new CredentialInfo((String) crMap.get("vaultId"), (String) crMap.get("itemId"));
            }

            GetVaultItemRequest request = GetVaultItemRequest.newBuilder()
                    .setItemId(creds.itemId)
                    .setVaultId(creds.vaultId)
                    .build();

            GetVaultItemResponse response = client.getVaultItem(request);
            Struct st = new Struct(response.getItem());
            return (Map<String, Object>) st.Parse();
        }

        public Map<String, Object> Set(Context ctx, byte[] data) throws RuntimeNotInitializedException {
            if (client == null)
                throw new RuntimeNotInitializedException();

            CredentialInfo creds;
            if (this.vaultId != null && this.itemId != null) {
                creds = new CredentialInfo(this.vaultId, this.itemId);
            } else {
                Object cr = this.name;
                if (this.scope.equals("Message")) {
                    InVariable<Object> v = new InVariable<>("Message", this.name.toString());
                    cr = v.Get(ctx);
                }

                Map<String, Object> crMap = (Map<String, Object>) cr;
                creds = new CredentialInfo((String) crMap.get("vaultId"), (String) crMap.get("itemId"));
            }

            SetVaultItemRequest request = SetVaultItemRequest.newBuilder()
                    .setVaultId(creds.vaultId)
                    .setItemId(creds.itemId)
                    .setData(ByteString.copyFrom(data))
                    .build();

            SetVaultItemResponse response = client.setVaultItem(request);
            Struct st = new Struct(response.getItem());
            return (Map<String, Object>) st.Parse();
        }
    }

    private static class CredentialInfo {
        public String vaultId;
        public String itemId;

        public CredentialInfo(String vaultId, String itemId) {
            this.vaultId = vaultId;
            this.itemId = itemId;
        }
    }

    // Instance access data class
    public static class InstanceAccess {
        public final String amqEndpoint;
        public final String apiEndpoint;
        public final String accessToken;

        public InstanceAccess(String amqEndpoint, String apiEndpoint, String accessToken) {
            this.amqEndpoint = amqEndpoint;
            this.apiEndpoint = apiEndpoint;
            this.accessToken = accessToken;
        }
    }

    // Port type for custom ports
    public static class Port {
        private final String[] guids;

        public Port(String... guids) {
            this.guids = guids;
        }

        public String[] getGuids() {
            return guids;
        }
    }
}
