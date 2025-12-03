package com.github.gradusnikov.eclipse.assistai.network.subscribers;

import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscription;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gradusnikov.eclipse.assistai.chat.FunctionCall;
import com.github.gradusnikov.eclipse.assistai.chat.Incoming;
import com.github.gradusnikov.eclipse.assistai.jobs.ExecuteFunctionCallJob;

@Creatable
public class FunctionCallSubscriber implements Flow.Subscriber<Incoming>
{
    @Inject
    private ILog logger;
    @Inject
    private Provider<ExecuteFunctionCallJob> executeFunctionCallJobProvider;
    
    private Subscription subscription;
    private final StringBuffer jsonBuffer;
    ObjectMapper mapper = new ObjectMapper();
    
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
    public void onNext( Incoming item )
    {
        if ( Incoming.Type.FUNCTION_CALL == item.type() )
        {
            jsonBuffer.append( item.payload() );
        }
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

        if ( !json.startsWith( "\"function_call\"" ) )
        {
            subscription.request(1);
            return;
        }
        try
        {
            if ( json.trim().endsWith( ":" ) ) 
            {
                json += "{}";
            }
            json += "\n}";
            // 1. append assistant request to call a function to the conversation
            // -- convert JSON to FuncationCall object
            var functionCallJson = json.substring( Math.max(0, json.indexOf( "{" )), json.length() );
            
            logger.info( "Function call json:\n" + functionCallJson  );
            
            var functionCall = mapper.readValue( functionCallJson, FunctionCall.class );
            
            ExecuteFunctionCallJob job = executeFunctionCallJobProvider.get();
            job.setFunctionCall( functionCall );
            job.schedule();
            logger.info("Job scheduled: " + functionCall.id() );
        }
        catch ( Exception e )
        {
            logger.error( e.getMessage(), e );
        }
        subscription.request(1);
    }
}