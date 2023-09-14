package com.github.gradusnikov.eclipse.assistai.commands;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Retention( RUNTIME )
@Target( PARAMETER )
public @interface FunctionParam
{
    public String name() default "";
    public String description();
    public boolean required() default false;
    public String type() default "string";

}
