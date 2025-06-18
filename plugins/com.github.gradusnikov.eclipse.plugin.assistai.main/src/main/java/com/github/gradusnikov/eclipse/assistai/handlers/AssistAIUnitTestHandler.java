package com.github.gradusnikov.eclipse.assistai.handlers;

import codingagent.models.Prompts;

public class AssistAIUnitTestHandler extends AssistAIHandlerTemplate
{
    
    public AssistAIUnitTestHandler()
    {
        super( Prompts.TEST_CASE );
    }
}
