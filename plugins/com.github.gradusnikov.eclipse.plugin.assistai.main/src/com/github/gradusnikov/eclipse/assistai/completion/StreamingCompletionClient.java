package com.github.gradusnikov.eclipse.assistai.completion;

import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.chat.Conversation;
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
        AtomicBoolean completed = new AtomicBoolean( false );
        StringBuilder fullResponse = new StringBuilder();

        try
        {
            // Get raw client without UI subscribers
            LanguageModelClient client = clientProvider.get();

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
                }

                @Override
                public void onError( Throwable throwable )
                {
                    completed.set( true );
                    if ( !cancelled.get() )
                    {
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
                    completed.set( true );
                    if ( !cancelled.get() )
                    {
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
            } );

            // Run the request in a separate thread
            Thread requestThread = new Thread( () -> {
                try
                {
                    client.run( conversation ).run();
                    
                    // Give the publisher a moment to deliver the onComplete signal
                    // This is needed because SubmissionPublisher.close() is asynchronous
                    int waitCount = 0;
                    while ( !completed.get() && waitCount < 50 && !cancelled.get() )
                    {
                        Thread.sleep( 10 );
                        waitCount++;
                    }
                    
                    // If still not completed after waiting, call onComplete with what we have
                    if ( !completed.get() && !cancelled.get() )
                    {
                        completed.set( true );
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
                    if ( !cancelled.get() && !completed.get() )
                    {
                        completed.set( true );
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
                    if ( !cancelled.get() && !completed.get() )
                    {
                        completed.set( true );
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
