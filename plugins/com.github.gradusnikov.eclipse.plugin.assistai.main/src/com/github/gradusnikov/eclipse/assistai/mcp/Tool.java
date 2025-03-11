package com.github.gradusnikov.eclipse.assistai.mcp;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Retention( RUNTIME )
@Target( METHOD )
public @interface Tool
{
    public String name() default "";
    public String description();
    public String type() default "object";
}
