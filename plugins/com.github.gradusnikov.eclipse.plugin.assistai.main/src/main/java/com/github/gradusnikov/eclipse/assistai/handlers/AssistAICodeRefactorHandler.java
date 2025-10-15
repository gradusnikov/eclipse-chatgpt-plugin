package com.github.gradusnikov.eclipse.assistai.handlers;

import codingagent.models.Prompts;

public class AssistAICodeRefactorHandler extends AssistAIHandlerTemplate
{
    public AssistAICodeRefactorHandler()
    {
        super(Prompts.REFACTOR);
    }
}
