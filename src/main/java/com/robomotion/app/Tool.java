package com.robomotion.app;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Tool is a marker class that indicates a node can be used as an AI tool.
 * Use the @ToolInfo annotation on the Tool field to specify name and description.
 *
 * Usage:
 * Add a Tool field with @ToolInfo annotation to your node class:
 *
 * <pre>{@code
 * @NodeAnnotations.Name(name = "com.example.MyNode")
 * public class MyNode extends Node {
 *     @Tool.ToolInfo(name = "myTool", description = "Description of what this tool does")
 *     public Tool tool = new Tool();
 *
 *     @Override
 *     public void OnMessage(Context ctx) throws Exception {
 *         // Handle the message
 *     }
 * }
 * }</pre>
 */
public class Tool {

    /**
     * Annotation to specify tool name and description for AI tool support.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface ToolInfo {
        String name();
        String description();
    }

    private String name;
    private String description;

    public Tool() {
        this.name = "";
        this.description = "";
    }

    public Tool(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
