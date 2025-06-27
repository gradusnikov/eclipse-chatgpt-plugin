package com.github.gradusnikov.eclipse.assistai.autocomplete;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.ui.text.java.ContentAssistInvocationContext;
import org.eclipse.jdt.ui.text.java.IJavaCompletionProposalComputer;
import org.eclipse.jface.text.contentassist.CompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContextInformation;

import com.github.gradusnikov.eclipse.assistai.network.clients.LanguageModelClientConfiguration;
import com.github.gradusnikov.eclipse.assistai.prompt.ChatMessageFactory;

import codingagent.factory.ModelFactories;
import codingagent.factory.ModelFactoryAdapter;
import codingagent.models.AutoCompletingQuery;
import codingagent.models.ModelApiDescriptor;
import codingagent.models.Prompts;
import dev.langchain4j.model.chat.ChatLanguageModel;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;


@Singleton
public class CustomCompletionProposalComputer implements IJavaCompletionProposalComputer {
	
	public CustomCompletionProposalComputer( ) {
		System.out.println("CustomCompletionProposalComputer constructor");
	}
		
	
	
	

    
	@Override
    public void sessionStarted() {
		System.out.println("CustomCompletionProposalComputer sessionStarted");        
    }

    @Override
    public List<ICompletionProposal> computeCompletionProposals(ContentAssistInvocationContext context, IProgressMonitor monitor) {    	
        System.out.println("Autocompleting...");
        List<ICompletionProposal> proposals = new ArrayList<>();

        
        // Add your custom proposals here
        
        var message = ChatMessageFactory.INSTANCE.createUserChatMessage( Prompts.AUTOCOMPLETE );
        
        AutoCompletingQuery query = new AutoCompletingQuery();
        query.setQuery(message.getContent());
        //

        System.out.println(query.getQuery());

        ModelApiDescriptor modelApiDescriptor = LanguageModelClientConfiguration.INSTANCE.getSelectedModel().get();
        ModelFactoryAdapter modelFactory = ModelFactories.valueOf( modelApiDescriptor.apiType()).getFactory();
        ChatLanguageModel model = modelFactory.buildChat(modelApiDescriptor);
        String suggested = query.suggest(model);
        
        System.out.println(suggested);
        
        
        proposals.add(new CompletionProposal(suggested, context.getInvocationOffset(), 0, suggested.length()));

        return proposals;
    }

    @Override
    public List<IContextInformation> computeContextInformation(ContentAssistInvocationContext context, IProgressMonitor monitor) {
        // Provide context information if needed
        return null;
    }

    @Override
    public String getErrorMessage() {
    	System.out.println("ERROR Autocompleting...");
        return null; // Return an error message if something goes wrong
    }

    @Override
    public void sessionEnded() {
    	System.out.println("CustomCompletionProposalComputer sessionEnded");

    }

	
}