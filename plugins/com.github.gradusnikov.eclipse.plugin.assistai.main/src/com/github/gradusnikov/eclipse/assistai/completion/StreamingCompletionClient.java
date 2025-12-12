package com.github.gradusnikov.eclipse.assistai.completion;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.chat.Conversation;
import com.github.gradusnikov.eclipse.assistai.chat.ConversationContext;
import com.github.gradusnikov.eclipse.assistai.chat.Incoming;
import com.github.gradusnikov.eclipse.assistai.network.clients.CompletionsLanguageModelHttpClientProvider;
import com.github.gradusnikov.eclipse.assistai.network.clients.LanguageModelClient;

import jakarta.inject.Inject;

/**
 * Streaming completion client that provides incremental updates via callbacks.
 * Uses modern Java concurrent APIs (CompletableFuture, Flow) for cleaner async handling.
 */
@Creatable
public class StreamingCompletionClient
{
    /**
     * Tools allowed for code completion context.
     * These are read-only tools that help gather context for better completions.
     */
    private static final Set<String> COMPLETION_ALLOWED_TOOLS = Set.of(
        "eclipse-ide__getSource",
        "eclipse-ide__readProjectResource",
        "eclipse-ide__getProjectLayout",
        "eclipse-ide__getCurrentlyOpenedFile",
        "eclipse-ide__getEditorSelection",
        "eclipse-ide__getJavaDoc",
        "eclipse-ide__getCompilationErrors",
        "eclipse-ide__getMethodCallHierarchy",
        "memory__completion_meta"
    );
    
    /**
     * Maximum number of function call iterations to prevent infinite loops.
     */
    private static final int MAX_FUNCTION_CALL_ITERATIONS = 5;
    
