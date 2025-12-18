# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is the Java SDK for Robomotion, an RPA (Robotic Process Automation) platform. The SDK allows developers to create custom nodes (automation building blocks) that can be used in Robomotion workflows.

**Version 2.0** - Modernized with Java 21, GraalVM native-image support, and feature parity with robomotion-go.

## Build Commands

```bash
# Compile the project
mvn compile

# Package into JAR
mvn package

# Generate spec file (outputs JSON node specification to stdout)
java -jar target/robomotion-2.0.0.jar -s

# Run in attach/debug mode
java -jar target/robomotion-2.0.0.jar -a

# Build native binary (requires GraalVM)
mvn package -Pnative
```

## Native Binary Support

The SDK supports GraalVM native-image compilation for creating standalone executables without requiring a JVM:

1. Install GraalVM 21+ and set `GRAALVM_HOME`
2. Run `mvn package -Pnative`
3. The native binary will be at `target/robomotion`

This replaces the old `jutil` approach which embedded a JRE into a Go wrapper.

## Architecture

### Plugin System (gRPC-based)
The SDK communicates with the Robomotion runtime via gRPC. Proto definitions are in `src/main/proto/`:
- `plugin.proto` - Defines the `Node` service and `RuntimeHelper` service
- `health.proto` - Health check service
- `runner.proto` - Runner service definitions

### Core Classes

- `App` - Entry point; call `App.Start(args)` from main()
- `Node` - Base class for custom nodes
- `Runtime` - Static utilities for variable access, vault operations, and runtime communication
- `Context` / `Message` - Message handling with type-safe getters

### Creating a Custom Node

**Modern Style (Recommended):**
```java
@NodeDef(name = "com.example.MyNode", title = "My Node", color = "#4CAF50", inputs = 1, outputs = 1)
public class MyNode extends Node {

    @Var(title = "Input", type = Var.VarType.INPUT, messageScope = true, aiScope = true)
    public Runtime.InVariable<String> input = new Runtime.InVariable<>("Message", "input");

    @Var(title = "Output", type = Var.VarType.OUTPUT, messageScope = true)
    public Runtime.OutVariable<String> output = new Runtime.OutVariable<>("Message", "output");

    @Override
    public void OnMessage(Context ctx) throws Exception {
        String value = input.Get(ctx);
        output.Set(ctx, "Processed: " + value);
    }
}
```

**Legacy Style (Still Supported):**
```java
@NodeAnnotations.Name(name = "com.example.MyNode")
@NodeAnnotations.Title(title = "My Node")
@NodeAnnotations.Color(color = "#4CAF50")
@NodeAnnotations.Inputs(inputs = 1)
@NodeAnnotations.Outputs(outputs = 1)
public class MyNode extends Node {
    @FieldAnnotations.MessageScope
    public Runtime.InVariable<String> input = new Runtime.InVariable<>("Message", "input");
    // ...
}
```

### AI Tool Support

```java
@NodeDef(name = "com.example.AINode", title = "AI Search", color = "#9C27B0")
public class AISearchNode extends Node {

    @Tool.ToolInfo(name = "search", description = "Search for data in the database")
    public Tool tool = new Tool();

    @Var(title = "Query", type = Var.VarType.INPUT, aiScope = true)
    public Runtime.InVariable<String> query = new Runtime.InVariable<>("Message", "query");

    @Override
    public void OnMessage(Context ctx) throws Exception {
        if (ToolResponse.isToolRequest(ctx)) {
            // Handle AI tool request
            String q = query.Get(ctx);
            ToolResponse.sendSuccess(ctx, Map.of("results", searchResults));
        }
    }
}
```

### Annotations Reference

**@NodeDef** (class-level, combined):
- `name` - Unique node identifier
- `title` - Display title
- `color` - Hex color (default: "#666666")
- `icon` - Icon name
- `inputs` - Input port count (default: 1)
- `outputs` - Output port count (default: 1)
- `editor` - Custom editor component

**@Var** (field-level, combined):
- `type` - `INPUT`, `OUTPUT`, or `OPTION`
- `title`, `description`, `format`
- `customScope`, `messageScope`, `jsScope`, `messageOnly`, `aiScope`
- `hidden`, `defaultValue`, `enumValues`, `enumNames`

**@Tool.ToolInfo** (for Tool fields):
- `name` - Tool name for AI agents
- `description` - What the tool does

### Runtime Helper Methods

- `EmitOutput()`, `EmitInput()`, `EmitError()`, `EmitDebug()` - Event emission
- `AppRequest()`, `AppRequestV2()`, `AppPublish()` - App communication
- `AppDownload()`, `AppUpload()` - File operations
- `GatewayRequest()`, `ProxyRequest()` - HTTP gateway/proxy
- `GetRobotInfo()`, `GetPortConnections()`, `GetInstanceAccess()`, `IsRunning()`

## Key Dependencies
- Java 21 LTS
- gRPC 1.60.1
- Protobuf 3.25.2
- Jackson 2.16.1
- GraalVM Native Image 0.10.0
