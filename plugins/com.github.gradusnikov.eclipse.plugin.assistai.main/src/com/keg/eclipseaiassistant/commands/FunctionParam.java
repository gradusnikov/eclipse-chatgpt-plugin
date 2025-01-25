package com.keg.eclipseaiassistant.commands;

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
    public boolean required() default true;
    public String type() default "string";

}
