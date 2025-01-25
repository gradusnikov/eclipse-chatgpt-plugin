package com.keg.eclipseaiassistant.jobs;

import java.util.concurrent.CompletableFuture;

import jakarta.inject.Inject;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.keg.eclipseaiassistant.model.ChatMessage;
import com.keg.eclipseaiassistant.model.Conversation;
import com.keg.eclipseaiassistant.subscribers.OpenAIHttpClientProvider;

@Creatable
public class SendConversationJob extends Job
{
    @Inject
    private ILog logger;
    
    @Inject
    private OpenAIHttpClientProvider clientProvider;
    
    @Inject
    private Conversation conversation;
    
    public SendConversationJob()
    {
        super( AssistAIJobConstants.JOB_PREFIX + " ask assistant for help");
        
    }
    @Override
    protected IStatus run(IProgressMonitor progressMonitor) 
    {
        var openAIClient = clientProvider.get();
        openAIClient.setCancelProvider( () -> progressMonitor.isCanceled() ); 
        
        try 
        {
            var future = CompletableFuture.runAsync( openAIClient.run(conversation) )
                    .thenApply( v -> Status.OK_STATUS )
                    .exceptionally( e -> Status.error("Unable to run the task: " + e.getMessage(), e) );
            return future.get();
        } 
        catch ( Exception e ) 
        {
            return Status.error( e.getMessage(), e );
        }
    }
}
