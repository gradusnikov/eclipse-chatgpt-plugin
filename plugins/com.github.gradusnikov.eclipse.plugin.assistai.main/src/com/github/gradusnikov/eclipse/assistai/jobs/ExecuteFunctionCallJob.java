package com.github.gradusnikov.eclipse.assistai.jobs;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gradusnikov.eclipse.assistai.mcp.McpClientRetistry;
import com.github.gradusnikov.eclipse.assistai.model.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.model.Conversation;
import com.github.gradusnikov.eclipse.assistai.model.FunctionCall;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

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

    private final ObjectMapper            objectMapper          = new ObjectMapper();

    public ExecuteFunctionCallJob()
    {
        super( JOB_NAME );
    }

    @Override
    protected IStatus run( IProgressMonitor monitor )
    {
        Objects.requireNonNull( functionCall, "Function call cannot be null" );

        try
        {
            return executeFunctionCall().join();
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

    private CompletableFuture<IStatus> executeFunctionCall()
    {
        logger.info( "Executing function call: " + functionCall );

        // Parse client and tool names
        String clientToolName = functionCall.name();
        int separatorIndex = clientToolName.indexOf( CLIENT_TOOL_SEPARATOR );

        if ( separatorIndex == -1 )
        {
            return CompletableFuture.completedFuture( Status.error( "Invalid function call format: " + clientToolName ) );
        }

        String clientName = clientToolName.substring( 0, separatorIndex );
        String toolName = clientToolName.substring( separatorIndex + CLIENT_TOOL_SEPARATOR.length() );

        // Create tool request
        CallToolRequest request = new CallToolRequest( toolName, functionCall.arguments() );

        // Find and execute the tool
        Optional<CompletableFuture<Object>> functionExecutor = mcpClientRetistry.findClient( clientName )
                .map( client -> CompletableFuture.supplyAsync( () -> client.callTool( request ) ) );

        if ( functionExecutor.isEmpty() )
        {
            return CompletableFuture.completedFuture( Status.error( "Tool not found: " + clientName + ":" + toolName ) );
        }

        return functionExecutor.get().thenApply( this::handleFunctionResult ).exceptionally( this::handleExecutionError );
    }

    private IStatus handleFunctionResult( Object result )
    {
        logger.info( "Finished function call " + functionCall.name() );
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

    private ChatMessage createFunctionResultMessage( Object result ) throws Exception
    {
        ChatMessage resultMessage = new ChatMessage( UUID.randomUUID().toString(), functionCall.name(), "function" );

        String resultJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString( result );
        resultMessage.setContent( resultJson );
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