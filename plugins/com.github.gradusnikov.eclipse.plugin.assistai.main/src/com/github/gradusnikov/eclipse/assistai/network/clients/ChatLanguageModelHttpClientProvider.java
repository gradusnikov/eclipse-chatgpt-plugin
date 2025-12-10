package com.github.gradusnikov.eclipse.assistai.network.clients;


import java.util.Optional;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.models.ModelApiDescriptorRepository;
import com.github.gradusnikov.eclipse.assistai.network.subscribers.AppendMessageToViewSubscriber;
import com.github.gradusnikov.eclipse.assistai.network.subscribers.FunctionCallSubscriber;
import com.github.gradusnikov.eclipse.assistai.network.subscribers.PrintMessageSubscriber;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class ChatLanguageModelHttpClientProvider extends AbstractLanguageModelHttpClientProvider
{
    @Inject
    private PrintMessageSubscriber printMessageSubscriber;
    @Inject
    private FunctionCallSubscriber functionCallSubscriber;
    @Inject
    private AppendMessageToViewSubscriber appendMessageToViewSubscriber;
    @Inject
    private ModelApiDescriptorRepository modelApiDescriptorRepository;

    @Inject
    public ChatLanguageModelHttpClientProvider( 
            Provider<OpenAIStreamJavaHttpClient> openaiClientProvider,
            Provider<OpenAIResponsesJavaHttpClient> openaiResponsesClientProvider, 
            Provider<AnthropicStreamJavaHttpClient> anthropicClientProvider,
            Provider<GrokStreamJavaHttpClient> grokClientProvider, 
            Provider<DeepSeekStreamJavaHttpClient> deepseekClientProvider,
            Provider<GeminiStreamJavaHttpClient> geminiClientProvider
            )
    {
        super( openaiClientProvider, 
                openaiResponsesClientProvider, 
                anthropicClientProvider, 
                grokClientProvider, 
                deepseekClientProvider, 
                geminiClientProvider );
    }
    
    
    /**
     * Returns a raw client without any subscribers attached.
     * Use this for background operations like code completion
     * where UI updates are not needed.
     * 
     * @return A fresh LanguageModelClient instance without subscribers
     */
    @Override
    public LanguageModelClient get()
    {
        var modelApiDescriptor = Optional.ofNullable( modelApiDescriptorRepository.getChatModelInUse() )
                .orElseThrow( () -> new IllegalArgumentException("Model not selected") );

        LanguageModelClient client = createClient( modelApiDescriptor );
        client.subscribe( appendMessageToViewSubscriber );
        client.subscribe( functionCallSubscriber );
        client.subscribe( printMessageSubscriber );
        return client;
    }
}
