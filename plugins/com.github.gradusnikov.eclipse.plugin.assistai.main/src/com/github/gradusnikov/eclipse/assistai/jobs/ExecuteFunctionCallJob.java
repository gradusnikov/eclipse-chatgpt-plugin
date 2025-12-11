
package com.github.gradusnikov.eclipse.assistai.jobs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.chat.Attachment;
import com.github.gradusnikov.eclipse.assistai.chat.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.chat.ConversationContext;
import com.github.gradusnikov.eclipse.assistai.chat.FunctionCall;
import com.github.gradusnikov.eclipse.assistai.mcp.local.InMemoryMcpClientRetistry;
import com.github.gradusnikov.eclipse.assistai.resources.CachedResource;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceCache;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceResultSerializer;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceToolResult;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import jakarta.inject.Inject;

@Creatable
public class ExecuteFunctionCallJob extends Job
{
    private static final String           JOB_NAME              = AssistAIJobConstants.JOB_PREFIX + " execute function call";

    private static final String           CLIENT_TOOL_SEPARATOR = "__";

    @Inject
    private ILog                          logger;

    @Inject
    private InMemoryMcpClientRetistry     mcpClientRetistry;

    @Inject
    private ResourceCache                 resourceCache;

    private FunctionCall                  functionCall;
    
    private ConversationContext           conversationContext;


	// In ExecuteFunctionCallJob.java, add a job rule in the constructor
	public ExecuteFunctionCallJob() {
	    super(JOB_NAME);
	    // Add a mutual exclusion rule for both SendConversationJob and ExecuteFunctionCallJob
	    super.setRule(new AssistAIJobRule());
	}
	
	@Override
    protected IStatus run( IProgressMonitor monitor )
    {
        Objects.requireNonNull( functionCall, "Function call cannot be null" );
        Objects.requireNonNull( conversationContext, "Conversation context cannot be null" );

        try
        {
            return executeFunctionCall();
        }
        catch ( Exception e )
        {
            logger.error( "Error executing function call: " + e.getMessage(), e );
            return Status.error( e.getMessage(), e );
        }
    }

    public void setFunctionCall( FunctionCall functionCall )
    {
        this.functionCall = functionCall;
    }
    
    public void setConversationContext( ConversationContext context )
    {
        this.conversationContext = context;
    }

    private IStatus executeFunctionCall()
    {
        logger.info( "Executing function call: " + functionCall );

        // Parse client and tool names
        String clientToolName = functionCall.name();
        int separatorIndex = clientToolName.indexOf( CLIENT_TOOL_SEPARATOR );

        if ( separatorIndex == -1 )
        {
            return Status.error( "Invalid function call format: " + clientToolName );
        }

        String clientName = clientToolName.substring( 0, separatorIndex );
        String toolName = clientToolName.substring( separatorIndex + CLIENT_TOOL_SEPARATOR.length() );

        // Check if tool is allowed in this context
        if ( !conversationContext.isToolAllowed( clientToolName ) )
        {
            logger.warn( "Tool not allowed in this context: " + clientToolName );
            return handleToolNotAllowed( clientToolName );
        }

        // Create tool request
        CallToolRequest request = new CallToolRequest( toolName, functionCall.arguments() );

        // Find and execute the tool
        var clientOpt = mcpClientRetistry.findClient( clientName );
        
        if ( clientOpt.isEmpty() )
        {
            return Status.error( "Tool not found: " + clientName + ":" + toolName );
        }
        
        try
        {
            CallToolResult result = clientOpt.get().callTool( request );
            return handleFunctionResult( result );
        }
        catch ( Exception e )
        {
            return handleExecutionError( e );
        }
    }

    private IStatus handleToolNotAllowed( String toolName )
    {
        // Create an error result for disallowed tool
        CallToolResult errorResult = new CallToolResult(
            List.of( new McpSchema.TextContent( "Tool '" + toolName + "' is not allowed in this context." ) ),
            true // isError
        );
        return handleFunctionResult( errorResult );
    }

