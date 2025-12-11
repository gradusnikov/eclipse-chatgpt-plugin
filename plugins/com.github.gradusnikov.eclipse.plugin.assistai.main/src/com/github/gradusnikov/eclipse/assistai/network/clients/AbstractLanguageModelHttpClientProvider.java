package com.github.gradusnikov.eclipse.assistai.network.clients;

import java.util.Objects;

import com.github.gradusnikov.eclipse.assistai.chat.ConversationContext;
import com.github.gradusnikov.eclipse.assistai.models.ModelApiDescriptor;

import jakarta.inject.Provider;

public abstract class AbstractLanguageModelHttpClientProvider implements Provider<LanguageModelClient>
{
    protected final Provider<OpenAIStreamJavaHttpClient> openaiClientProvider;
    protected final Provider<OpenAIResponsesJavaHttpClient> openaiResponsesClientProvider;
    protected final Provider<AnthropicStreamJavaHttpClient> anthropicClientProvider;
    protected final Provider<GrokStreamJavaHttpClient> grokClientProvider;
    protected final Provider<DeepSeekStreamJavaHttpClient> deepseekClientProvider;
    protected final Provider<GeminiStreamJavaHttpClient> geminiClientProvider;

    public AbstractLanguageModelHttpClientProvider(
            Provider<OpenAIStreamJavaHttpClient> openaiClientProvider,
            Provider<OpenAIResponsesJavaHttpClient> openaiResponsesClientProvider,
            Provider<AnthropicStreamJavaHttpClient> anthropicClientProvider,
            Provider<GrokStreamJavaHttpClient> grokClientProvider,
            Provider<DeepSeekStreamJavaHttpClient> deepseekClientProvider,
            Provider<GeminiStreamJavaHttpClient> geminiClientProvider
            )
    {
        super();
        Objects.requireNonNull( openaiClientProvider );
        Objects.requireNonNull( openaiResponsesClientProvider );
        Objects.requireNonNull( anthropicClientProvider );
        Objects.requireNonNull( grokClientProvider );
        Objects.requireNonNull( deepseekClientProvider );
        Objects.requireNonNull( geminiClientProvider );
        this.openaiClientProvider = openaiClientProvider;
        this.openaiResponsesClientProvider = openaiResponsesClientProvider;
        this.anthropicClientProvider = anthropicClientProvider;
        this.grokClientProvider = grokClientProvider;
        this.deepseekClientProvider = deepseekClientProvider;
        this.geminiClientProvider = geminiClientProvider;
        
    }

    /**
     * Creates a new client instance based on the selected model's API URL.
     */
    protected LanguageModelClient createClient( ModelApiDescriptor modelApiDescriptor )
    {
        var apiUrl = modelApiDescriptor.apiUrl();
        
        var clientProvider = switch ( apiUrl.toLowerCase() ) {
            case String s when s.contains("anthropic") -> anthropicClientProvider;
            case String s when s.contains("deepseek")  -> deepseekClientProvider;
            case String s when s.contains("googleapis") -> geminiClientProvider;
            case String s when s.contains("/v1/responses") -> openaiResponsesClientProvider;
            case String s when s.contains("api.x.ai/v1/chat/completions") -> grokClientProvider;
            default -> openaiClientProvider; 
        };
        
        var client =  clientProvider.get();
        client.setModel( modelApiDescriptor );
        return client;
    }
    
    /**
     * Returns a client configured with the given conversation context.
     * This is the preferred method for obtaining clients as it ensures
     * proper routing of function call results.
     * 
     * @param context The conversation context for this request
     * @return A configured LanguageModelClient
     */
    public abstract LanguageModelClient get( ConversationContext context );

}
