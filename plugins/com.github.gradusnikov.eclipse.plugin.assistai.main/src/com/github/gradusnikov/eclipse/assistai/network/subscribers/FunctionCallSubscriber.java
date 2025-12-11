package com.github.gradusnikov.eclipse.assistai.network.subscribers;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscription;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gradusnikov.eclipse.assistai.chat.ConversationContext;
import com.github.gradusnikov.eclipse.assistai.chat.FunctionCall;
import com.github.gradusnikov.eclipse.assistai.chat.Incoming;
import com.github.gradusnikov.eclipse.assistai.jobs.ExecuteFunctionCallJob;

/**
 * Subscriber that handles function call responses from the LLM.
 * 
 * This class is NOT a singleton - a new instance should be created for each
 * request to ensure proper context isolation between different conversation flows
 * (e.g., ChatView vs code completion).
 */
@Creatable
public class FunctionCallSubscriber implements Flow.Subscriber<Incoming>
{
    @Inject
    private ILog logger;
    @Inject
    private Provider<ExecuteFunctionCallJob> executeFunctionCallJobProvider;
    
    private Subscription subscription;
    private final StringBuffer jsonBuffer;
    private final ObjectMapper mapper = new ObjectMapper();
    
    private ConversationContext conversationContext;
    
    public FunctionCallSubscriber()
    {
        jsonBuffer = new StringBuffer();
    }
    
    /**
     * Sets the conversation context for this subscriber.
     * Must be called before the subscriber is used.
     * 
     * @param context The conversation context to use for function call execution
     */
    public void setConversationContext( ConversationContext context )
    {
        this.conversationContext = Objects.requireNonNull( context, "ConversationContext cannot be null" );
    }
    
    /**
     * Returns the current conversation context.
     */
    public ConversationContext getConversationContext()
    {
        return conversationContext;
    }
    
    @Override
    public void onSubscribe( Subscription subscription )
    {
        this.subscription = subscription;
        jsonBuffer.setLength(0);
        subscription.request(1);
    }

    @Override
    public void onNext( Incoming item )
    {
        if ( Incoming.Type.FUNCTION_CALL == item.type() )
        {
            jsonBuffer.append(item.payload());
        }
        subscription.request(1);
    }

    @Override
    public void onError( Throwable throwable )
    {
        jsonBuffer.setLength(0);
    }

    @Override
    public void onComplete()
    {
        String json = jsonBuffer.toString();
        
        if (!json.startsWith("\"function_call\""))
        {
            subscription.request(1);
            return;
        }
        
        // Verify context is set
        if ( conversationContext == null )
        {
            logger.error( "ConversationContext not set in FunctionCallSubscriber. Function calls will not be executed." );
            jsonBuffer.setLength(0);
            subscription.request(1);
            return;
        }
        
        try
        {
            // Split by "function_call" to get individual calls
            String[] parts = json.split("\"function_call\"\\s*:\\s*");
            
            for (String part : parts)
            {
                if (part.isBlank()) continue;
                
                // Find the start of the JSON object
                int startIdx = part.indexOf('{');
                if (startIdx == -1) continue;
                
                String functionCallJson = toValidFunctionCallJson(part, startIdx);
                
                logger.info("Function call json:\n" + functionCallJson);
                
                // Parse JSON manually to extract fields
                var jsonNode = mapper.readTree(functionCallJson);
                String id = jsonNode.has("id") ? jsonNode.get("id").asText() : null;
                String name = jsonNode.get("name").asText();
                @SuppressWarnings("unchecked")
                Map<String, Object> arguments = mapper.convertValue(
                    jsonNode.has("arguments") ? jsonNode.get("arguments") : mapper.createObjectNode(), 
                    Map.class
                );
                String thoughtSignature = jsonNode.has("thoughtSignature") ? jsonNode.get("thoughtSignature").asText() : null;
                
                var functionCall = new FunctionCall(id, name, arguments, thoughtSignature);
                
                // Check if tool is allowed before scheduling
                if ( !conversationContext.isToolAllowed( name ) )
                {
                    logger.warn( "Tool not allowed in context " + conversationContext.getContextId() + ": " + name );
                    continue;
                }
                
                scheduleFunctionCall(functionCall);
                logger.info("Job scheduled: " + functionCall.id());
            }
        }
        catch (Exception e)
        {
            logger.error(e.getMessage(), e);
        }
        
        jsonBuffer.setLength(0);
        subscription.request(1);
    }
    
    private String toValidFunctionCallJson(String part, int startIdx)
    {
        // Extract everything from { onwards
        String functionCallJson = part.substring(startIdx);
        
        // Find where this JSON object ends using proper JSON-aware parsing
        int endIdx = findJsonObjectEnd(functionCallJson);
        if (endIdx > 0 && endIdx < functionCallJson.length())
        {
            functionCallJson = functionCallJson.substring(0, endIdx);
        }
        
        // Count braces outside of string literals to check if JSON is complete
        int braceCount = countUnmatchedBraces(functionCallJson);
        
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
    
    /**
     * Finds the end index of a JSON object, properly handling nested objects and string literals.
     * Returns the index after the closing brace of the top-level object.
     */
    private int findJsonObjectEnd(String json)
    {
        int braceCount = 0;
        boolean inString = false;
        boolean escape = false;
        
        for (int i = 0; i < json.length(); i++)
        {
            char c = json.charAt(i);
            
            if (escape)
            {
                escape = false;
                continue;
            }
            
            if (c == '\\' && inString)
            {
                escape = true;
                continue;
            }
            
            if (c == '"')
            {
                inString = !inString;
                continue;
            }
            
            if (!inString)
            {
                if (c == '{')
                {
                    braceCount++;
                }
                else if (c == '}')
                {
                    braceCount--;
                    if (braceCount == 0)
                    {
                        return i + 1;
                    }
                }
            }
        }
        
        return json.length();
    }
    
    /**
     * Counts unmatched opening braces, properly ignoring braces inside string literals.
     */
    private int countUnmatchedBraces(String json)
    {
        int braceCount = 0;
        boolean inString = false;
        boolean escape = false;
        
        for (int i = 0; i < json.length(); i++)
        {
            char c = json.charAt(i);
            
            if (escape)
            {
                escape = false;
                continue;
            }
            
            if (c == '\\' && inString)
            {
                escape = true;
                continue;
            }
            
            if (c == '"')
            {
                inString = !inString;
                continue;
            }
            
            if (!inString)
            {
                if (c == '{') braceCount++;
                else if (c == '}') braceCount--;
            }
        }
        
        return braceCount;
    }
    
    private void scheduleFunctionCall( FunctionCall functionCall )
    {
        ExecuteFunctionCallJob job = executeFunctionCallJobProvider.get();
        job.setFunctionCall( functionCall );
        job.setConversationContext( conversationContext );
        job.schedule();
    }


}