    private IStatus handleFunctionResult( CallToolResult result )
    {
        logger.info( "Finished function call " + functionCall.name() 
                    + "\n\nResult:\n\\n" + Optional.ofNullable( result ).map( Object::toString ).orElse( "" ) );
        try
        {
            // 1. Create assistant message with function call
            ChatMessage assistantMessage = createAssistantMessage();
            conversationContext.addMessage( assistantMessage );

            // 2. Create function result message
            ChatMessage resultMessage = createFunctionResultMessage( result );
            conversationContext.addMessage( resultMessage );

            // 3. Notify context of function result
            conversationContext.handleFunctionResult( functionCall, result );

            // 4. Continue conversation if context supports it
            logger.info( "Checking if conversation should continue: " + conversationContext.shouldContinueConversation() + " (context: " + conversationContext.getContextId() + ")" );
            if ( conversationContext.shouldContinueConversation() )
            {
                logger.info( "Calling continueConversation() for context: " + conversationContext.getContextId() );
                conversationContext.continueConversation();
            }

            return Status.OK_STATUS;
        }
        catch ( Exception e )
        {
            logger.error( "Error handling function result: " + e.getMessage(), e );
            return Status.error( e.getMessage(), e );
        }
    }

    private ChatMessage createAssistantMessage()
    {
        ChatMessage message = new ChatMessage( UUID.randomUUID().toString(), "assistant" );
        message.setFunctionCall( functionCall );
        return message;
    }

    private ChatMessage createFunctionResultMessage( CallToolResult result ) throws Exception
    {
        ChatMessage resultMessage = new ChatMessage( UUID.randomUUID().toString(), functionCall.name(), "function" );
        
        StringBuilder textContent = new StringBuilder();
        List<Attachment> attachments = new ArrayList<Attachment>();
        if ( result.isError() )
        {
            textContent.append( "Error: " );
        }
        var contentParts = Optional.ofNullable( result.content() ).orElse( Collections.emptyList() );
        for ( McpSchema.Content content : contentParts )
        {
            switch ( content.type() )
            {
                case "text" -> {
                    String text = ((McpSchema.TextContent) content).text();
                    // Check if this is a cacheable resource result
                    textContent.append( processPossibleResourceResult( text ) ).append( "\n" );
                }
                default -> logger.error( "Unsupported result content type: " + content.type() );
                    
            }
        }
        resultMessage.setAttachments( attachments );
        resultMessage.setContent( textContent.toString() );
        resultMessage.setFunctionCall( functionCall );

        return resultMessage;
    }
    
    /**
     * Processes text that might be a serialized ResourceToolResult.
     * If it is, caches the resource and returns a reference.
     * Otherwise, returns the text unchanged.
     */
    private String processPossibleResourceResult( String text )
    {
        // Check if this is a serialized resource result
        if ( !ResourceResultSerializer.isResourceResult( text ) )
        {
            return text;
        }
        
        ResourceToolResult resourceResult = ResourceResultSerializer.deserialize( text );
        if ( resourceResult == null || !resourceResult.isCacheable() )
        {
            // Deserialization failed or not cacheable, return original content
            return resourceResult != null ? resourceResult.content() : text;
        }
        
        // Cache the resource
        CachedResource cached = resourceCache.put( resourceResult );
        
        if ( cached != null )
        {
            // Return a reference instead of full content
            logger.info( "Cached resource: " + cached.descriptor().uri() + " (v" + cached.version() + ")" );
            return String.format( 
                "[Resource cached: %s (version %d, ~%d tokens)]\n" +
                "Content available in <resources> block at top of context.",
                cached.descriptor().uri(),
                cached.version(),
                cached.estimateTokens()
            );
        }
        else
        {
            // Caching failed, return full content
            return resourceResult.content();
        }
    }

    private IStatus handleExecutionError( Throwable throwable )
    {
        logger.error( "Function execution error: " + throwable.getMessage(), throwable );
        return Status.error( throwable.getMessage(), throwable );
    }
}
