package com.github.gradusnikov.eclipse.assistai.mcp.annotations;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Retention( RUNTIME )
@Target( PARAMETER )
public @interface ToolParam
{
    public String name() default "";
    public String description();
    public boolean required() default true;
    /**
     * The JSON Schema type advertised for this parameter: {@code string},
     * {@code integer} or {@code boolean}. The Java parameter stays a {@code String};
     * the framework converts a JSON number or boolean to its string form before the
     * call, and still accepts the string form ("120", "true") from a client that
     * sends one.
     */
    public String type() default "string";

}
