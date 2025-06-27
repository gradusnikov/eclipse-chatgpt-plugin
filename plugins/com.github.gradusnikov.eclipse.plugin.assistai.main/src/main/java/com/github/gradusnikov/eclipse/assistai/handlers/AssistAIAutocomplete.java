package com.github.gradusnikov.eclipse.assistai.handlers;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.github.gradusnikov.eclipse.assistai.network.clients.LanguageModelClientConfiguration;

import codingagent.factory.ModelFactories;
import codingagent.factory.ModelFactoryAdapter;
import codingagent.models.AutoCompletingQuery;
import codingagent.models.ModelApiDescriptor;
import codingagent.models.Prompts;
import dev.langchain4j.model.chat.ChatLanguageModel;
import jakarta.inject.Inject;
import jakarta.inject.Named;

public class AssistAIAutocomplete extends AssistAIHandlerTemplate
{
    
	@Inject
    private LanguageModelClientConfiguration configuration;
	
    public AssistAIAutocomplete()
    {
        super( Prompts.AUTOCOMPLETE );
    }
    
    @Execute
    public void execute(@Named(IServiceConstants.ACTIVE_SHELL) Shell s)
    {
        var message = chatMessageFactory.createUserChatMessage( type );
        
        AutoCompletingQuery query = new AutoCompletingQuery();
        query.setQuery(message.getContent());
        //
        
        // Get the active shell
        Shell shell = Display.getDefault().getActiveShell();

        // Display the information dialog
        MessageDialog.openInformation(
            shell,
            "Information", // Title of the dialog
            query.getQuery()
        );
        
        ModelApiDescriptor modelApiDescriptor = configuration.getSelectedModel().get();
        ModelFactoryAdapter modelFactory = ModelFactories.valueOf( modelApiDescriptor.apiType()).getFactory();
        ChatLanguageModel model = modelFactory.buildChat(modelApiDescriptor);
        String suggested = query.suggest(model);
        
        MessageDialog.openInformation(
                shell,
                "Information", // Title of the dialog
                suggested
            );
        
    }
    
}
