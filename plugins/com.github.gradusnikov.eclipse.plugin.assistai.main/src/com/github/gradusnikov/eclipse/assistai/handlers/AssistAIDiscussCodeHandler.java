package com.github.gradusnikov.eclipse.assistai.handlers;

import com.github.gradusnikov.eclipse.assistai.prompt.JobFactory.JobType;

public class AssistAIDiscussCodeHandler extends AssistAIHandlerTemplate
{
    public AssistAIDiscussCodeHandler()
    {
        super( JobType.DISCUSS_CODE );
    }
}
