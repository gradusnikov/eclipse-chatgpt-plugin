package com.github.gradusnikov.eclipse.assistai.services;


import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.subscribers.AppendMessageToViewSubscriber;
import com.github.gradusnikov.eclipse.assistai.subscribers.FunctionCallSubscriber;
import com.github.gradusnikov.eclipse.assistai.subscribers.PrintMessageSubscriber;
import com.github.gradusnikov.eclipse.assistai.subscribers.PrintToFileMessageSubscriber;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class LanguageModelHttpClientProvider
{
    @Inject
    private Provider<OpenAIStreamJavaHttpClient> openaiClientProvider;
    @Inject
    private Provider<AnthropicStreamJavaHttpClient> anthropicClientProvider;
    @Inject
    private AppendMessageToViewSubscriber appendMessageToViewSubscriber;
    @Inject
    private FunctionCallSubscriber functionCallSubscriber;
    @Inject
    private PrintMessageSubscriber printMessageSubscriber;
    @Inject
    private LanguageModelClientConfiguration configuration;
    @Inject
    private PrintToFileMessageSubscriber printToFileSubscriber;
    
    public LanguageModelHttpClientProvider()
    {
    }
    
    public LanguageModelClient get()
    {
        var modelApiDescriptor = configuration.getSelectedModel().orElseThrow( () -> new IllegalArgumentException("Model not selected") );
        var apiUrl = modelApiDescriptor.apiUrl();
        
        Provider<? extends LanguageModelClient> clientProvider;
        if ( apiUrl.toLowerCase().contains( "anthropic" ) )
        {
            clientProvider = anthropicClientProvider;
        }
        else
        {
            clientProvider = openaiClientProvider;
        }
        
        LanguageModelClient client = clientProvider.get();
//        client.subscribe( conversationSubscriber );
        client.subscribe( printMessageSubscriber );
        client.subscribe( appendMessageToViewSubscriber );
        client.subscribe( functionCallSubscriber );
//        client.subscribe( printToFileSubscriber );
        return client;
    }
}
