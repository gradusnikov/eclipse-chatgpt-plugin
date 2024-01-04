package com.github.gradusnikov.eclipse.assistai.jobs;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;
import javax.inject.Provider;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gradusnikov.eclipse.assistai.commands.FunctionExecutorProvider;
import com.github.gradusnikov.eclipse.assistai.model.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.model.Conversation;
import com.github.gradusnikov.eclipse.assistai.model.FunctionCall;

@Creatable
public class ExecuteFunctionCallJob extends Job
{
    @Inject
    private ILog logger;

    @Inject
    private Provider<SendConversationJob> sendConversationJobProvider;
    
    @Inject
    private FunctionExecutorProvider functionExecutorProvider;
    @Inject
    private Conversation conversation;

    private FunctionCall functionCall;

    
    public ExecuteFunctionCallJob()
    {
        super( AssistAIJobConstants.JOB_PREFIX + " execute function call");
        
    }
    
    @Override
    protected IStatus run( IProgressMonitor monitor )
    {
        Objects.requireNonNull( functionCall );
        
        try
        {
            // 1. execute the callback
            var future = executeFunctionCall( functionCall );
            return future.get();
        }
        catch ( InterruptedException | ExecutionException e )
        {
            return Status.error( e.getMessage(), e );
        }
    }

    public void setFunctionCall( FunctionCall functionCall )
    {
        this.functionCall = functionCall;
    }
    private CompletableFuture<IStatus> executeFunctionCall( FunctionCall functionCall )
    {
        logger.info( "Executing function call: " + functionCall  );
        var functionExecutor = functionExecutorProvider.get();
        return functionExecutor.call( functionCall.name(), functionCall.arguments() )
        .exceptionally( th -> {
            logger.error( th.getMessage(), th );
            return Status.error( th.getMessage(), th ); 
            })
        .thenApply( result -> {
            logger.info( "Finished function call " + functionCall.name() );
            ChatMessage resultMessage = new ChatMessage( UUID.randomUUID().toString(), functionCall.name(), "function" );
            String resultJson;
            try
            {
                // 2. append function_call request to the conversation
                ChatMessage message =  new ChatMessage( UUID.randomUUID().toString(), "assistant");
                message.setFunctionCall( functionCall );
                conversation.add( message );
                // 3. append function_call result to the conversation
                ObjectMapper mapper = new ObjectMapper();
                resultJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString( result );
                resultMessage.setContent( resultJson );
                conversation.add( resultMessage );
                // 4. and push the conversation to the LLM
                SendConversationJob job = sendConversationJobProvider.get();
                job.schedule();
                return Status.OK_STATUS;
            }
            catch ( JsonProcessingException e )
            {
                logger.error( e.getMessage(), e );
                return Status.error( e.getMessage(), e ); 
            }
        } );
    }
}
