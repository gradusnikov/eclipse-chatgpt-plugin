package com.github.gradusnikov.eclipse.assistai.jobs;

import java.util.concurrent.CompletableFuture;

import jakarta.inject.Inject;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.model.Conversation;
import com.github.gradusnikov.eclipse.assistai.services.OpenAIHttpClientProvider;

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
        super( AssistAIJobConstants.JOB_PREFIX + " ask ChatGPT for help");
        
    }
	

	@Override
	protected IStatus run(IProgressMonitor progressMonitor) 
	{
	    var openAIClient = clientProvider.get();
	    openAIClient.setCancelProvider(() -> progressMonitor.isCanceled()); 
	    
	    try 
	    {
	        // Get the runnable from the client
	        Runnable task = openAIClient.run(conversation);
	        
	        // Create a CompletableFuture that can be explicitly canceled
	        var future = CompletableFuture.runAsync(task)
	                .thenApply(v -> Status.OK_STATUS)
	                .exceptionally(e -> Status.error("Unable to run the task: " + e.getMessage(), e));
	        
	        // Check for cancellation while waiting for completion
	        try 
	        {
	            while (!future.isDone()) 
	            {
	                if (progressMonitor.isCanceled()) 
	                {
	                    future.cancel(true); // Attempt to cancel the future
	                    return Status.CANCEL_STATUS;
	                }
	                Thread.sleep(100); // Small delay to prevent CPU hogging
	            }
	            
	            return future.get(); // Get the result (OK_STATUS or error status)
	        } 
	        catch (InterruptedException e) 
	        {
	            Thread.currentThread().interrupt(); // Restore the interrupted status
	            return Status.CANCEL_STATUS;
	        }
	    } 
	    catch (Exception e) 
	    {
	        return Status.error(e.getMessage(), e);
	    }
	}


}
