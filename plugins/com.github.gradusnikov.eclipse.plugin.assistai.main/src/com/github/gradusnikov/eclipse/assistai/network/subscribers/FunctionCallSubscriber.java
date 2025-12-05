package com.github.gradusnikov.eclipse.assistai.network.subscribers;

import java.util.Arrays;
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
            // Split by "function_call" to get individual calls
            String[] parts = json.split("\"function_call\"\\s*:\\s*");
            
            for (String part : parts)
            {
                if( part.isBlank() ) continue;
                
                // Find the start of the JSON object
                int startIdx = part.indexOf('{');
                if (startIdx == -1) continue;
                
                String functionCallJson = toValidFunctionCallJson( part, startIdx );
                
                logger.info( "Function call json:\n" + functionCallJson  );
                
                var functionCall = mapper.readValue( functionCallJson, FunctionCall.class );
                
                scheduleFunctionCall( functionCall );
                logger.info("Job scheduled: " + functionCall.id() );
            }
        }
        catch ( Exception e )
        {
            logger.error( e.getMessage(), e );
        }
        subscription.request(1);
    }

    private String toValidFunctionCallJson( String part, int startIdx )
    {
        // Extract everything from { onwards
        String functionCallJson = part.substring(startIdx);
        
        // Ensure the JSON object is properly closed
        int braceCount = 0;
        for (char c : functionCallJson.toCharArray())
        {
            if (c == '{') braceCount++;
            else if (c == '}') braceCount--;
        }
        
        // Add missing closing braces
        while (braceCount > 0)
        {
            functionCallJson += "\n}";
            braceCount--;
        }
        
        // Handle incomplete arguments: "arguments" : followed by } or whitespace then }
        functionCallJson = functionCallJson.replaceAll("\"arguments\"\\s*:\\s*([\\r\\n\\s]*)}", "\"arguments\" : {}$1}");
        return functionCallJson;
    }
    
    private void scheduleFunctionCall( FunctionCall functionCall )
    {
        ExecuteFunctionCallJob job = executeFunctionCallJobProvider.get();
        job.setFunctionCall( functionCall );
        job.schedule();
    }


}