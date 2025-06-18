package com.github.gradusnikov.eclipse.assistai.handlers;

import codingagent.models.Prompts;


public class AssistAiGenerateGitCommentHandler extends AssistAIHandlerTemplate 
{
    public AssistAiGenerateGitCommentHandler()
    {
        super( Prompts.GIT_COMMENT );
    }
}
