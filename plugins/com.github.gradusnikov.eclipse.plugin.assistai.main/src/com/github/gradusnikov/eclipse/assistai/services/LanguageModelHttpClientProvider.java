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
    @Inject
    private Provider<DeepSeekStreamJavaHttpClient> deepseekClientProvider;
    
    public LanguageModelHttpClientProvider()
    {
    }
    
    public LanguageModelClient get()
    {
        var modelApiDescriptor = configuration.getSelectedModel().orElseThrow( () -> new IllegalArgumentException("Model not selected") );
        var apiUrl = modelApiDescriptor.apiUrl();
        
        var clientProvider = switch ( apiUrl.toLowerCase() ) {
        	case String s when s.contains("anthropic") -> anthropicClientProvider;
        	case String s when s.contains("deepseek")  -> deepseekClientProvider;
        	default -> openaiClientProvider; 
        };
        
        
        LanguageModelClient client = clientProvider.get();
//        client.subscribe( conversationSubscriber );
        client.subscribe( printMessageSubscriber );
        client.subscribe( appendMessageToViewSubscriber );
        client.subscribe( functionCallSubscriber );
//        client.subscribe( printToFileSubscriber );
        return client;
    }
}
