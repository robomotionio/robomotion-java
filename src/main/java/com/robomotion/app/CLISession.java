package com.robomotion.app;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;

/**
 * CLI session/daemon support for persistent node processes.
 * <p>
 * The daemon is a long-running gRPC server that reuses the existing {@link NodeServer}.
 * Clients connect via TCP localhost and send commands as gRPC calls.
 * This avoids JVM startup overhead and credential re-fetching on repeated invocations.
 */
public class CLISession {

    static final long DEFAULT_SESSION_TIMEOUT_MS = 30 * 60 * 1000; // 30 minutes
    private static final long DAEMON_POLL_INTERVAL_MS = 100;
    private static final long DAEMON_POLL_MAX_WAIT_MS = 3000;
    private static final int MAX_SESSION_MSG_SIZE = 64 * 1024 * 1024; // 64 MB

    private static final ObjectMapper mapper = new ObjectMapper();

    // --- Daemon ---

    /**
     * Runs the session daemon process. Called internally via --session-daemon &lt;id&gt;.
     * Blocks until timeout or explicit close.
     */
    public static void runDaemon(String sessionID, long timeoutMs, String vaultID, String itemID) {
        // Set up CLI runtime helper
        CLIRuntimeHelper cliHelper = new CLIRuntimeHelper();

        if (vaultID != null && !vaultID.isEmpty() && itemID != null && !itemID.isEmpty()) {
            try {
                CLIVaultClient vc = new CLIVaultClient();
                Map<String, Object> creds = vc.fetchVaultItem(vaultID, itemID);
                cliHelper.setCredentials(creds);
            } catch (Exception e) {
                daemonError("vault: %s", e.getMessage());
            }
        }

        Runtime.testHelper = cliHelper;
        Runtime.cliMode = true;
        Runtime.sessionMode = true;

        // Register node factories
        try {
            App.Init();
        } catch (Exception e) {
            daemonError("init: %s", e.getMessage());
        }

        try {
            // Shared references for timeout interceptor
            AtomicReference<Server> serverRef = new AtomicReference<>();
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "session-timeout");
                t.setDaemon(true);
                return t;
            });
            AtomicReference<ScheduledFuture<?>> timeoutRef = new AtomicReference<>();

            Runnable timeoutAction = () -> {
                System.err.printf("{\"info\":\"session.timeout\",\"session_id\":\"%s\"}%n", sessionID);
                closeAllSessionNodes();
                Server s = serverRef.get();
                if (s != null) s.shutdown();
                sessionCleanup(sessionID);
            };

            // Interceptor resets timeout on each gRPC call
            ServerInterceptor interceptor = new ServerInterceptor() {
                @Override
                public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                        ServerCall<ReqT, RespT> call, Metadata headers,
                        ServerCallHandler<ReqT, RespT> next) {
                    ScheduledFuture<?> old = timeoutRef.getAndSet(
                            scheduler.schedule(timeoutAction, timeoutMs, TimeUnit.MILLISECONDS));
                    if (old != null) old.cancel(false);
                    return next.startCall(call, headers);
                }
            };

            Server server = ServerBuilder.forPort(0)
                    .addService(ServerInterceptors.intercept(new NodeServer(), interceptor))
                    .maxInboundMessageSize(MAX_SESSION_MSG_SIZE)
                    .build()
                    .start();

            serverRef.set(server);
            int port = server.getPort();

            // Write port file and metadata
            writePortFile(sessionID, port);
            writeSessionMetadata(sessionID);

            // Start inactivity timeout
            timeoutRef.set(scheduler.schedule(timeoutAction, timeoutMs, TimeUnit.MILLISECONDS));

            // Block until server stops
            server.awaitTermination();
            scheduler.shutdownNow();

        } catch (Exception e) {
            daemonError("serve: %s", e.getMessage());
        }
    }

    // --- Client ---

    /**
     * Connects to an existing session daemon and sends a command.
     */
    @SuppressWarnings("unchecked")
    public static void runClient(String sessionID, String commandName,
            CLI.CommandEntry cmd, Map<String, String> flags) {
        String addr = sessionDialAddr(sessionID);
        if (addr == null || addr.isEmpty()) {
            CLI.cliError("session %s not found (no port file)", sessionID);
            return;
        }

        String[] hostPort = addr.split(":");
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(hostPort[0], Integer.parseInt(hostPort[1]))
                .usePlaintext()
                .maxInboundMessageSize(MAX_SESSION_MSG_SIZE)
                .build();

        try {
            NodeGrpc.NodeBlockingStub nodeClient = NodeGrpc.newBlockingStub(channel);

            // Unique guid per invocation
            String guid = commandName + "-" + generateSessionID();

            // Extract vault flags
            String vaultID = flags.remove("vault-id");
            String itemID = flags.remove("item-id");

            // Build flag map from temp node
            Node tempNode;
            try {
                tempNode = (Node) cmd.nodeClass.getDeclaredConstructor().newInstance();
                CLI.initializeNodeFields(cmd.nodeClass, tempNode);
            } catch (Exception e) {
                CLI.cliError("failed to inspect node: %s", e.getMessage());
                return;
            }

            Map<String, CLI.FlagEntry> flagMap = CLI.buildFlagMap(cmd.nodeClass, tempNode);

            // Split flags into config patches and message data
            Map<String, Object> msgData = new LinkedHashMap<>();
            Map<String, Object> configPatches = new LinkedHashMap<>();

            for (Map.Entry<String, String> entry : flags.entrySet()) {
                String flagName = entry.getKey();
                String value = entry.getValue();

                CLI.FlagEntry fe = flagMap.get(flagName);
                if (fe == null) {
                    msgData.put(CLI.kebabToCamel(flagName), CLI.tryParseJSON(value));
                    continue;
                }

                String fieldName = Spec.LowerFirstLetter(fe.field.getName());
                if (fe.isOption) {
                    configPatches.put(fieldName, value);
                } else if ("Custom".equals(fe.scope)) {
                    Map<String, Object> varPatch = new LinkedHashMap<>();
                    varPatch.put("scope", "Custom");
                    varPatch.put("name", value);
                    configPatches.put(fieldName, varPatch);
                } else {
                    msgData.put(fe.specName, CLI.tryParseJSON(value));
                }
            }

            // Build node config JSON
            Map<String, Object> nodeConfig = buildNodeConfig(
                    cmd.nodeClass, tempNode, guid, commandName, vaultID, itemID, configPatches);

            byte[] configJSON = Runtime.Serialize(nodeConfig);

            // Call onCreate
            nodeClient.onCreate(OnCreateRequest.newBuilder()
                    .setName(cmd.nodeID)
                    .setConfig(ByteString.copyFrom(configJSON))
                    .build());

            // Update metadata with new node guid
            SessionMetadata meta = readSessionMetadata(sessionID);
            if (meta != null) {
                meta.nodes.add(guid);
                meta.lastActivity = java.time.Instant.now().toString();
                saveSessionMetadata(sessionID, meta);
            }

            // Build and compress message
            byte[] msgJSON = Runtime.Serialize(msgData);
            byte[] compressed = Runtime.Compress(msgJSON != null ? msgJSON : "{}".getBytes());

            // Call onMessage
            OnMessageResponse resp = nodeClient.onMessage(OnMessageRequest.newBuilder()
                    .setGuid(guid)
                    .setInMessage(ByteString.copyFrom(compressed))
                    .build());

            // Parse response
            byte[] outData = resp.getOutMessage().toByteArray();
            Map<String, Object> result;
            if (outData != null && outData.length > 0) {
                try {
                    result = mapper.readValue(outData, Map.class);
                } catch (Exception e) {
                    result = new LinkedHashMap<>();
                    result.put("result", new String(outData, StandardCharsets.UTF_8));
                }
            } else {
                result = new LinkedHashMap<>();
                result.put("status", "completed");
            }
            result.put("session_id", sessionID);

            byte[] output = Runtime.Serialize(result);
            System.out.println(new String(output, StandardCharsets.UTF_8));

        } catch (io.grpc.StatusRuntimeException e) {
            CLI.cliError("session call failed: %s", e.getStatus().getDescription());
        } catch (Exception e) {
            CLI.cliError("session error: %s", e.getMessage());
        } finally {
            channel.shutdown();
        }
    }

    // --- Close ---

    /**
     * Sends OnClose for all nodes and the daemon eventually exits on timeout.
     */
    public static void closeSession(String sessionID) {
        SessionMetadata meta = readSessionMetadata(sessionID);
        if (meta == null) {
            CLI.cliError("cannot read session %s", sessionID);
            return;
        }

        String addr = sessionDialAddr(sessionID);
        if (addr == null || addr.isEmpty()) {
            CLI.cliError("session %s not reachable", sessionID);
            return;
        }

        String[] hostPort = addr.split(":");
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(hostPort[0], Integer.parseInt(hostPort[1]))
                .usePlaintext()
                .maxInboundMessageSize(MAX_SESSION_MSG_SIZE)
                .build();

        try {
            NodeGrpc.NodeBlockingStub nodeClient = NodeGrpc.newBlockingStub(channel);

            for (String guid : meta.nodes) {
                try {
                    nodeClient.onClose(OnCloseRequest.newBuilder().setGuid(guid).build());
                } catch (Exception e) {
                    System.err.printf("{\"warning\":\"OnClose %s: %s\"}%n", guid, e.getMessage());
                }
            }

            Map<String, String> result = Map.of("status", "closed");
            byte[] output = Runtime.Serialize(result);
            System.out.println(new String(output, StandardCharsets.UTF_8));

        } finally {
            channel.shutdown();
        }
    }

    // --- Daemon process management ---

    /**
     * Forks the current binary as a session daemon, waits for it to be ready.
     */
    public static void startDaemonProcess(String sessionID, long timeoutMs,
            String vaultID, String itemID) {
        List<String> cmd = getBaseCommand();
        cmd.add("--session-daemon");
        cmd.add(sessionID);

        if (timeoutMs != DEFAULT_SESSION_TIMEOUT_MS) {
            cmd.add("--session-timeout=" + timeoutMs + "ms");
        }
        if (vaultID != null && !vaultID.isEmpty()) {
            cmd.add("--vault-id=" + vaultID);
        }
        if (itemID != null && !itemID.isEmpty()) {
            cmd.add("--item-id=" + itemID);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File("."));
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process proc = pb.start();
            proc.getOutputStream().close();
            // Don't wait — it's a daemon

            // Poll for port file to appear
            long deadline = System.currentTimeMillis() + DAEMON_POLL_MAX_WAIT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (sessionReady(sessionID)) {
                    return;
                }
                Thread.sleep(DAEMON_POLL_INTERVAL_MS);
            }

            CLI.cliError("session daemon did not start within %dms", DAEMON_POLL_MAX_WAIT_MS);

        } catch (Exception e) {
            CLI.cliError("failed to start session daemon: %s", e.getMessage());
        }
    }

    // --- Node config builder ---

    private static Map<String, Object> buildNodeConfig(Class<?> nodeClass, Node tempNode,
            String guid, String commandName, String vaultID, String itemID,
            Map<String, Object> configPatches) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("guid", guid);
        config.put("name", commandName);

        for (Field f : nodeClass.getFields()) {
            String fieldName = Spec.LowerFirstLetter(f.getName());
            if (configPatches.containsKey(fieldName)) continue;

            java.lang.reflect.Type t = f.getGenericType();
            if (t instanceof ParameterizedType pT) {
                Class<?> rawType = (Class<?>) pT.getRawType();
                if (Runtime.Variable.class.isAssignableFrom(rawType)) {
                    try {
                        f.setAccessible(true);
                        Runtime.Variable<?> variable = (Runtime.Variable<?>) f.get(tempNode);
                        if (variable != null) {
                            Map<String, Object> varConfig = new LinkedHashMap<>();
                            varConfig.put("scope", variable.scope);
                            varConfig.put("name", variable.name != null ? variable.name : "");
                            config.put(fieldName, varConfig);
                        }
                    } catch (Exception e) { /* skip */ }
                }
            } else if (Runtime.Credential.class.isAssignableFrom(f.getType())) {
                if (vaultID != null && !vaultID.isEmpty() && itemID != null && !itemID.isEmpty()) {
                    Map<String, Object> credConfig = new LinkedHashMap<>();
                    credConfig.put("scope", "Custom");
                    Map<String, String> credName = new LinkedHashMap<>();
                    credName.put("vaultId", vaultID);
                    credName.put("itemId", itemID);
                    credConfig.put("name", credName);
                    config.put(fieldName, credConfig);
                }
            } else if (!Tool.class.isAssignableFrom(f.getType())) {
                // Option field defaults
                try {
                    f.setAccessible(true);
                    Object val = f.get(tempNode);
                    if (val != null && !isNodeField(f)) {
                        config.put(fieldName, val);
                    }
                } catch (Exception e) { /* skip */ }
            }
        }

        config.putAll(configPatches);
        return config;
    }

    private static boolean isNodeField(Field f) {
        return f.getDeclaringClass() == Node.class;
    }

    // --- Platform-specific session directory ---

    static String sessionDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("windows")) {
            String appData = System.getenv("LOCALAPPDATA");
            if (appData != null && !appData.isEmpty()) {
                return Paths.get(appData, "Robomotion", "sessions").toString();
            }
            return Paths.get(System.getProperty("java.io.tmpdir"), "robomotion-sessions").toString();
        }
        if (os.contains("mac")) {
            String tmpDir = System.getenv("TMPDIR");
            if (tmpDir != null && !tmpDir.isEmpty()) {
                return Paths.get(tmpDir, "robomotion-sessions").toString();
            }
        } else {
            String xdg = System.getenv("XDG_RUNTIME_DIR");
            if (xdg != null && !xdg.isEmpty()) {
                return Paths.get(xdg, "robomotion-sessions").toString();
            }
        }
        return "/tmp/robomotion-sessions";
    }

    // --- Port file management ---

    private static void writePortFile(String sessionID, int port) throws Exception {
        Path dir = Path.of(sessionDir());
        Files.createDirectories(dir);
        Path portFile = dir.resolve(sessionID + ".port");
        Files.writeString(portFile, String.valueOf(port));
    }

    private static String sessionDialAddr(String sessionID) {
        Path portFile = Path.of(sessionDir(), sessionID + ".port");
        try {
            String port = Files.readString(portFile).trim();
            Integer.parseInt(port); // validate
            return "127.0.0.1:" + port;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean sessionReady(String sessionID) {
        return sessionDialAddr(sessionID) != null;
    }

    private static void sessionCleanup(String sessionID) {
        Path dir = Path.of(sessionDir());
        try { Files.deleteIfExists(dir.resolve(sessionID + ".port")); } catch (Exception e) { }
        try { Files.deleteIfExists(dir.resolve(sessionID + ".json")); } catch (Exception e) { }
    }

    // --- Metadata ---

    static class SessionMetadata {
        public String session_id;
        public int pid;
        public String namespace;
        public String created_at;
        public String lastActivity;
        public List<String> nodes = new ArrayList<>();
    }

    private static void writeSessionMetadata(String sessionID) {
        try {
            Path dir = Path.of(sessionDir());
            Files.createDirectories(dir);

            String namespace = "";
            try {
                org.json.simple.JSONObject config = App.ReadConfigFile();
                Object ns = config.get("namespace");
                if (ns != null) namespace = ns.toString();
            } catch (Exception e) { /* ignore */ }

            SessionMetadata meta = new SessionMetadata();
            meta.session_id = sessionID;
            meta.pid = (int) ProcessHandle.current().pid();
            meta.namespace = namespace;
            meta.created_at = java.time.Instant.now().toString();
            meta.lastActivity = meta.created_at;

            Path metaFile = dir.resolve(sessionID + ".json");
            Files.writeString(metaFile, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(meta));
        } catch (Exception e) { /* ignore */ }
    }

    static SessionMetadata readSessionMetadata(String sessionID) {
        try {
            Path metaFile = Path.of(sessionDir(), sessionID + ".json");
            return mapper.readValue(Files.readString(metaFile), SessionMetadata.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveSessionMetadata(String sessionID, SessionMetadata meta) {
        try {
            Path metaFile = Path.of(sessionDir(), sessionID + ".json");
            Files.writeString(metaFile, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(meta));
        } catch (Exception e) { /* ignore */ }
    }

    // --- Node cleanup ---

    private static void closeAllSessionNodes() {
        for (Map.Entry<String, Node> entry : new ArrayList<>(Runtime.Nodes().entrySet())) {
            try {
                entry.getValue().OnClose();
            } catch (Exception e) { /* ignore */ }
        }
    }

    // --- Utilities ---

    static String generateSessionID() {
        byte[] b = new byte[4];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format("%02x", v & 0xFF));
        return sb.toString();
    }

    static long parseSessionTimeout(String s) {
        if (s == null || s.isEmpty()) return DEFAULT_SESSION_TIMEOUT_MS;
        try {
            s = s.trim().toLowerCase();
            if (s.endsWith("ms")) {
                return Long.parseLong(s.substring(0, s.length() - 2));
            } else if (s.endsWith("s")) {
                return (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1000);
            } else if (s.endsWith("m")) {
                return (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 60_000);
            } else if (s.endsWith("h")) {
                return (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 3_600_000);
            } else {
                return (long) (Double.parseDouble(s) * 1000);
            }
        } catch (Exception e) {
            return DEFAULT_SESSION_TIMEOUT_MS;
        }
    }

    /**
     * Builds the command to start a new JVM running the same application.
     * Handles both JAR mode and GraalVM native-image mode.
     */
    private static List<String> getBaseCommand() {
        List<String> cmd = new ArrayList<>();

        // Check GraalVM native image
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            String exe = ProcessHandle.current().info().command().orElse(null);
            if (exe != null) {
                cmd.add(exe);
                return cmd;
            }
        }

        // JAR mode
        String javaExe = ProcessHandle.current().info().command().orElse("java");
        cmd.add(javaExe);

        // Detect JAR path from sun.java.command or java.class.path
        String sunCmd = System.getProperty("sun.java.command", "");
        String mainPart = sunCmd.split("\\s+")[0];
        if (mainPart.endsWith(".jar")) {
            cmd.add("-jar");
            cmd.add(new File(mainPart).getAbsolutePath());
        } else {
            // Fallback: try classpath
            String cp = System.getProperty("java.class.path", "");
            if (cp.endsWith(".jar")) {
                cmd.add("-jar");
                cmd.add(new File(cp).getAbsolutePath());
            } else {
                // Last resort: use classpath and main class
                cmd.add("-cp");
                cmd.add(cp);
                cmd.add(mainPart);
            }
        }

        return cmd;
    }

    private static void daemonError(String format, Object... args) {
        String msg = String.format(format, args);
        System.err.printf("{\"error\":\"%s\"}%n", msg.replace("\"", "\\\""));
        System.exit(1);
    }
}
