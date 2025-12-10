package com.github.gradusnikov.eclipse.assistai.completion;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.ui.text.java.ContentAssistInvocationContext;
import org.eclipse.jdt.ui.text.java.IJavaCompletionProposalComputer;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.CompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.swt.graphics.Image;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.chat.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.chat.Conversation;
import com.github.gradusnikov.eclipse.assistai.prompt.Prompts;
import com.github.gradusnikov.eclipse.assistai.repository.PromptRepository;

/**
 * Eclipse JDT completion proposal computer that uses LLM for code completion.
 * Integrates with Ctrl+Space content assist in Java editors.
 * 
 * Supports both synchronous and streaming completion modes based on user preferences.
 */
public class LLMCompletionProposalComputer implements IJavaCompletionProposalComputer
{

    // Icon for AI completions
    private static final String      AI_ICON_KEY = "assistai-16";

    private ILog                     logger;

    private CompletionContextBuilder contextBuilder;

    private StreamingCompletionClient streamingCompletionClient;

    private CompletionConfiguration  configuration;

    private PromptRepository         promptRepository;

    private Image                    aiIcon;

    private String                   errorMessage;

    public LLMCompletionProposalComputer()
    {
        // Dependencies will be initialized in sessionStarted()
    }

    @Override
    public void sessionStarted()
    {
        // Initialize dependencies from the Activator's injector
        try
        {
            Activator activator = Activator.getDefault();
            this.logger = Activator.getDefault().getLog();
            this.contextBuilder = activator.make( CompletionContextBuilder.class );
            this.streamingCompletionClient = activator.make( StreamingCompletionClient.class );
            this.configuration = activator.make( CompletionConfiguration.class );
            this.promptRepository = activator.make( PromptRepository.class );
            this.errorMessage = null;

            // Load AI icon
            loadIcon();

        }
        catch ( Exception e )
        {
            throw new RuntimeException( e );
        }
    }

    /**
     * Loads the AI icon for completion proposals.
     */
    private void loadIcon()
    {
        try
        {
            String uri = String.format( "platform:/plugin/com.github.gradusnikov.eclipse.plugin.assistai.main/icons/%s.png", AI_ICON_KEY );
            ImageDescriptor descriptor = ImageDescriptor.createFromURI( new URI( uri ) );
            this.aiIcon = descriptor.createImage();
        }
        catch ( Exception e )
        {
            // Icon loading failed, will use null (no icon)
            if ( logger != null )
            {
                logger.warn( "Could not load AI icon: " + e.getMessage() );
            }
        }
    }

    @Override
    public void sessionEnded()
    {
        // Don't dispose icon here - it may be reused across sessions
    }

    @Override
    public List<ICompletionProposal> computeCompletionProposals( ContentAssistInvocationContext context, IProgressMonitor monitor )
    {

        // Check if completion is enabled
        if ( configuration == null || !configuration.isEnabled() )
        {
            return Collections.emptyList();
        }

        if ( contextBuilder == null || streamingCompletionClient == null )
        {
            errorMessage = "LLM completion not initialized";
            return Collections.emptyList();
        }

        try
        {
            // Build completion context
            Optional<CompletionContext> completionContextOpt = contextBuilder.build( context );
            if ( completionContextOpt.isEmpty() )
            {
                return Collections.emptyList();
            }

            CompletionContext completionContext = completionContextOpt.get();

            // Skip if we're at an empty position with no context
            if ( completionContext.codeBeforeCursor().isBlank() )
            {
                return Collections.emptyList();
            }

            // Build the prompt
            String prompt = buildPrompt( completionContext );

            // Create conversation
            Conversation conversation = new Conversation();
            ChatMessage userMessage = new ChatMessage( java.util.UUID.randomUUID().toString(), "user" );
            userMessage.setContent( prompt );
            conversation.add( userMessage );

            // Check for cancellation
            if ( monitor.isCanceled() )
            {
                return Collections.emptyList();
            }

            // Choose between streaming and sync based on preference
            Optional<String> responseOpt;
            responseOpt = completeWithStreaming( conversation, monitor );
            if ( responseOpt.isEmpty() || monitor.isCanceled() )
            {
                return Collections.emptyList();
            }

            // Parse JSON response
            String response = responseOpt.get();
            String completion = parseCompletion( response );

            if ( completion == null || completion.isBlank() )
            {
                if ( logger != null )
                {
                    logger.warn( "Could not parse completion from response: " + ( response.length() > 200 ? response.substring( 0, 200 ) + "..." : response ) );
                }
                return Collections.emptyList();
            }

            // Create the completion proposal
            ICompletionProposal proposal = createProposal( completion, context.getInvocationOffset(), context.getDocument() );

            return List.of( proposal );

        }
        catch ( Exception e )
        {
            if ( logger != null )
            {
                logger.error( "Error computing LLM completion: " + e.getMessage(), e );
            }
            errorMessage = "LLM completion error: " + e.getMessage();
            return Collections.emptyList();
        }
    }
    
