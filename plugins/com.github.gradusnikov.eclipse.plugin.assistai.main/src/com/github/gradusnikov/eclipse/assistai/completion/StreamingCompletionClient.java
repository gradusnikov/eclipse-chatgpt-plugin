package com.github.gradusnikov.eclipse.assistai.completion;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
 * Used for ghost text preview that updates as the LLM response streams in.
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
        "eclipse-ide__getCompilationErrors"
    );
    
    /**
     * Maximum number of function call iterations to prevent infinite loops.
     */
    private static final int MAX_FUNCTION_CALL_ITERATIONS = 5;

    private ILog                                       logger;

    private CompletionsLanguageModelHttpClientProvider clientProvider;

    private CompletionConfiguration                    configuration;

    @Inject
    public StreamingCompletionClient( 
            CompletionsLanguageModelHttpClientProvider clientProvider, 
            CompletionConfiguration configuration, 
            ILog logger )
    {
        Objects.requireNonNull( clientProvider );
        Objects.requireNonNull( configuration );
        Objects.requireNonNull( logger );
        this.clientProvider = clientProvider;
        this.configuration = configuration;
        this.logger = logger;
    }

    /**
     * Starts a streaming completion request.
     * 
     * @param conversation
     *            The conversation/prompt to send
     * @param onChunk
     *            Called for each chunk of text received
     * @param onComplete
     *            Called when streaming is complete with full text
     * @param onError
     *            Called if an error occurs
     * @return A cancellation handle
     */
    public CompletionHandle startStreaming( 
            Conversation conversation, 
            Consumer<String> onChunk, 
            Consumer<String> onComplete, 
            Consumer<Throwable> onError )
    {

        // Check if completion is enabled
        if ( !configuration.isEnabled() )
        {
            logger.info( "LLM code completion is disabled in preferences" );
            onComplete.accept( "" );
            return new CompletionHandle( () -> {
            } );
        }

        AtomicBoolean cancelled = new AtomicBoolean( false );
        AtomicBoolean finalCompleted = new AtomicBoolean( false );
        AtomicInteger functionCallIteration = new AtomicInteger( 0 );
        StringBuilder fullResponse = new StringBuilder();
        
        // Use AtomicReference to break circular dependency in lambda
        AtomicReference<ConversationContext> contextRef = new AtomicReference<>();

        try
        {
            String contextId = "completion-" + System.currentTimeMillis();
            logger.info( "Creating completion context: " + contextId );
            
            // Create a conversation context for completions
            // This context has restricted tools and handles function call continuations
            ConversationContext context = ConversationContext.builder()
                    .contextId( contextId )
                    .conversation( conversation )
                    .onFunctionResult( (functionCall, result) -> {
                        logger.info( "Completion context " + contextId + ": function call completed: " + functionCall.name() );
                    })
                    .onConversationContinue( () -> {
                        logger.info( "Completion context " + contextId + ": onConversationContinue called, cancelled=" + cancelled.get() + ", finalCompleted=" + finalCompleted.get() );
                        
                        // For completions, we need to re-invoke streaming after function call
                        if ( cancelled.get() || finalCompleted.get() )
                        {
                            logger.info( "Completion context " + contextId + ": skipping continuation - cancelled or completed" );
                            return;
                        }
                        
                        int iteration = functionCallIteration.incrementAndGet();
                        if ( iteration > MAX_FUNCTION_CALL_ITERATIONS )
                        {
                            logger.warn( "Completion context " + contextId + ": max function call iterations reached (" + MAX_FUNCTION_CALL_ITERATIONS + ")" );
                            finalCompleted.set( true );
                            onComplete.accept( fullResponse.toString() );
                            return;
                        }
                        
                        logger.info( "Completion context " + contextId + ": continuing conversation after function call (iteration " + iteration + ")" );
                        
                        // Re-invoke streaming with updated conversation
                        ConversationContext ctx = contextRef.get();
                        if ( ctx != null )
                        {
                            // Clear the response buffer for the new iteration
                            // (function results are in the conversation, not the response text)
                            fullResponse.setLength( 0 );
                            
                            startStreamingInternal( ctx, onChunk, onComplete, onError, cancelled, finalCompleted, fullResponse );
                        }
                        else
                        {
                            logger.error( "Completion context " + contextId + ": contextRef is null!" );
                        }
                    })
                    .allowedTools( COMPLETION_ALLOWED_TOOLS )
                    .build();
            
            // Store the context in the reference for use in the lambda
            contextRef.set( context );
            
            logger.info( "Completion context " + contextId + ": shouldContinueConversation = " + context.shouldContinueConversation() );
            
            // Start the streaming request
            startStreamingInternal( context, onChunk, onComplete, onError, cancelled, finalCompleted, fullResponse );

            // Return cancellation handle
            return new CompletionHandle( () -> {
                cancelled.set( true );
            } );

        }
        catch ( Exception e )
        {
            logger.error( "Error starting streaming completion: " + e.getMessage(), e );
            onError.accept( e );
            return new CompletionHandle( () -> {
            } );
        }
    }
    
    /**
     * Internal method to start or continue streaming.
     */
    private void startStreamingInternal(
            ConversationContext context,
            Consumer<String> onChunk,
            Consumer<String> onComplete,
            Consumer<Throwable> onError,
            AtomicBoolean cancelled,
            AtomicBoolean finalCompleted,
            StringBuilder fullResponse )
    {
        logger.info( "startStreamingInternal called for context: " + context.getContextId() );
        
        // Track if this particular stream iteration had a function call
        AtomicBoolean hasFunctionCall = new AtomicBoolean( false );
        
        // Get client with context for proper function call routing
        LanguageModelClient client = clientProvider.get( context );

        // Set up cancellation
        client.setCancelProvider( cancelled::get );

        // Subscribe to stream
        client.subscribe( new Flow.Subscriber<Incoming>()
        {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe( Flow.Subscription subscription )
            {
                this.subscription = subscription;
                subscription.request( Long.MAX_VALUE ); // Request all items upfront
            }

            @Override
            public void onNext( Incoming item )
            {
                if ( cancelled.get() )
                {
                    subscription.cancel();
                    return;
                }

                if ( item.type() == Incoming.Type.CONTENT )
                {
                    String chunk = item.payload().toString();
                    fullResponse.append( chunk );

                    // Notify of new chunk
                    try
                    {
                        onChunk.accept( chunk );
                    }
                    catch ( Exception e )
                    {
                        logger.warn( "Error in chunk callback: " + e.getMessage() );
                    }
                }
                else if ( item.type() == Incoming.Type.FUNCTION_CALL )
                {
                    // Mark that we have a function call - don't complete yet
                    hasFunctionCall.set( true );
                    logger.info( "Completion stream " + context.getContextId() + ": function call detected, will wait for execution" );
                }
            }

            @Override
            public void onError( Throwable throwable )
            {
                if ( !cancelled.get() && !finalCompleted.get() )
                {
                    finalCompleted.set( true );
                    try
                    {
                        onError.accept( throwable );
                    }
                    catch ( Exception e )
                    {
                        logger.warn( "Error in error callback: " + e.getMessage() );
                    }
                }
            }

            @Override
            public void onComplete()
            {
                logger.info( "Completion stream " + context.getContextId() + ": onComplete called, hasFunctionCall=" + hasFunctionCall.get() + ", cancelled=" + cancelled.get() + ", finalCompleted=" + finalCompleted.get() );
                
                // Only call the final onComplete if there's no function call pending
                // If there's a function call, ExecuteFunctionCallJob will trigger continuation
                if ( !hasFunctionCall.get() && !cancelled.get() && !finalCompleted.get() )
                {
                    finalCompleted.set( true );
                    try
                    {
                        onComplete.accept( fullResponse.toString() );
                    }
                    catch ( Exception e )
                    {
                        logger.warn( "Error in complete callback: " + e.getMessage() );
                    }
                }
                else if ( hasFunctionCall.get() )
                {
                    logger.info( "Completion stream " + context.getContextId() + ": completed with function call pending, waiting for ExecuteFunctionCallJob" );
                }
            }
        } );

        // Run the request in a separate thread
        Thread requestThread = new Thread( () -> {
            try
            {
                client.run( context.getConversation() ).run();
                
                // Give the publisher a moment to deliver the onComplete signal
                // This is needed because SubmissionPublisher.close() is asynchronous
                int waitCount = 0;
                while ( !finalCompleted.get() && !hasFunctionCall.get() && waitCount < 50 && !cancelled.get() )
                {
                    Thread.sleep( 10 );
                    waitCount++;
                }
                
                // If still not completed after waiting and no function call, call onComplete
                if ( !finalCompleted.get() && !hasFunctionCall.get() && !cancelled.get() )
                {
                    finalCompleted.set( true );
                    try
                    {
                        onComplete.accept( fullResponse.toString() );
                    }
                    catch ( Exception e )
                    {
                        logger.warn( "Error in complete callback: " + e.getMessage() );
                    }
                }
            }
            catch ( InterruptedException e )
            {
                Thread.currentThread().interrupt();
                if ( !cancelled.get() && !finalCompleted.get() )
                {
                    finalCompleted.set( true );
                    try
                    {
                        onError.accept( e );
                    }
                    catch ( Exception ex )
                    {
                        logger.warn( "Error in error callback: " + ex.getMessage() );
                    }
                }
            }
            catch ( Exception e )
            {
                if ( !cancelled.get() && !finalCompleted.get() )
                {
                    finalCompleted.set( true );
                    try
                    {
                        onError.accept( e );
                    }
                    catch ( Exception ex )
                    {
                        logger.warn( "Error in error callback: " + ex.getMessage() );
                    }
                }
            }
        }, "LLM-Streaming-Completion" );
        requestThread.setDaemon( true );
        requestThread.start();
    }

    /**
     * Handle for cancelling a streaming completion request.
     */
    public static class CompletionHandle
    {
        private final Runnable cancelAction;

        CompletionHandle( Runnable cancelAction )
        {
            this.cancelAction = cancelAction;
        }

        /**
         * Cancels the completion request.
         */
        public void cancel()
        {
            cancelAction.run();
        }
    }
}
