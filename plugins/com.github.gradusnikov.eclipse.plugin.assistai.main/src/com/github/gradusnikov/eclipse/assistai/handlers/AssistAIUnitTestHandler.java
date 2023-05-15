package com.github.gradusnikov.eclipse.assistai.handlers;

import com.github.gradusnikov.eclipse.assistai.prompt.JobFactory.JobType;

public class AssistAIUnitTestHandler extends AssistAIHandlerTemplate
{
    
    public AssistAIUnitTestHandler()
    {
        super( JobType.UNIT_TEST );
    }
}
