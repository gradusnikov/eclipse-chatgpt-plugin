
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
import com.github.gradusnikov.eclipse.assistai.chat.Conversation;
import com.github.gradusnikov.eclipse.assistai.chat.FunctionCall;
import com.github.gradusnikov.eclipse.assistai.mcp.McpClientRetistry;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

@Creatable
public class ExecuteFunctionCallJob extends Job
{
    private static final String           JOB_NAME              = AssistAIJobConstants.JOB_PREFIX + " execute function call";

    private static final String           CLIENT_TOOL_SEPARATOR = "__";

    @Inject
    private ILog                          logger;

    @Inject
    private Provider<SendConversationJob> sendConversationJobProvider;

    @Inject
    private Conversation                  conversation;

    @Inject
    private McpClientRetistry             mcpClientRetistry;

    private FunctionCall                  functionCall;


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

    private IStatus handleFunctionResult( CallToolResult result )
    {
        logger.info( "Finished function call " + functionCall.name() 
                    + "\n\nResult:\n\\n" + Optional.ofNullable( result ).map( Object::toString ).orElse( "" ) );
        try
        {
            // 1. Create assistant message with function call
            ChatMessage assistantMessage = createAssistantMessage();
            conversation.add( assistantMessage );

            // 2. Create function result message
            ChatMessage resultMessage = createFunctionResultMessage( result );
            conversation.add( resultMessage );

            // 3. Send updated conversation to LLM
            scheduleConversationSending();

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
            // TODO: add support for images and other content types
            switch ( content.type() )
            {
                case "text" -> textContent.append( ((McpSchema.TextContent) content).text() ).append( "\n" );
                default -> logger.error( "Unsupported result content type: " + content.type() );
                    
            }
        }
        resultMessage.setAttachments( attachments );
        resultMessage.setContent( textContent.toString() );
        resultMessage.setFunctionCall( functionCall );

        return resultMessage;
    }

    private void scheduleConversationSending()
    {
        SendConversationJob job = sendConversationJobProvider.get();
        job.schedule();
    }

    private IStatus handleExecutionError( Throwable throwable )
    {
        logger.error( "Function execution error: " + throwable.getMessage(), throwable );
        return Status.error( throwable.getMessage(), throwable );
    }
}
