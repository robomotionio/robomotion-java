package com.robomotion.app;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import com.robomotion.app.Runtime.Credential;
import com.robomotion.app.Runtime.InVariable;
import com.robomotion.app.Runtime.OptVariable;
import com.robomotion.app.Runtime.OutVariable;

/**
 * Generates SKILL.md documentation from introspecting registered nodes.
 * Only nodes with an embedded Tool field are included.
 */
public class SkillMD {

    public static void generate(String name, String version, String description) {
        List<Class<?>> classes = Runtime.RegisteredNodes();
        if (classes == null || classes.isEmpty()) {
            System.err.printf("No nodes registered in package %s%n", name);
            System.exit(1);
            return;
        }

        String binaryName = inferBinaryName(name);

        List<SkillCommand> commands = new ArrayList<>();
        boolean hasCredentials = false;
        boolean hasConnect = false;
        boolean hasDisconnect = false;

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

            // Detect Connect/Disconnect nodes
            String nodeID = Spec.GetNamespace(c);
            if (nodeID.contains(".Connect") || toolName.equalsIgnoreCase("connect")) {
                hasConnect = true;
            }
            if (nodeID.contains(".Disconnect") || toolName.equalsIgnoreCase("disconnect")) {
                hasDisconnect = true;
            }

            SkillCommand cmd = new SkillCommand();
            cmd.name = toolName;
            cmd.description = toolDescription != null ? toolDescription : "";

            // Instantiate temp node to read variable names
            try {
                Node tempNode = (Node) c.getDeclaredConstructor().newInstance();

                for (Field f : c.getFields()) {
                    if (Tool.class.isAssignableFrom(f.getType())) continue;

                    if (Credential.class.isAssignableFrom(f.getType())) {
                        hasCredentials = true;
                        continue;
                    }

                    java.lang.reflect.Type t = f.getGenericType();
                    if (!(t instanceof ParameterizedType pT)) continue;

                    Class<?> rawType = (Class<?>) pT.getRawType();
                    if (!Runtime.Variable.class.isAssignableFrom(rawType)) continue;

                    f.setAccessible(true);
                    Runtime.Variable<?> variable = (Runtime.Variable<?>) f.get(tempNode);
                    if (variable == null) continue;

                    String specName = variable.getNameString();
                    if (specName == null || specName.isEmpty()) continue;

                    String varType = CLI.getVariableType(f);
                    String flagName = CLI.camelToKebab(specName);
                    String desc = Spec.GetDescription(f);
                    String title = Spec.GetTitle(f);
                    if ((desc == null || desc.isEmpty()) && title != null && !title.isEmpty()) {
                        desc = title;
                    }

                    if (InVariable.class.isAssignableFrom(rawType)) {
                        cmd.params.add(new SkillParam(flagName, varType, true, desc));
                    } else if (OptVariable.class.isAssignableFrom(rawType)) {
                        cmd.params.add(new SkillParam(flagName, varType, false, desc));
                    } else if (OutVariable.class.isAssignableFrom(rawType)) {
                        cmd.outputs.add(new SkillOutput(specName, varType));
                    }
                }
            } catch (Exception e) {
                continue;
            }

            commands.add(cmd);
        }

        if (commands.isEmpty()) {
            System.err.printf("No tool-enabled nodes found in package %s%n", name);
            System.exit(1);
            return;
        }

        // Generate SKILL.md
        StringBuilder b = new StringBuilder();

        // YAML frontmatter
        b.append("---\n");
        String shortName = name.startsWith("Robomotion.") ? name.substring(11).toLowerCase() : name.toLowerCase();
        b.append(String.format("name: %s%n", shortName));
        b.append(String.format("description: %s%n", description));
        if (hasCredentials) {
            b.append("compatibility: Requires 'robomotion login' for vault credentials\n");
        }
        b.append("---\n\n");

