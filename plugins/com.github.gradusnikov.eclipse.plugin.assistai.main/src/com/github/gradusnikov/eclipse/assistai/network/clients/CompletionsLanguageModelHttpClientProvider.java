package com.github.gradusnikov.eclipse.assistai.network.clients;


import java.util.Optional;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.chat.ConversationContext;
import com.github.gradusnikov.eclipse.assistai.models.ModelApiDescriptorRepository;
import com.github.gradusnikov.eclipse.assistai.network.subscribers.FunctionCallSubscriber;
import com.github.gradusnikov.eclipse.assistai.network.subscribers.PrintMessageSubscriber;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class CompletionsLanguageModelHttpClientProvider extends AbstractLanguageModelHttpClientProvider
{
    @Inject
    private ModelApiDescriptorRepository modelApiDescriptorRepository;
    @Inject
    private PrintMessageSubscriber printMessageSubscriber;
    @Inject
    private Provider<FunctionCallSubscriber> functionCallSubscriberProvider;

    @Inject
    public CompletionsLanguageModelHttpClientProvider( 
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
     * Returns a client configured with the given conversation context and continuation callback.
     * This ensures function call results are routed to the correct conversation.
     * 
     * @param context The conversation context for this request
     * @param onContinue The continuation callback to invoke after function execution (can be null)
     * @return A configured LanguageModelClient with proper function call handling
     */
    @Override
    public LanguageModelClient get( ConversationContext context, Runnable onContinue )
    {
        var modelApiDescriptor = Optional.ofNullable( modelApiDescriptorRepository.getCompletionsModelInUse() )
                .orElseThrow( () -> new IllegalArgumentException("Model not selected") );
        
        LanguageModelClient client = createClient( modelApiDescriptor, context );
        client.subscribe( printMessageSubscriber );
        
        // Create a new FunctionCallSubscriber instance with the context and continuation callback
        FunctionCallSubscriber functionCallSubscriber = functionCallSubscriberProvider.get();
        functionCallSubscriber.setConversationContext( context );
        functionCallSubscriber.setOnContinue( onContinue );
        client.subscribe( functionCallSubscriber );
        
        return client;
    }
}
