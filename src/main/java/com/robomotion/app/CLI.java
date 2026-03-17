package com.robomotion.app;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.robomotion.app.Runtime.Credential;
import com.robomotion.app.Runtime.InVariable;
import com.robomotion.app.Runtime.OptVariable;
import com.robomotion.app.Runtime.OutVariable;

import org.json.simple.JSONObject;

/**
 * CLI execution engine for running Robomotion packages from the command line.
 * AI agents call the binary with a command and --key=value flags, get JSON output on stdout.
 */
public class CLI {

    // --- Entry point ---

    public static void run(String[] args) {
        if (args.length == 0) {
            cliError("no command specified; use --list-commands to see available commands");
            return;
        }

        String commandName = args[0];

        switch (commandName) {
            case "--list-commands":
                if (args.length > 1 && !args[1].startsWith("-")) {
                    listCommandDetail(args[1]);
                } else {
                    listCommands(args);
                }
                return;
            case "--skill-md":
                try {
                    JSONObject config = App.ReadConfigFile();
                    String name = config.get("name").toString();
                    String version = config.get("version").toString();
                    Object descObj = config.get("description");
                    String description = descObj != null ? descObj.toString() : name;
                    SkillMD.generate(name, version, description);
                } catch (Exception e) {
                    cliError("failed to read config: %s", e.getMessage());
                }
                return;
            case "--help":
            case "-h":
                printHelp();
                return;
        }

        // Build tool name → node class mapping
        Map<String, CommandEntry> commands = buildCommandMap();

        CommandEntry cmd = commands.get(commandName);
        if (cmd == null) {
            String available = String.join(", ", commands.keySet());
            cliError("unknown command \"%s\"; available commands: %s", commandName, available);
            return;
        }

        // Parse --key=value flags from remaining args
        Map<String, String> flags;
        try {
            flags = parseFlags(args, 1);
        } catch (Exception e) {
            cliError("%s", e.getMessage());
            return;
        }

        runCommand(commandName, cmd, flags);
    }

    // --- Core execution ---

    private static void runCommand(String commandName, CommandEntry cmd, Map<String, String> flags) {
        // Extract global flags
        String vaultID = flags.remove("vault-id");
        String itemID = flags.remove("item-id");
        String vaultName = flags.remove("vault");
        String itemName = flags.remove("item");
        flags.remove("output");

        // Resolve --vault/--item names to IDs if needed
        if (vaultName != null || itemName != null) {
            if (vaultID != null || itemID != null) {
                cliError("cannot use --vault/--item together with --vault-id/--item-id");
                return;
            }
            if (vaultName == null || itemName == null) {
                cliError("--vault and --item must both be provided");
                return;
            }
            try {
                CLIVaultClient vc = new CLIVaultClient();
                vaultID = vc.resolveVaultByName(vaultName);
                itemID = vc.resolveItemByName(vaultID, itemName);
            } catch (Exception e) {
                cliError("vault error: %s", e.getMessage());
                return;
            }
        }

        // Extract session flags
        boolean sessionStart = "true".equals(flags.remove("session"));
        String sessionID = flags.remove("session-id");
        String timeoutStr = flags.remove("session-timeout");

        // Session reuse: send command to existing daemon
        if (sessionID != null && !sessionID.isEmpty()) {
            if (vaultID != null) flags.put("vault-id", vaultID);
            if (itemID != null) flags.put("item-id", itemID);
            CLISession.runClient(sessionID, commandName, cmd, flags);
            return;
        }

        // Session start: fork daemon, then send first command as client
        if (sessionStart) {
            long timeout = CLISession.parseSessionTimeout(timeoutStr);
            String id = CLISession.generateSessionID();
            CLISession.startDaemonProcess(id, timeout, vaultID, itemID);
            if (vaultID != null) flags.put("vault-id", vaultID);
            if (itemID != null) flags.put("item-id", itemID);
            CLISession.runClient(id, commandName, cmd, flags);
            return;
        }

        // Set up CLI runtime helper
        CLIRuntimeHelper cliHelper = new CLIRuntimeHelper();

        // Handle credentials from vault flags
        if (vaultID != null && itemID != null) {
            try {
                CLIVaultClient vaultClient = new CLIVaultClient();
                Map<String, Object> creds = vaultClient.fetchVaultItem(vaultID, itemID);
                cliHelper.setCredentials(creds);
            } catch (Exception e) {
                cliError("vault fetch error: %s", e.getMessage());
                return;
            }
        }

        // Set global helpers
        Runtime.testHelper = cliHelper;
        Runtime.cliMode = true;

        try {
            // Instantiate node
            Node node = (Node) cmd.nodeClass.getDeclaredConstructor().newInstance();
            node.guid = "cli-node";
            node.name = commandName;

            // Initialize null Variable/Credential/option fields from annotations
            // (needed for legacy-style nodes where fields lack initializers)
            initializeNodeFields(cmd.nodeClass, node);

            // If vault flags: set credential vaultId/itemId on Credential fields
            if (vaultID != null && itemID != null) {
                injectCredentials(cmd.nodeClass, node, vaultID, itemID);
            }

            // Build message context from flags
            Map<String, Object> msgData = new LinkedHashMap<>();
            applyFlags(cmd.nodeClass, node, flags, msgData);

            // Build message context
            byte[] msgJSON = Runtime.Serialize(msgData);
            Context ctx = new Message(msgJSON != null ? msgJSON : "{}".getBytes(StandardCharsets.UTF_8));

            // Run node lifecycle: OnCreate → OnMessage → OnClose
            node.OnCreate();

            Exception onMessageError = null;
            try {
                node.OnMessage(ctx);
            } catch (Exception e) {
                onMessageError = e;
            }

            Exception onCloseError = null;
            try {
                node.OnClose();
            } catch (Exception e) {
                onCloseError = e;
            }

            if (onMessageError != null) {
                cliError("%s", onMessageError.getMessage());
                return;
            }
            if (onCloseError != null) {
                cliError("OnClose failed: %s", onCloseError.getMessage());
                return;
            }

            // Collect output variables from context
            Map<String, Object> output = collectOutput(cmd.nodeClass, node, ctx);

            // Print JSON result to stdout
            byte[] result = Runtime.Serialize(output);
            System.out.println(new String(result, StandardCharsets.UTF_8));

        } catch (Exception e) {
            cliError("failed to execute command: %s", e.getMessage());
        }
    }

