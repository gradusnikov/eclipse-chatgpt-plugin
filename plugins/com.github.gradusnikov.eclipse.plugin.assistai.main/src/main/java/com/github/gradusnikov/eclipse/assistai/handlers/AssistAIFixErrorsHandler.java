package com.github.gradusnikov.eclipse.assistai.handlers;

import codingagent.models.Prompts;

public class AssistAIFixErrorsHandler extends AssistAIHandlerTemplate
{
    public AssistAIFixErrorsHandler()
    { 
        super( Prompts.FIX_ERRORS );
    }
}
