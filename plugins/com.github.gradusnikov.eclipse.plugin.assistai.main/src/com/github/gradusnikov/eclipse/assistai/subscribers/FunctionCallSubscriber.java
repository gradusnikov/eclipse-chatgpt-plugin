package com.github.gradusnikov.eclipse.assistai.subscribers;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscription;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gradusnikov.eclipse.assistai.jobs.SendConversationJob;
import com.github.gradusnikov.eclipse.assistai.model.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.model.Conversation;
import com.github.gradusnikov.eclipse.assistai.model.FunctionCall;
import com.github.gradusnikov.eclipse.assistai.services.FunctionExecutor;
import com.github.gradusnikov.eclipse.assistai.services.FunctionExecutorProvider;

@Creatable
public class FunctionCallSubscriber implements Flow.Subscriber<String>
{
    @Inject
    private ILog logger;
    @Inject
    private FunctionExecutorProvider functionExecutorProvider;
    @Inject
    private Conversation conversation;
    @Inject
    private Provider<SendConversationJob> sendConversationJobProvider;
    
    
    private Subscription subscription;
    private final StringBuffer jsonBuffer;
    
    public FunctionCallSubscriber()
    {
        jsonBuffer = new StringBuffer();
    }
    
    @Override
    public void onSubscribe( Subscription subscription )
    {
        this.subscription = subscription;
        jsonBuffer.setLength( 0 );
        subscription.request(1);

    }

    @Override
    public void onNext( String item )
    {
        jsonBuffer.append( item );
        subscription.request(1);
    }

    @Override
    public void onError( Throwable throwable )
    {
        jsonBuffer.setLength( 0 );
    }

    @Override
    public void onComplete()
    {
        String json = jsonBuffer.toString();
        json += "}";

        if ( !json.startsWith( "\"function_call\"" ) )
        {
            subscription.request(1);
            return;
        }
        
        ObjectMapper mapper = new ObjectMapper();
        try
        {
            var functionCall = mapper.readValue( json.replace( "\"function_call\" : ","" ), FunctionCall.class );
            ChatMessage message =  new ChatMessage( UUID.randomUUID().toString(), "assistant");
            message.setFunctionCall( functionCall );
            conversation.add( message );

            logger.info( "Executing function call: " + functionCall  );
            FunctionExecutor functionExecutor = functionExecutorProvider.get();
            CompletableFuture<Object> future = functionExecutor.call( functionCall.name(), functionCall.arguments() );
            future.thenAccept( result -> {
                logger.info( "Finished function call " + functionCall.name() );
                ChatMessage resultMessage = new ChatMessage( UUID.randomUUID().toString(), functionCall.name(), "function" );
                String resultJson;
                try
                {
                    resultJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString( result );
                    resultMessage.setContent( resultJson );
                    conversation.add( resultMessage );
                    SendConversationJob job = sendConversationJobProvider.get();
                    job.schedule();
                }
                catch ( JsonProcessingException e )
                {
                    logger.error( e.getMessage(), e );
                }
            } );
            
        }
        catch ( Exception e )
        {
            logger.error( e.getMessage(), e );
        }
        subscription.request(1);
    }
    

}
