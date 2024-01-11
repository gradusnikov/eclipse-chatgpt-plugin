package com.github.gradusnikov.eclipse.assistai.subscribers;

import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscription;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gradusnikov.eclipse.assistai.jobs.ExecuteFunctionCallJob;
import com.github.gradusnikov.eclipse.assistai.model.FunctionCall;
import com.github.gradusnikov.eclipse.assistai.model.Incoming;

@Creatable
public class FunctionCallSubscriber implements Flow.Subscriber<Incoming>
{
    @Inject
    private ILog logger;
    @Inject
    private Provider<ExecuteFunctionCallJob> executeFunctionCallJobProvider;
    
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
        json += "}";

        if ( !json.startsWith( "\"function_call\"" ) )
        {
            subscription.request(1);
            return;
        }
        try
        {
            // 1. append assistant request to call a function to the conversation
            ObjectMapper mapper = new ObjectMapper();
            // -- convert JSON to FuncationCall object
            var functionCall = mapper.readValue( json.replace( "\"function_call\" : ","" ), FunctionCall.class );
            
            ExecuteFunctionCallJob job = executeFunctionCallJobProvider.get();
            job.setFunctionCall( functionCall );
            job.schedule();
        }
        catch ( Exception e )
        {
            logger.error( e.getMessage(), e );
        }
        subscription.request(1);
    }


    

}
