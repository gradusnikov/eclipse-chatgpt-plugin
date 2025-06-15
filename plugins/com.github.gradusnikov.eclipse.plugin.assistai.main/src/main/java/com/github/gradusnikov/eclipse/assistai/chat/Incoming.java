package com.github.gradusnikov.eclipse.assistai.chat;

public record Incoming( Type type, Object payload )
{
    public enum Type
    {
        CONTENT,
        FUNCTION_CALL
    }
}
