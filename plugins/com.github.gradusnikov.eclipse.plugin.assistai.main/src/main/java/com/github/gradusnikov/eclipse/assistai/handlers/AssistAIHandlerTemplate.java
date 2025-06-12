package com.github.gradusnikov.eclipse.assistai.handlers;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.swt.widgets.Shell;

import com.github.gradusnikov.eclipse.assistai.prompt.ChatMessageFactory;
import com.github.gradusnikov.eclipse.assistai.prompt.Prompts;
import com.github.gradusnikov.eclipse.assistai.view.ChatViewPresenter;

import jakarta.inject.Inject;
import jakarta.inject.Named;

public class AssistAIHandlerTemplate 
{
    @Inject
    protected ChatMessageFactory chatMessageFactory;
    @Inject
    protected ChatViewPresenter viewPresenter;
    
    protected final Prompts type;
    
    public AssistAIHandlerTemplate( Prompts type )
    {
        this.type = type;
    }
    
    @Execute
    public void execute(@Named(IServiceConstants.ACTIVE_SHELL) Shell s)
    {
        var message = chatMessageFactory.createUserChatMessage( type );
        viewPresenter.onSendPredefinedPrompt( type, message );
    }
    
}
