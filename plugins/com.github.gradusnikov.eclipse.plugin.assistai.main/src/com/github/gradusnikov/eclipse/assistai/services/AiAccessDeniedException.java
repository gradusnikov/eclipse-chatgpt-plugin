package com.github.gradusnikov.eclipse.assistai.services;

/**
 * Thrown when an AI operation attempts to access a resource that is excluded
 * from AI processing by an .aiignore rule.
 */
public class AiAccessDeniedException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public AiAccessDeniedException(String message)
    {
        super(message);
    }
}
