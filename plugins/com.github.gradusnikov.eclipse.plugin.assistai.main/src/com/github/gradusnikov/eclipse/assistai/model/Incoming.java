package com.github.gradusnikov.eclipse.assistai.model;

public record Incoming( Type type, Object payload )
{
    public enum Type
    {
        CONTENT,
        FUNCTION_CALL
    }
}
