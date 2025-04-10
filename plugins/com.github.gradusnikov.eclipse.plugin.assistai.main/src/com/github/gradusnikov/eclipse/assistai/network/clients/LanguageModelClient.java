package com.github.gradusnikov.eclipse.assistai.network.clients;

import java.util.concurrent.Flow;
import java.util.function.Supplier;

import com.github.gradusnikov.eclipse.assistai.chat.Conversation;
import com.github.gradusnikov.eclipse.assistai.chat.Incoming;

public interface LanguageModelClient
{

    void setCancelProvider( Supplier<Boolean> isCancelled );

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