package com.example.handlers.services;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscription;

import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.services.OpenAIStreamJavaHttpClient;

class OpenAIStreamJavaHttpClientTest
{

    @Test
    void test() throws IOException, InterruptedException
    {
        OpenAIStreamJavaHttpClient client = new OpenAIStreamJavaHttpClient();
        client.subscribe(createSubscriber());
        client.run(
                "Translate the following English text to Java code: Create an empty HashMap with keys of type String and values of type Integer");
    }

    public Flow.Subscriber<String> createSubscriber()
    {
        return new Flow.Subscriber<String>()
        {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Subscription subscription)
            {
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(String item)
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
