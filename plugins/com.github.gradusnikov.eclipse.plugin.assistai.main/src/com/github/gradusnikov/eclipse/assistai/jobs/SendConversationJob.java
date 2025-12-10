package com.github.gradusnikov.eclipse.assistai.jobs;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.chat.Conversation;
import com.github.gradusnikov.eclipse.assistai.network.clients.ChatLanguageModelHttpClientProvider;

import jakarta.inject.Inject;

@Creatable
public class SendConversationJob extends Job
{
    @Inject
    private ILog logger;
    
    @Inject
    private ChatLanguageModelHttpClientProvider clientProvider;
    
    @Inject
    private Conversation conversation;
    
    public SendConversationJob()
    {
        super( AssistAIJobConstants.JOB_PREFIX + " is working." );
        setRule(new AssistAIJobRule());
    }
	

	@Override
	protected IStatus run(IProgressMonitor progressMonitor) 
	{
	    var aiClient = clientProvider.get();
	    aiClient.setCancelProvider(() -> progressMonitor.isCanceled()); 
	    
        // Get the runnable from the client
        Runnable task = aiClient.run(conversation);
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