        // Title
        b.append(String.format("# %s%n%n", name));
        b.append(String.format("Binary: `%s`%n", binaryName));
        b.append(String.format("Also available via: `robomotion %s <command> [flags]`%n%n", shortName));

        // Commands section
        b.append("## Commands\n\n");

        for (SkillCommand cmd : commands) {
            b.append(String.format("### %s%n", cmd.name));
            if (!cmd.description.isEmpty()) {
                b.append(String.format("%s%n", cmd.description));
            }
            b.append("\n");

            // Usage line
            b.append(String.format("    %s %s", binaryName, cmd.name));
            for (SkillParam p : cmd.params) {
                if (p.required) {
                    b.append(String.format(" --%s=<%s>", p.flag, p.varType));
                }
            }
            for (SkillParam p : cmd.params) {
                if (!p.required) {
                    b.append(String.format(" [--%s=<%s>]", p.flag, p.varType));
                }
            }
            b.append("\n\n");

            // Parameters
            if (!cmd.params.isEmpty()) {
                b.append("**Parameters:**\n");
                for (SkillParam p : cmd.params) {
                    String req = p.required ? "required" : "optional";
                    String desc = "";
                    if (p.description != null && !p.description.isEmpty()) {
                        desc = ": " + p.description;
                    }
                    b.append(String.format("- `--%s` (%s, %s)%s%n", p.flag, p.varType, req, desc));
                }
                b.append("\n");
            }

            // Output
            if (!cmd.outputs.isEmpty()) {
                StringJoiner fields = new StringJoiner(", ");
                for (SkillOutput o : cmd.outputs) {
                    fields.add(String.format("\"%s\": \"<%s>\"", o.name, o.varType));
                }
                b.append("**Output:**\n```json\n");
                b.append(String.format("{%s}%n", fields));
                b.append("```\n\n");
            }
        }

        // Authentication section
        if (hasCredentials) {
            b.append("## Authentication\n\n");
            b.append("By name (human-friendly):\n\n");
            b.append(String.format("    %s %s --vault=\"My Vault\" --item=\"My Item\" ...%n%n",
                    binaryName, commands.get(0).name));
            b.append("By ID (machine-friendly):\n\n");
            b.append(String.format("    %s %s --vault-id=<id> --item-id=<id> ...%n%n",
                    binaryName, commands.get(0).name));
            b.append("Alternatively, set `ROBOMOTION_API_TOKEN` and `ROBOMOTION_ROBOT_ID` environment variables for token-based auth.\n");
        }

        // Sessions section
        if (hasConnect && hasDisconnect) {
            b.append("\n## Sessions\n\n");
            b.append("This package supports persistent connections. Start a session to reuse connections across calls:\n\n");
            b.append(String.format("    %s connect --vault=\"My Vault\" --item=\"My Item\" --session%n",
                    binaryName));
            b.append(String.format("    %s %s --session-id=<id> --conn-id=<from-connect> ...%n",
                    binaryName, commands.get(0).name));
            b.append(String.format("    %s --session-close=<id>%n", binaryName));
        }

        System.out.print(b.toString());
    }

    private static String inferBinaryName(String pkgName) {
        String[] parts = pkgName.split("\\.");
        if (parts.length > 1) {
            return "robomotion-" + parts[parts.length - 1].toLowerCase();
        }
        return "robomotion-" + pkgName.toLowerCase();
    }

    // --- Inner data classes ---

    private static class SkillCommand {
        String name;
        String description;
        List<SkillParam> params = new ArrayList<>();
        List<SkillOutput> outputs = new ArrayList<>();
    }

    private static class SkillParam {
        String flag;
        String varType;
        boolean required;
        String description;

        SkillParam(String flag, String varType, boolean required, String description) {
            this.flag = flag;
            this.varType = varType;
            this.required = required;
            this.description = description;
        }
    }

    private static class SkillOutput {
        String name;
        String varType;

        SkillOutput(String name, String varType) {
            this.name = name;
            this.varType = varType;
        }
    }
}
