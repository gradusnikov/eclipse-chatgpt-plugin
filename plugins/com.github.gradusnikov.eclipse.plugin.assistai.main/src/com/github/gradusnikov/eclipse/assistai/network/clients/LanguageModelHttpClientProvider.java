package com.github.gradusnikov.eclipse.assistai.network.clients;


import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.network.subscribers.AppendMessageToViewSubscriber;
import com.github.gradusnikov.eclipse.assistai.network.subscribers.FunctionCallSubscriber;
import com.github.gradusnikov.eclipse.assistai.network.subscribers.PrintMessageSubscriber;
import com.github.gradusnikov.eclipse.assistai.network.subscribers.PrintToFileMessageSubscriber;

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
    private Provider<OpenAIResponsesJavaHttpClient> openaiResponsesClientProvider;
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
    @Inject
    private Provider<GeminiStreamJavaHttpClient> geminiClientProvider;
    
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
        	case String s when s.contains("googleapis") -> geminiClientProvider;
        	case String s when s.contains("/v1/responses") -> openaiResponsesClientProvider;
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
