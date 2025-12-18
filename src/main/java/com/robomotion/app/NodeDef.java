package com.robomotion.app;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Combined annotation for defining a Robomotion node.
 * This is the recommended way to define nodes in modern Java.
 *
 * <pre>{@code
 * @NodeDef(name = "com.example.MyNode", title = "My Node", color = "#4CAF50", inputs = 1, outputs = 1)
 * public class MyNode extends Node {
 *     // ...
 * }
 * }</pre>
 *
 * For backward compatibility, you can still use the individual annotations:
 * @NodeAnnotations.Name, @NodeAnnotations.Title, etc.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface NodeDef {
    /**
     * Unique identifier for the node (e.g., "com.company.package.NodeName")
     */
    String name();

    /**
     * Display title shown in the designer
     */
    String title();

    /**
     * Node color in hex format (e.g., "#4CAF50")
     */
    String color() default "#666666";

    /**
     * Icon name or path
     */
    String icon() default "";

    /**
     * Number of input ports
     */
    int inputs() default 1;

    /**
     * Number of output ports
     */
    int outputs() default 1;

    /**
     * Custom editor component name
     */
    String editor() default "";
}
