package com.github.gradusnikov.eclipse.assistai.handlers;

import com.github.gradusnikov.eclipse.assistai.prompt.Prompts;


public class AssistAiGenerateGitCommentHandler extends AssistAIHandlerTemplate 
{
    public AssistAiGenerateGitCommentHandler()
    {
        super( Prompts.GIT_COMMENT );
    }
}
