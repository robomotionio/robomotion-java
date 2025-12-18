package com.robomotion.app;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Combined annotation for defining variable fields in Robomotion nodes.
 * This is the recommended way to annotate variables in modern Java.
 *
 * <pre>{@code
 * @NodeDef(name = "com.example.MyNode", title = "My Node")
 * public class MyNode extends Node {
 *     @Var(title = "Input Message", type = VarType.INPUT, messageScope = true, aiScope = true)
 *     public Runtime.InVariable<String> input = new Runtime.InVariable<>("Message", "input");
 *
 *     @Var(title = "Output Result", type = VarType.OUTPUT, messageScope = true)
 *     public Runtime.OutVariable<String> output = new Runtime.OutVariable<>("Message", "output");
 *
 *     @Var(title = "Timeout", type = VarType.OPTION, defaultValue = "30")
 *     public int timeout;
 * }
 * }</pre>
 *
 * For backward compatibility, you can still use the individual FieldAnnotations.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Var {

    /**
     * Variable type: INPUT, OUTPUT, or OPTION
     */
    VarType type() default VarType.INPUT;

    /**
     * Display title for the variable
     */
    String title() default "";

    /**
     * Description shown as tooltip or help text
     */
    String description() default "";

    /**
     * Format hint (e.g., "password", "textarea", "code")
     */
    String format() default "";

    /**
     * Default value as JSON string
     */
    String defaultValue() default "";

    /**
     * Default scope for variable
     */
    String defaultScope() default "";

    /**
     * Default name for variable
     */
    String defaultName() default "";

    /**
     * Enable custom scope selection
     */
    boolean customScope() default false;

    /**
     * Enable message scope selection
     */
    boolean messageScope() default false;

    /**
     * Enable JS scope selection
     */
    boolean jsScope() default false;

    /**
     * Restrict to message scope only
     */
    boolean messageOnly() default false;

    /**
     * Enable AI scope for LLM agent integration
     */
    boolean aiScope() default false;

    /**
     * Hide this field in the UI
     */
    boolean hidden() default false;

    /**
     * Credential category (for vault fields)
     */
    FieldAnnotations.ECategory category() default FieldAnnotations.ECategory.Null;

    /**
     * Enum values as JSON array (e.g., "[\"option1\", \"option2\"]")
     */
    String enumValues() default "";

    /**
     * Enum display names as JSON array
     */
    String enumNames() default "";

    /**
     * Array field names (pipe-separated)
     */
    String arrayFields() default "";

    /**
     * Variable types
     */
    enum VarType {
        INPUT,
        OUTPUT,
        OPTION
    }
}