    // --- Command map ---

    static Map<String, CommandEntry> buildCommandMap() {
        Map<String, CommandEntry> commands = new LinkedHashMap<>();
        List<Class<?>> classes = Runtime.RegisteredNodes();
        if (classes == null) return commands;

        for (Class<?> c : classes) {
            for (Field f : c.getFields()) {
                if (Tool.class.isAssignableFrom(f.getType())) {
                    Tool.ToolInfo info = f.getAnnotation(Tool.ToolInfo.class);
                    if (info != null && !info.name().isEmpty()) {
                        commands.put(info.name(), new CommandEntry(
                                c, Spec.GetNamespace(c), info.name(), info.description()));
                        break;
                    }
                }
            }
        }

        return commands;
    }

    // --- Flag parsing ---

    static Map<String, String> parseFlags(String[] args, int startIndex) throws Exception {
        Map<String, String> flags = new LinkedHashMap<>();
        Map<String, List<String>> multi = new LinkedHashMap<>();

        for (int i = startIndex; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new Exception("unexpected argument \"" + arg + "\" (expected --flag=value)");
            }

            arg = arg.substring(2); // strip --

            String key, value;
            int eqIdx = arg.indexOf('=');
            if (eqIdx >= 0) {
                key = arg.substring(0, eqIdx);
                value = arg.substring(eqIdx + 1);
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                key = arg;
                value = args[i + 1];
                i++;
            } else {
                key = arg;
                value = "true";
            }

            if (flags.containsKey(key)) {
                if (!multi.containsKey(key)) {
                    multi.put(key, new ArrayList<>(List.of(flags.get(key))));
                }
                multi.get(key).add(value);
            } else {
                flags.put(key, value);
            }
        }

        // Encode repeated flags as JSON arrays
        for (Map.Entry<String, List<String>> entry : multi.entrySet()) {
            byte[] encoded = Runtime.Serialize(entry.getValue());
            if (encoded != null) {
                flags.put(entry.getKey(), new String(encoded, StandardCharsets.UTF_8));
            }
        }