    /**
     * Performs completion using the streaming client, collecting all chunks into a single result.
     * This allows progress monitoring and better responsiveness even when using streaming.
     */
    private Optional<String> completeWithStreaming( Conversation conversation, IProgressMonitor monitor )
    {
        CompletableFuture<String> future = new CompletableFuture<>();
        StringBuilder result = new StringBuilder();
        AtomicReference<StreamingCompletionClient.CompletionHandle> handleRef = new AtomicReference<>();
        
        StreamingCompletionClient.CompletionHandle handle = streamingCompletionClient.startStreaming(
            conversation,
            // On chunk - accumulate text
            chunk -> {
                if ( !monitor.isCanceled() )
                {
                    result.append( chunk );
                }
            },
            // On complete
            fullResponse -> {
                if ( !future.isDone() )
                {
                    future.complete( fullResponse );
                }
            },
            // On error
            error -> {
                if ( !future.isDone() )
                {
                    future.completeExceptionally( error );
                }
            }
        );
        
        handleRef.set( handle );
        
        try
        {
            // Wait with timeout, checking for cancellation periodically
            long timeoutMs = configuration.getTimeout().toMillis();
            long startTime = System.currentTimeMillis();
            
            while ( !future.isDone() )
            {
                if ( monitor.isCanceled() )
                {
                    handle.cancel();
                    return Optional.empty();
                }
                
                if ( System.currentTimeMillis() - startTime > timeoutMs )
                {
                    handle.cancel();
                    if ( logger != null )
                    {
                        logger.warn( "Streaming completion timed out" );
                    }
                    return Optional.empty();
                }
                
                try
                {
                    return Optional.of( future.get( 100, TimeUnit.MILLISECONDS ) );
                }
                catch ( java.util.concurrent.TimeoutException e )
                {
                    // Continue waiting
                }
            }
            
            return Optional.of( future.get() );
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
            handle.cancel();
            return Optional.empty();
        }
        catch ( Exception e )
        {
            if ( logger != null )
            {
                logger.error( "Streaming completion failed: " + e.getMessage(), e );
            }
            return Optional.empty();
        }
    }

    @Override
    public List<IContextInformation> computeContextInformation( ContentAssistInvocationContext context, IProgressMonitor monitor )
    {
        // We don't provide context information
        return Collections.emptyList();
    }

    @Override
    public String getErrorMessage()
    {
        return errorMessage;
    }

    /**
     * Builds the prompt using the template and completion context.
     */
    private String buildPrompt( CompletionContext ctx )
    {
        String template = promptRepository.getPrompt( Prompts.COMPLETION.name() );

        return template.replace( "${currentFileName}", ctx.fileName() )
                       .replace( "${currentProjectName}", ctx.projectName() )
                       .replace( "${fileExtension}", ctx.fileExtension() )
                       .replace( "${codeBeforeCursor}", ctx.codeBeforeCursor() )
                       .replace( "${codeAfterCursor}", ctx.codeAfterCursor() )
                       .replace( "${cursorLine}", String.valueOf( ctx.cursorLine() ) )
                       .replace( "${cursorColumn}", String.valueOf( ctx.cursorColumn() ) );
    }

    /**
     * Parses the completion from direct code response.
     */
    private String parseCompletion( String response )
    {
        if ( response == null || response.isBlank() )
        {
            return null;
        }

        return response.trim();
    }

    /**
     * Creates an ICompletionProposal from the completion text.
     */
    private ICompletionProposal createProposal( String completion, int offset, IDocument document )
    {
        // Calculate display string (first line or truncated)
        String displayString = completion.contains( "\n" ) ? completion.substring( 0, completion.indexOf( '\n' ) ) + "..." : completion;

        if ( displayString.length() > 60 )
        {
            displayString = displayString.substring( 0, 57 ) + "...";
        }

        // Cursor position after insertion
        int cursorPosition = completion.length();

        return new CompletionProposal( completion, // Replacement string
                offset, // Replacement offset
                0, // Replacement length (insert, don't replace)
                cursorPosition, // Cursor position after
                aiIcon, // Image (AI icon)
                displayString, // Display string
                null, // Context information
                "AI-generated code completion\n\nGenerated by AssistAI using LLM" // Additional
                                                                                  // info
        );
    }
}
