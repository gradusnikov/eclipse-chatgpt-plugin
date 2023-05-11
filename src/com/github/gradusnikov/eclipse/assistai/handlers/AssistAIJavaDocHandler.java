package com.github.gradusnikov.eclipse.assistai.handlers;

import com.github.gradusnikov.eclipse.assistai.prompt.JobFactory.JobType;

public class AssistAIJavaDocHandler extends AssistAIHandlerTemplate
{
    public AssistAIJavaDocHandler()
    {
        super( JobType.DOCUMENT );
    }
}
