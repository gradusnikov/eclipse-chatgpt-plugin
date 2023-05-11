package com.github.gradusnikov.eclipse.assistai.handlers;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.services.OpenAIStreamJavaHttpClient;

@Creatable
@Singleton
public class OpenAIHttpClientProvider
{
    @Inject
    private Provider<OpenAIStreamJavaHttpClient> clientProvider;
    @Inject
    private AppendMessageToViewSubscriber appendMessageToViewSubscriber;
    @Inject
    private PrintMessageSubscriber printMessageSubscriber;
    
    public OpenAIStreamJavaHttpClient get( )
    {
        OpenAIStreamJavaHttpClient client = clientProvider.get();
        client.subscribe( printMessageSubscriber );
        client.subscribe( appendMessageToViewSubscriber );
        return client;
    }
}
