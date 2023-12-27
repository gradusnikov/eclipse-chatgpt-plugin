package com.example.handlers.services;

import java.io.IOException;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscription;

import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.model.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.model.Conversation;
import com.github.gradusnikov.eclipse.assistai.model.Incoming;
import com.github.gradusnikov.eclipse.assistai.services.OpenAIStreamJavaHttpClient;

class OpenAIStreamJavaHttpClientTest
{

    @Test
    void test() throws IOException, InterruptedException
    {
        OpenAIStreamJavaHttpClient client = new OpenAIStreamJavaHttpClient();
        client.subscribe(createSubscriber());
        
        Conversation conversation = new Conversation();
        ChatMessage message = new ChatMessage("123", "user" );
        message.setContent( "Translate the following English text to Java code: Create an empty HashMap with keys of type String and values of type Integer" );
        conversation.add( message );
        client.run( conversation );
    }

    public Flow.Subscriber<Incoming> createSubscriber()
    {
        return new Flow.Subscriber<Incoming>()
        {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Subscription subscription)
            {
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(Incoming item)
            {
                System.out.print(item);
                subscription.request(1);
            }

            @Override
            public void onError(Throwable throwable)
            {
                throwable.printStackTrace();
            }

            @Override
            public void onComplete()
            {
                System.out.print("\n");
                subscription.request(1);
            }
        };
    }

}