        return flags;
    }

    // --- Flag mapping and application ---

    static Map<String, FlagEntry> buildFlagMap(Class<?> nodeClass, Node node) {
        Map<String, FlagEntry> mapping = new LinkedHashMap<>();

        for (Field f : nodeClass.getFields()) {
            java.lang.reflect.Type t = f.getGenericType();

            if (t instanceof ParameterizedType pT) {
                Class<?> rawType = (Class<?>) pT.getRawType();
                if (Runtime.Variable.class.isAssignableFrom(rawType)) {
                    try {
                        f.setAccessible(true);
                        Runtime.Variable<?> variable = (Runtime.Variable<?>) f.get(node);
                        if (variable == null) continue;

                        String specName = variable.getNameString();
                        String scope = variable.scope;

                        if (specName != null && !specName.isEmpty()) {
                            String flagName = camelToKebab(specName);
                            mapping.put(flagName, new FlagEntry(f, specName, scope, false));
                        } else {
                            // Unnamed variable (Custom scope): derive from title
                            String title = Spec.GetTitle(f);
                            if (title != null && !title.isEmpty()) {
                                String flagName = camelToKebab(title);
                                mapping.put(flagName, new FlagEntry(f, "", "Custom", false));
                            }
                        }
                    } catch (Exception e) {
                        continue;
                    }
                }
            } else if (!Tool.class.isAssignableFrom(f.getType())
                    && !Credential.class.isAssignableFrom(f.getType())) {
                // Check for enum option fields
                String enumValues = getEnumValues(f);
                if (enumValues != null && !enumValues.isEmpty()) {
                    String title = Spec.GetTitle(f);
                    if (title == null || title.isEmpty()) {
                        title = Spec.LowerFirstLetter(f.getName());
                    }
                    String flagName = camelToKebab(title);
                    mapping.put(flagName, new FlagEntry(f, Spec.LowerFirstLetter(f.getName()), "", true));
                }
            }
        }

        return mapping;
    }

    private static void applyFlags(Class<?> nodeClass, Node node, Map<String, String> flags,
            Map<String, Object> msgData) {
        Map<String, FlagEntry> flagMap = buildFlagMap(nodeClass, node);

        for (Map.Entry<String, String> entry : flags.entrySet()) {
            String flagName = entry.getKey();
            String value = entry.getValue();

            FlagEntry fe = flagMap.get(flagName);
            if (fe == null) {
                // Unknown flag → message context (kebab → camel)
                String camelKey = kebabToCamel(flagName);
                msgData.put(camelKey, tryParseJSON(value));
                continue;
            }

            try {
                if (fe.isOption) {
                    // Set field value directly on node
                    fe.field.setAccessible(true);
                    setFieldValue(fe.field, node, value);
                } else if ("Custom".equals(fe.scope)) {
                    // Custom-scope: set variable.name = value
                    fe.field.setAccessible(true);
                    Runtime.Variable<?> variable = (Runtime.Variable<?>) fe.field.get(node);
                    variable.name = value;
                } else {
                    // Message scope: put in message context
                    // Parse JSON arrays/objects so they serialize correctly
                    msgData.put(fe.specName, tryParseJSON(value));
                }
            } catch (Exception e) {
                // Skip this flag on error
            }
        }
    }

    private static void injectCredentials(Class<?> nodeClass, Node node, String vaultID, String itemID) {
        for (Field f : nodeClass.getFields()) {
            if (Credential.class.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    Credential cred = (Credential) f.get(node);
                    if (cred != null) {
                        cred.vaultId = vaultID;
                        cred.itemId = itemID;
                    }
                } catch (Exception e) {
                    // Skip
                }
            }
        }
    }

    /**
     * Initializes null Variable, Credential, and option fields from annotations.
     * Needed for legacy-style nodes where fields lack initializers (populated by
     * Gson deserialization in gRPC mode but null after plain constructor call).
     */
    @SuppressWarnings("unchecked")
    static void initializeNodeFields(Class<?> nodeClass, Node node) throws Exception {
        for (Field f : nodeClass.getFields()) {
            f.setAccessible(true);

            java.lang.reflect.Type t = f.getGenericType();
            if (t instanceof ParameterizedType pT) {
                Class<?> rawType = (Class<?>) pT.getRawType();

                // Initialize null Variable fields from @FieldAnnotations.Default or @Var
                if (Runtime.Variable.class.isAssignableFrom(rawType) && f.get(node) == null) {
                    String scope = "";
                    String name = "";

                    FieldAnnotations.Default defAnn = f.getAnnotation(FieldAnnotations.Default.class);
                    if (defAnn != null) {
                        scope = defAnn.scope();
                        name = defAnn.name();
                    }

                    // Also check @Var annotation
                    if (scope.isEmpty()) {
                        Var varAnn = f.getAnnotation(Var.class);
                        if (varAnn != null) {
                            scope = varAnn.defaultScope();
                            if (name.isEmpty()) name = varAnn.defaultName();
                        }
                    }

                    if (scope.isEmpty()) scope = "Custom";

                    Runtime.Variable<?> variable;
                    if (InVariable.class.isAssignableFrom(rawType)) {
                        variable = new InVariable<>(scope, name);
                    } else if (OutVariable.class.isAssignableFrom(rawType)) {
                        variable = new OutVariable<>(scope, name);
                    } else if (OptVariable.class.isAssignableFrom(rawType)) {
                        variable = new OptVariable<>(scope, name);
                    } else {
                        continue;
                    }

                    f.set(node, variable);
                }
            } else if (Credential.class.isAssignableFrom(f.getType()) && f.get(node) == null) {
                // Initialize null Credential fields
                f.set(node, new Credential("Custom", null, null, null));
            } else {
                // Initialize non-variable option fields from @FieldAnnotations.Default(value=...)
                FieldAnnotations.Default defAnn = f.getAnnotation(FieldAnnotations.Default.class);
                if (defAnn != null && !defAnn.value().isEmpty()) {
                    try {
                        Class<?> type = f.getType();
                        if (type == String.class && f.get(node) == null) {
                            f.set(node, defAnn.value());
                        } else if ((type == int.class || type == Integer.class) && ((int) f.get(node)) == 0) {
                            f.set(node, Integer.parseInt(defAnn.value()));
                        } else if ((type == float.class || type == Float.class) && ((float) f.get(node)) == 0.0f) {
                            f.set(node, Float.parseFloat(defAnn.value()));
                        } else if ((type == double.class || type == Double.class) && ((double) f.get(node)) == 0.0) {
                            f.set(node, Double.parseDouble(defAnn.value()));
                        } else if ((type == boolean.class || type == Boolean.class)) {
                            f.set(node, Boolean.parseBoolean(defAnn.value()));
                        }
                    } catch (Exception e) {
                        // Skip on parse error
                    }
                }
            }
        }
    }

    // --- Output collection ---

    private static Map<String, Object> collectOutput(Class<?> nodeClass, Node node, Context ctx) {
        Map<String, Object> output = new LinkedHashMap<>();

        for (Field f : nodeClass.getFields()) {
            java.lang.reflect.Type t = f.getGenericType();
            if (!(t instanceof ParameterizedType pT)) continue;
            if (!OutVariable.class.isAssignableFrom((Class<?>) pT.getRawType())) continue;

            try {
                f.setAccessible(true);
                OutVariable<?> variable = (OutVariable<?>) f.get(node);
                if (variable == null) continue;

                String name = variable.getNameString();
                if (name == null || name.isEmpty()) continue;

                Object val = ctx.get(name);
                if (val != null) {
                    output.put(name, val);
                }
            } catch (Exception e) {
                continue;
            }
        }

        if (output.isEmpty()) {
            output.put("status", "completed");
        }

        return output;
    }

    // --- Command listing ---

    static List<Map<String, Object>> gatherCommands() {
        List<Class<?>> classes = Runtime.RegisteredNodes();
        List<Map<String, Object>> commands = new ArrayList<>();
        if (classes == null) return commands;

        for (Class<?> c : classes) {
            // Find Tool field with @ToolInfo
            String toolName = null;
            String toolDescription = null;

            for (Field f : c.getFields()) {
                if (Tool.class.isAssignableFrom(f.getType())) {
                    Tool.ToolInfo info = f.getAnnotation(Tool.ToolInfo.class);
                    if (info != null && !info.name().isEmpty()) {
                        toolName = info.name();
                        toolDescription = info.description();
                        break;
                    }
                }
            }

            if (toolName == null) continue;

            Map<String, Object> cmd = new LinkedHashMap<>();
            cmd.put("name", toolName);
            cmd.put("description", toolDescription != null ? toolDescription : "");
            cmd.put("node_id", Spec.GetNamespace(c));

            List<Map<String, Object>> params = new ArrayList<>();
            List<Map<String, Object>> outputs = new ArrayList<>();

            // Instantiate temp node to read variable names
            try {
                Node tempNode = (Node) c.getDeclaredConstructor().newInstance();
                initializeNodeFields(c, tempNode);

                for (Field f : c.getFields()) {
                    // Skip Tool and Credential fields
                    if (Tool.class.isAssignableFrom(f.getType())) continue;
                    if (Credential.class.isAssignableFrom(f.getType())) continue;

                    java.lang.reflect.Type t = f.getGenericType();

                    if (t instanceof ParameterizedType pT) {
                        Class<?> rawType = (Class<?>) pT.getRawType();
                        if (!Runtime.Variable.class.isAssignableFrom(rawType)) continue;

                        f.setAccessible(true);
                        Runtime.Variable<?> variable = (Runtime.Variable<?>) f.get(tempNode);
                        if (variable == null) continue;

                        String specName = variable.getNameString();
                        String varType = getVariableType(f);

                        // Derive flag name
                        String flagName = "";
                        if (specName != null && !specName.isEmpty()) {
                            flagName = camelToKebab(specName);
                        } else {
                            String title = Spec.GetTitle(f);
                            if (title != null && !title.isEmpty()) {
                                flagName = camelToKebab(title);
                            }
                        }
                        if (flagName.isEmpty()) continue;

                        String description = Spec.GetDescription(f);

                        if (InVariable.class.isAssignableFrom(rawType)) {
                            Map<String, Object> param = new LinkedHashMap<>();
                            param.put("name", flagName);
                            param.put("type", varType);
                            param.put("required", true);
                            if (description != null && !description.isEmpty()) {
                                param.put("description", description);
                            }
                            params.add(param);
                        } else if (OptVariable.class.isAssignableFrom(rawType)) {
                            Map<String, Object> param = new LinkedHashMap<>();
                            param.put("name", flagName);
                            param.put("type", varType);
                            param.put("required", false);
                            if (description != null && !description.isEmpty()) {
                                param.put("description", description);
                            }
                            params.add(param);
                        } else if (OutVariable.class.isAssignableFrom(rawType)) {
                            if (specName != null && !specName.isEmpty()) {
                                Map<String, Object> out = new LinkedHashMap<>();
                                out.put("name", specName);
                                out.put("type", varType);
                                outputs.add(out);
                            }
                        }
                    } else {
                        // Check for enum option fields (non-variable)
                        String enumValues = getEnumValues(f);
                        if (enumValues != null && !enumValues.isEmpty()) {
                            String title = Spec.GetTitle(f);
                            if (title == null || title.isEmpty()) {
                                title = f.getName();
                            }
                            String flagName = camelToKebab(title);
                            String description = Spec.GetDescription(f);

                            Map<String, Object> param = new LinkedHashMap<>();
                            param.put("name", flagName);
                            param.put("type", "string");
                            param.put("required", false);
                            if (description != null && !description.isEmpty()) {
                                param.put("description", description);
                            }
                            // Parse enum choices
                            List<String> choices = parseEnumChoices(enumValues);
                            if (!choices.isEmpty()) {
                                param.put("choices", choices);
                            }
                            params.add(param);
                        }
                    }
                }
            } catch (Exception e) {
                // Skip this class if instantiation fails
                continue;
            }

            cmd.put("parameters", params);
            cmd.put("outputs", outputs);
            commands.add(cmd);
        }

        return commands;
    }

    private static void listCommands(String[] args) {
        // Check for --output json
        boolean outputJSON = false;
        for (int i = 1; i < args.length; i++) {
            if ("--output".equals(args[i]) && i + 1 < args.length && "json".equals(args[i + 1])) {
                outputJSON = true;
                break;
            }
            if ("--output=json".equals(args[i])) {
                outputJSON = true;
                break;
            }
        }

        List<Map<String, Object>> commands = gatherCommands();

        if (outputJSON) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
                String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(commands);
                System.out.println(json);
            } catch (Exception e) {
                cliError("JSON serialization error: %s", e.getMessage());
            }
            return;
        }

        // Human-readable output to stderr
        String name = "";
        String version = "";
        try {
            JSONObject config = App.ReadConfigFile();
            name = config.get("name").toString();
            version = config.get("version").toString();
        } catch (Exception e) {
            // Use defaults
        }

        if (!name.isEmpty()) {
            System.err.printf("%s v%s%n%n", name, version);
        }
        System.err.println("Available commands:\n");

        for (Map<String, Object> cmd : commands) {
            System.err.printf("  %-24s %s%n", cmd.get("name"), cmd.get("description"));
        }

        System.err.println("\nUse --list-commands <command> for details on a specific command.");
        System.err.println("Use --list-commands --output json for machine-readable output.");
    }

    @SuppressWarnings("unchecked")
    private static void listCommandDetail(String cmdName) {
        List<Map<String, Object>> commands = gatherCommands();

        Map<String, Object> cmd = null;
        for (Map<String, Object> c : commands) {
            if (cmdName.equals(c.get("name"))) {
                cmd = c;
                break;
            }
        }

        if (cmd == null) {
            System.err.printf("Unknown command: %s%n", cmdName);
            System.err.println("Run --list-commands to see available commands.");
            System.exit(1);
            return;
        }

        String binName = "";
        try {
            JSONObject config = App.ReadConfigFile();
            binName = config.get("name").toString().toLowerCase();
        } catch (Exception e) {
            binName = "package";
        }

        System.err.printf("Usage: %s %s [flags]%n%n", binName, cmd.get("name"));
        System.err.printf("%s%n", cmd.get("description"));

        List<Map<String, Object>> params = (List<Map<String, Object>>) cmd.get("parameters");
        if (params != null && !params.isEmpty()) {
            System.err.println("\nFlags:");
            for (Map<String, Object> p : params) {
                String flag = String.format("--%s %s", p.get("name"), p.get("type"));
                String reqTag = Boolean.TRUE.equals(p.get("required")) ? " (required)" : "";
                String defTag = "";
                if (p.get("default") != null && !p.get("default").toString().isEmpty()) {
                    defTag = String.format(" (default: %s)", p.get("default"));
                }
                String choiceTag = "";
                Object choices = p.get("choices");
                if (choices instanceof List<?> choiceList && !choiceList.isEmpty()) {
                    choiceTag = String.format(" [%s]", String.join("|",
                            choiceList.stream().map(Object::toString).toList()));
                }
                String desc = p.get("description") != null ? p.get("description").toString() : "";
                System.err.printf("  %-32s %s%s%s%s%n", flag, desc, reqTag, defTag, choiceTag);
            }
        }

        List<Map<String, Object>> outputs = (List<Map<String, Object>>) cmd.get("outputs");
        if (outputs != null && !outputs.isEmpty()) {
            System.err.println("\nOutput:");
            for (Map<String, Object> o : outputs) {
                System.err.printf("  %-32s %s%n", o.get("name"), o.get("type"));
            }
        }
    }

    private static void printHelp() {
        String name = "";
        String version = "";
        try {
            JSONObject config = App.ReadConfigFile();
            name = config.get("name").toString();
            version = config.get("version").toString();
        } catch (Exception e) {
            // Use defaults
        }

        if (!name.isEmpty()) {
            System.err.printf("%s v%s%n%n", name, version);
            System.err.printf("Usage: %s <command> [flags]%n%n", name.toLowerCase());
        } else {
            System.err.println("Usage: <binary> <command> [flags]\n");
        }

        System.err.println("Commands:");
        List<Map<String, Object>> commands = gatherCommands();
        for (Map<String, Object> cmd : commands) {
            System.err.printf("  %-24s %s%n", cmd.get("name"), cmd.get("description"));
        }

        System.err.println("\nGlobal Flags:");
        System.err.printf("  %-32s %s%n", "--output json", "Output in JSON format");
        System.err.printf("  %-32s %s%n", "--vault-id ID", "Robomotion vault ID for credentials");
        System.err.printf("  %-32s %s%n", "--item-id ID", "Robomotion vault item ID for credentials");
        System.err.printf("  %-32s %s%n", "--vault NAME", "Vault name (resolved to ID via API)");
        System.err.printf("  %-32s %s%n", "--item NAME", "Item name (resolved to ID via API)");

        System.err.println("\nEnvironment:");
        System.err.printf("  %-32s %s%n", "ROBOMOTION_API_TOKEN",
                "API bearer token (from runner, skips robomotion login)");
        System.err.printf("  %-32s %s%n", "ROBOMOTION_ROBOT_ID",
                "Robot UUID (for private key lookup in keys dir)");
        System.err.printf("  %-32s %s%n", "ROBOMOTION_API_URL",
                "API base URL (default: https://api.robomotion.io)");

        System.err.println("\nUse --list-commands <command> for details on a specific command.");
        System.err.println("Use --help or -h to show this help.");
    }

    // --- Utility methods ---

    /**
     * Tries to parse a string as JSON (array or object). Returns the parsed
     * value if successful, otherwise returns the original string.
     */
    static Object tryParseJSON(String value) {
        if (value != null && (value.startsWith("[") || value.startsWith("{"))) {
            try {
                return new ObjectMapper().readValue(value, Object.class);
            } catch (Exception e) {
                // Not valid JSON
            }
        }
        return value;
    }

    static String camelToKebab(String s) {
        if (s == null || s.isEmpty()) return s;
        // Handle spaces (Title Case)
        s = s.replace(" ", "-");
        // Handle camelCase boundaries
        s = s.replaceAll("([a-z])([A-Z])", "$1-$2");
        s = s.replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2");
        return s.toLowerCase();
    }

    static String kebabToCamel(String s) {
        if (s == null || s.isEmpty()) return s;
        String[] parts = s.split("-");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(Character.toUpperCase(parts[i].charAt(0)));
                sb.append(parts[i].substring(1));
            }
        }
        return sb.toString();
    }

    static String getVariableType(Field f) {
        java.lang.reflect.Type t = f.getGenericType();
        if (t instanceof ParameterizedType pT) {
            java.lang.reflect.Type[] typeArgs = pT.getActualTypeArguments();
            if (typeArgs.length > 0) {
                String typeName = typeArgs[0].getTypeName();
                if (typeName.contains("String")) return "string";
                if (typeName.contains("Integer") || typeName.contains("Long")) return "number";
                if (typeName.contains("Double") || typeName.contains("Float")) return "number";
                if (typeName.contains("Boolean")) return "boolean";
                if (typeName.contains("Map")) return "object";
                if (typeName.contains("List")) return "array";
            }
        }
        return "string";
    }

    private static String getEnumValues(Field f) {
        // Check combined annotation first
        Var var = f.getAnnotation(Var.class);
        if (var != null && !var.enumValues().isEmpty()) {
            return var.enumValues();
        }
        // Fall back to legacy annotation
        FieldAnnotations.Enum enumAnn = f.getAnnotation(FieldAnnotations.Enum.class);
        if (enumAnn != null && !enumAnn.enumeration().isEmpty()) {
            return enumAnn.enumeration();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseEnumChoices(String enumJSON) {
        try {
            return new ObjectMapper().readValue(enumJSON, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static void setFieldValue(Field field, Object target, String value) throws Exception {
        field.setAccessible(true);
        Class<?> type = field.getType();
        if (type == String.class) {
            field.set(target, value);
        } else if (type == boolean.class || type == Boolean.class) {
            field.set(target, Boolean.parseBoolean(value));
        } else if (type == int.class || type == Integer.class) {
            field.set(target, Integer.parseInt(value));
        } else if (type == long.class || type == Long.class) {
            field.set(target, Long.parseLong(value));
        } else if (type == double.class || type == Double.class) {
            field.set(target, Double.parseDouble(value));
        } else if (type == float.class || type == Float.class) {
            field.set(target, Float.parseFloat(value));
        } else {
            field.set(target, value);
        }
    }

    static void cliError(String format, Object... args) {
        String msg = String.format(format, args);
        try {
            ObjectMapper mapper = new ObjectMapper();
            String errJSON = mapper.writeValueAsString(Map.of("error", msg));
            System.err.println(errJSON);
        } catch (Exception e) {
            System.err.println("{\"error\":\"" + msg.replace("\"", "\\\"") + "\"}");
        }
        System.exit(1);
    }

    // --- Inner classes ---

    static class CommandEntry {
        final Class<?> nodeClass;
        final String nodeID;
        final String toolName;
        final String description;

        CommandEntry(Class<?> nodeClass, String nodeID, String toolName, String description) {
            this.nodeClass = nodeClass;
            this.nodeID = nodeID;
            this.toolName = toolName;
            this.description = description;
        }
    }

    static class FlagEntry {
        final Field field;
        final String specName;
        final String scope;
        final boolean isOption;

        FlagEntry(Field field, String specName, String scope, boolean isOption) {
            this.field = field;
            this.specName = specName;
            this.scope = scope;
            this.isOption = isOption;
        }
    }
}
