
package com.github.gradusnikov.eclipse.assistai.jobs;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;

import com.github.gradusnikov.eclipse.assistai.chat.Attachment;
import com.github.gradusnikov.eclipse.assistai.chat.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.chat.ConversationContext;
import com.github.gradusnikov.eclipse.assistai.chat.FunctionCall;
import com.github.gradusnikov.eclipse.assistai.mcp.local.InMemoryMcpClientRetistry;
import com.github.gradusnikov.eclipse.assistai.models.ModelApiDescriptorRepository;
import com.github.gradusnikov.eclipse.assistai.models.ToolResponseFormat;
import com.github.gradusnikov.eclipse.assistai.resources.CachedResource;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceCache;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceDescriptor;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceReadResult;
import com.github.gradusnikov.eclipse.assistai.tools.ImageUtilities;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import jakarta.inject.Inject;

@Creatable
public class ExecuteFunctionCallJob extends Job
{
    private static final String       JOB_NAME                      = AssistAIJobConstants.JOB_PREFIX + " execute function call";

    private static final String       CLIENT_TOOL_SEPARATOR         = "__";

    private static final int          MAX_IMAGE_RESULT_BYTES        = 20 * 1024 * 1024;

    private static final int          MAX_IMAGE_RESULT_BASE64_CHARS = ( ( MAX_IMAGE_RESULT_BYTES + 2 ) / 3 ) * 4;

    @Inject
    private ILog                      logger;

    @Inject
    private InMemoryMcpClientRetistry mcpClientRetistry;

    @Inject
    private ResourceCache             resourceCache;

    @Inject
    private ModelApiDescriptorRepository modelApiDescriptorRepository;

    private FunctionCall              functionCall;

    private ConversationContext       conversationContext;

    private Runnable                  onContinue;

    // In ExecuteFunctionCallJob.java, add a job rule in the constructor
    public ExecuteFunctionCallJob()
    {
        super( JOB_NAME );
        // Add a mutual exclusion rule for both SendConversationJob and
        // ExecuteFunctionCallJob
        super.setRule( new AssistAIJobRule() );
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

    public void setOnContinue( Runnable onContinue )
    {
        this.onContinue = onContinue;
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
        CallToolRequest request = CallToolRequest.builder( toolName ).arguments( functionCall.arguments() ).build();

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
        var errorResult = CallToolResult.builder().addTextContent( "Tool '" + toolName + "' is not allowed in this context." ).isError( true ).build();
        return handleFunctionResult( errorResult );
    }

    private IStatus handleFunctionResult( CallToolResult result )
    {
        logger.info( "Finished function call " + functionCall.name() + "\n\nResult:\n" + Optional.ofNullable( result ).map( Object::toString ).orElse( "" ) );
        try
        {
            // 1. Create assistant message with function call
            ChatMessage assistantMessage = createAssistantMessage();
            conversationContext.addMessage( assistantMessage );

            // 2. Create function result message
            ChatMessage resultMessage = createFunctionResultMessage( result );
            conversationContext.addMessage( resultMessage );

            // 3. Continue conversation if continuation callback is set
            if ( onContinue != null )
            {
                logger.info( "Calling continuation callback for context: " + conversationContext.getContextId() );
                onContinue.run();
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

        List<Attachment> attachments = new ArrayList<Attachment>();

        // A structured read is recognised from structuredContent, and only from there.
        String cachedReference = cacheStructuredRead( result.structuredContent() );

        for ( McpSchema.Content content : Optional.ofNullable( result.content() ).orElse( List.of() ) )
        {
            if ( content instanceof McpSchema.ImageContent imageContent )
            {
                attachments.add( createImageAttachment( imageContent ) );
            }
            else if ( !( content instanceof McpSchema.TextContent ) )
            {
                logger.error( "Unsupported result content type: " + content.type() );
            }
        }

        var model = modelApiDescriptorRepository.getChatModelInUse();
        ToolResponseFormat responseFormat = model == null ? ToolResponseFormat.PLAIN : model.toolResponseFormat();
        resultMessage.setAttachments( attachments );
        resultMessage.setContent( ToolResultTextFormatter.format( result, responseFormat, cachedReference ) );
        resultMessage.setFunctionCall( functionCall );

        return resultMessage;
    }

    private Attachment.ImageAttachment createImageAttachment( McpSchema.ImageContent content )
    {
        String mimeType = content.mimeType();
        if ( mimeType == null || !mimeType.toLowerCase().startsWith( "image/" ) )
        {
            throw new IllegalArgumentException( "Unsupported image result MIME type: " + mimeType );
        }

        String encoded = content.data();
        if ( encoded.length() > MAX_IMAGE_RESULT_BASE64_CHARS )
        {
            throw new IllegalArgumentException( "Image result exceeds the 20 MiB limit" );
        }

        byte[] bytes = Base64.getDecoder().decode( encoded );
        if ( bytes.length > MAX_IMAGE_RESULT_BYTES )
        {
            throw new IllegalArgumentException( "Image result exceeds the 20 MiB limit" );
        }

        ImageData[] images = new ImageLoader().load( new ByteArrayInputStream( bytes ) );
        if ( images.length == 0 )
        {
            throw new IllegalArgumentException( "Image result contains no decodable image" );
        }

        ImageData image = images[0];
        return new Attachment.ImageAttachment( image, ImageUtilities.createPreview( image ) );
    }

    /**
     * Caches a resource the tool reported as structured content.
     * <p>
     * Recognition is by the shape of the payload rather than by a marker embedded in
     * the tool's text: reads used to be spotted by matching {@code __resourceCache__}
     * in the prose, which made the chat depend on how each tool worded its output.
     *
     * @return the reference to put in the conversation in place of the content, or
     *         null when the payload is not a cacheable read
     */
    private String cacheStructuredRead( Object structuredContent )
    {
        ResourceReadResult read = ResourceReadResult.fromStructuredContent( structuredContent );
        if ( read == null || !read.isCacheable()
                || read.projectName() == null || read.projectName().isBlank()
                || read.filePath() == null || read.filePath().isBlank() )
        {
            return null;
        }

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject( read.projectName() );
        if ( !project.exists() )
        {
            return null;
        }
        IFile file = project.getFile( IPath.fromOSString( read.filePath() ) );
        if ( !file.exists() )
        {
            return null;
        }

        CachedResource cached = resourceCache.put(
                ResourceDescriptor.fromWorkspaceFile( file, functionCall.name() ), read.content() );
        if ( cached == null )
        {
            return null;
        }

        logger.info( "Cached resource: " + cached.descriptor().uri() + " (v" + cached.version() + ")" );
        return String.format(
                "[Resource cached: %s (version %d, ~%d tokens)]%n"
                        + "Content available in <resources> block at top of context.%n"
                        + "Lines %d-%d of %d. modificationStamp=%d",
                cached.descriptor().uri(), cached.version(), cached.estimateTokens(),
                read.returnedRange() == null ? 1 : read.returnedRange().startLine(),
                read.returnedRange() == null ? read.totalLines() : read.returnedRange().endLine(),
                read.totalLines(),
                read.version() == null ? -1 : read.version().modificationStamp() );
    }

    private IStatus handleExecutionError( Throwable throwable )
    {
        logger.error( "Function execution error: " + throwable.getMessage(), throwable );
        return Status.error( throwable.getMessage(), throwable );
    }
}
