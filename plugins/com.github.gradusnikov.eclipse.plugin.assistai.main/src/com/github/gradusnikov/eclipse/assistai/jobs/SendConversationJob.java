package com.github.gradusnikov.eclipse.assistai.jobs;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.chat.Conversation;
import com.github.gradusnikov.eclipse.assistai.chat.ConversationContext;
import com.github.gradusnikov.eclipse.assistai.network.clients.ChatLanguageModelHttpClientProvider;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

@Creatable
public class SendConversationJob extends Job
{
    @Inject
    private ILog logger;
    
    @Inject
    private ChatLanguageModelHttpClientProvider clientProvider;
    
    @Inject
    private Conversation conversation;
    
    @Inject
    private Provider<SendConversationJob> selfProvider;
    
    public SendConversationJob()
    {
        super( AssistAIJobConstants.JOB_PREFIX + " is working." );
        setRule(new AssistAIJobRule());
    }
	

	@Override
	protected IStatus run(IProgressMonitor progressMonitor) 
	{
	    // Create a conversation context for the ChatView
	    ConversationContext context = ConversationContext.builder()
	            .contextId( "chat-view" )
	            .conversation( conversation )
	            // No tool restrictions for ChatView - all tools allowed
	            .allowedTools( null )
	            .build();
	    
	    // Create continuation callback that schedules another job
	    Runnable onContinue = () -> {
	        // Schedule another job to continue the conversation
	        selfProvider.get().schedule();
	    };
	    
	    var aiClient = clientProvider.get( context, onContinue );
	    aiClient.setCancelProvider(() -> progressMonitor.isCanceled()); 
	    
        // Get the runnable from the client
        Runnable task = aiClient.run( context.getConversation() );
        try
        {
        	task.run();
        }
        catch ( Exception e )
        {
        	logger.error(e.getMessage(), e);
        	return Status.error( e.getMessage(), e);
        }
        if ( progressMonitor.isCanceled() )
        {
        	return Status.CANCEL_STATUS;
        }
        return Status.OK_STATUS;
	}

}
