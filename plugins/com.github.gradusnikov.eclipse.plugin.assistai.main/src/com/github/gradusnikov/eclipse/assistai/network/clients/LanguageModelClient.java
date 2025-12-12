package com.github.gradusnikov.eclipse.assistai.network.clients;

import java.util.concurrent.Flow;
import java.util.function.Supplier;

import com.github.gradusnikov.eclipse.assistai.chat.Conversation;
import com.github.gradusnikov.eclipse.assistai.chat.ConversationContext;
import com.github.gradusnikov.eclipse.assistai.chat.Incoming;
import com.github.gradusnikov.eclipse.assistai.models.ModelApiDescriptor;

public interface LanguageModelClient
{
    
    void setCancelProvider( Supplier<Boolean> isCancelled );

    /**
     * Sets the model to use for requests.
     * This allows overriding the default model from configuration.
     * 
     * @param model the model descriptor to use
     */
    default void setModel( ModelApiDescriptor model )
    {
        // Default implementation does nothing - clients that support
        // model override should implement this method
    }

    /**
     * Sets the conversation context to use for requests.
     * 
     * @param conversationContext the conversation context to use
     */
    default void setConversationContext( ConversationContext conversationContext )
    {
        // Default implementation does nothing - clients that support
        // conversation context should implement this method
    }    
    
    /**
     * Subscribes a given Flow.Subscriber to receive String data from OpenAI API responses.
     * @param subscriber the Flow.Subscriber to be subscribed to the publisher
     */
    void subscribe( Flow.Subscriber<Incoming> subscriber );

    /**
     * Creates and returns a Runnable that will execute the HTTP request to OpenAI API
     * with the given conversation prompt and process the responses.
     * <p>
     * Note: this method does not block and the returned Runnable should be executed
     * to perform the actual HTTP request and processing.
     *
     * @param prompt the conversation to be sent to the OpenAI API
     * @return a Runnable that performs the HTTP request and processes the responses
     */
    Runnable run( Conversation prompt );

}
