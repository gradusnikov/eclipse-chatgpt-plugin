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
     * Returns a client without conversation context.
     * Function calls will not be executed properly without context.
     * 
     * @return A LanguageModelClient instance
     * @deprecated Use {@link #get(ConversationContext)} instead
     */
    @Override
    @Deprecated
    public LanguageModelClient get()
    {
        var modelApiDescriptor = Optional.ofNullable( modelApiDescriptorRepository.getCompletionsModelInUse()  )
                .orElseThrow( () -> new IllegalArgumentException("Model not selected") );
        LanguageModelClient client = createClient( modelApiDescriptor );
        client.subscribe( printMessageSubscriber );
        // Note: FunctionCallSubscriber not attached - function calls won't work
        return client;
    }
    
    /**
     * Returns a client configured with the given conversation context.
     * This ensures function call results are routed to the correct conversation.
     * 
     * @param context The conversation context for this request
     * @return A configured LanguageModelClient with proper function call handling
     */
    @Override
    public LanguageModelClient get( ConversationContext context )
    {
        var modelApiDescriptor = Optional.ofNullable( modelApiDescriptorRepository.getCompletionsModelInUse() )
                .orElseThrow( () -> new IllegalArgumentException("Model not selected") );
        
        LanguageModelClient client = createClient( modelApiDescriptor );
        client.subscribe( printMessageSubscriber );
        
        // Create a new FunctionCallSubscriber instance with the context
        FunctionCallSubscriber functionCallSubscriber = functionCallSubscriberProvider.get();
        functionCallSubscriber.setConversationContext( context );
        client.subscribe( functionCallSubscriber );
        
        return client;
    }
}