    /**
     * Executor for running streaming completion requests.
     * Uses daemon threads to prevent blocking JVM shutdown.
     */
    private static final Executor COMPLETION_EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "LLM-Streaming-Completion-" + threadNumber.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    });

    private ILog logger;
    private CompletionsLanguageModelHttpClientProvider clientProvider;
    private CompletionConfiguration configuration;

    @Inject
    public StreamingCompletionClient( 
            CompletionsLanguageModelHttpClientProvider clientProvider, 
            CompletionConfiguration configuration, 
            ILog logger )
    {
        this.clientProvider = Objects.requireNonNull( clientProvider );
        this.configuration = Objects.requireNonNull( configuration );
        this.logger = Objects.requireNonNull( logger );
    }

    /**
     * Starts a streaming completion request.
     * 
     * @param conversation The conversation/prompt to send
     * @param onChunk Called for each chunk of text received (optional, can be null)
     * @return A CompletableFuture that completes with the full response text.
     *         Use thenAccept() for completion handling and exceptionally() for errors.
     */
    public CompletableFuture<String> startStreaming( 
            Conversation conversation, 
            Consumer<String> onChunk )
    {
        // Check if completion is enabled
        if ( !configuration.isEnabled() )
        {
            logger.info( "LLM code completion is disabled in preferences" );
            return CompletableFuture.completedFuture( "" );
        }

        String contextId = "completion-" + System.currentTimeMillis();
        logger.info( "Creating completion context: " + contextId );
        
        AtomicInteger functionCallIteration = new AtomicInteger( 0 );
        StringBuilder fullResponse = new StringBuilder();
        
        // Create the conversation context (no callback - it's passed separately)
        CompletableFuture<String> completionFuture = new CompletableFuture<>();
        ConversationContext context = ConversationContext.builder()
                .contextId( contextId )
                .conversation( conversation )
                .allowedTools( COMPLETION_ALLOWED_TOOLS )
                .build();
        
        // Create continuation callback
        Runnable onContinue = createContinuationCallback(
            contextId,
            conversation,
            functionCallIteration,
            fullResponse,
            completionFuture,
            onChunk
        );
        
        // Start the first streaming iteration
        startStreamingIteration( context, onContinue, onChunk, fullResponse, completionFuture );
        
        return completionFuture;
    }
    
    /**
     * Creates a continuation callback that handles function call iterations.
     */
    private Runnable createContinuationCallback(
            String contextId,
            Conversation conversation,
            AtomicInteger functionCallIteration,
            StringBuilder fullResponse,
            CompletableFuture<String> completionFuture,
            Consumer<String> onChunk )
    {
        return () -> {
                    logger.info( "Completion context " + contextId + ": onConversationContinue called, cancelled=" + completionFuture.isCancelled() + ", completed=" + completionFuture.isDone() );
                    
                    // Skip if already cancelled or completed
                    if ( completionFuture.isDone() )
                    {
                        logger.info( "Completion context " + contextId + ": skipping continuation - cancelled or completed" );
                        return;
                    }
                    
                    int iteration = functionCallIteration.incrementAndGet();
                    if ( iteration > MAX_FUNCTION_CALL_ITERATIONS )
                    {
                        logger.warn( "Completion context " + contextId + ": max function call iterations reached (" + MAX_FUNCTION_CALL_ITERATIONS + ")" );
                        completionFuture.complete( fullResponse.toString() );
                        return;
                    }
                    
                    logger.info( "Completion context " + contextId + ": continuing conversation after function call (iteration " + iteration + ")" );
                    
                    // Clear response buffer for new iteration (function results are in conversation)
                    fullResponse.setLength( 0 );
                    
                    // Continue with next iteration
                    ConversationContext ctx = ConversationContext.builder()
                            .contextId( contextId )
                            .conversation( conversation )
                            .allowedTools( COMPLETION_ALLOWED_TOOLS )
                            .build();
                    
                    startStreamingIteration( ctx, this::handleRecursiveContinuation, onChunk, fullResponse, completionFuture );
                };
    }
    
    /**
     * Handles recursive continuation to prevent stack overflow.
     */
    private void handleRecursiveContinuation()
    {
        logger.warn( "Recursive continuation detected - this should not happen in normal flow" );
    }
    
    /**
     * Starts a single streaming iteration (may be followed by function call continuations).
     */
    private void startStreamingIteration(
            ConversationContext context,
            Runnable onContinue,
            Consumer<String> onChunk,
            StringBuilder fullResponse,
            CompletableFuture<String> completionFuture )
    {
        logger.info( "startStreamingIteration called for context: " + context.getContextId() );
        
        // Skip if already completed or cancelled
        if ( completionFuture.isDone() )
        {
            logger.info( "Skipping iteration - already completed or cancelled" );
            return;
        }
        
        AtomicBoolean hasFunctionCall = new AtomicBoolean( false );
        AtomicBoolean markdownTruncated = new AtomicBoolean( false );
        
        LanguageModelClient client = clientProvider.get( context, onContinue );
        client.setCancelProvider( completionFuture::isCancelled );
        
        // Create a future for this streaming iteration
        CompletableFuture<Void> iterationFuture = new CompletableFuture<>();
        
        // Subscribe to the stream
        client.subscribe( new Flow.Subscriber<Incoming>()
        {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe( Flow.Subscription subscription )
            {
                this.subscription = subscription;
                subscription.request( Long.MAX_VALUE );
            }

            @Override
            public void onNext( Incoming item )
            {
                if ( completionFuture.isDone() )
                {
                    subscription.cancel();
                    return;
                }

                if ( item.type() == Incoming.Type.CONTENT )
                {
                    String chunk = sanitizeMarkdownChunk( item.payload().toString(), markdownTruncated );
                    fullResponse.append( chunk );

                    if ( onChunk != null )
                    {
                        safeCallback( () -> onChunk.accept( chunk ), "chunk callback" );
                    }
                }
                else if ( item.type() == Incoming.Type.FUNCTION_CALL )
                {
                    hasFunctionCall.set( true );
                    logger.info( "Completion stream " + context.getContextId() + ": function call detected, will wait for execution" );
                }
            }

            @Override
            public void onError( Throwable throwable )
            {
                if ( !completionFuture.isDone() )
                {
                    iterationFuture.completeExceptionally( throwable );
                    completionFuture.completeExceptionally( throwable );
                }
            }

            @Override
            public void onComplete()
            {
                logger.info( "Completion stream " + context.getContextId() + ": onComplete called, hasFunctionCall=" + hasFunctionCall.get() );
                
                iterationFuture.complete( null );
                
                // Only complete if no function call is pending
                if ( !hasFunctionCall.get() && !completionFuture.isDone() )
                {
                    completionFuture.complete( fullResponse.toString() );
                }
                else if ( hasFunctionCall.get() )
                {
                    logger.info( "Completion stream " + context.getContextId() + ": completed with function call pending, waiting for continuation" );
                }
            }
        });

        // Run the client request asynchronously
        CompletableFuture.runAsync( () -> {
            try
            {
                client.run( context.getConversation() ).run();
            }
            catch ( Exception e )
            {
                if ( !completionFuture.isDone() )
                {
                    iterationFuture.completeExceptionally( e );
                    completionFuture.completeExceptionally( e );
                }
            }
        }, COMPLETION_EXECUTOR );
        
        // Handle iteration completion with timeout fallback
        iterationFuture
            .orTimeout( 60, java.util.concurrent.TimeUnit.SECONDS )
            .whenComplete( (result, error) -> {
                if ( error != null && !completionFuture.isDone() )
                {
                    logger.error( "Iteration failed or timed out: " + error.getMessage() );
                    if ( !hasFunctionCall.get() )
                    {
                        completionFuture.completeExceptionally( error );
                    }
                }
                else if ( !hasFunctionCall.get() && !completionFuture.isDone() )
                {
                    // Fallback completion if subscriber didn't trigger it
                    completionFuture.complete( fullResponse.toString() );
                }
            });
    }
    
    /**
     * Safely executes a callback, catching and logging any exceptions.
     */
    private void safeCallback( Runnable callback, String callbackName )
    {
        try
        {
            callback.run();
        }
        catch ( Exception e )
        {
            logger.warn( "Error in " + callbackName + ": " + e.getMessage() );
        }
    }
    
    /**
     * Sanitizes markdown code fences from completion chunks.
     */
    private static String sanitizeMarkdownChunk( String rawChunk, AtomicBoolean markdownTruncated )
    {
        if ( rawChunk == null || rawChunk.isEmpty() )
        {
            return "";
        }

        String chunk = rawChunk;

        // Drop common markdown code fences. If the fence is encountered, truncate the rest of the stream.
        int fenceIndex = chunk.indexOf( "```" );
        if ( fenceIndex >= 0 )
        {
            markdownTruncated.set( true );
            chunk = chunk.substring( 0, fenceIndex );
        }

        // Remove standalone fence markers that could arrive split across chunks.
        chunk = chunk.replace( "~~~", "" );

        return chunk;
    }
}
