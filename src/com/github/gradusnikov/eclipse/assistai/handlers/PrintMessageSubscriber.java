package com.github.gradusnikov.eclipse.assistai.handlers;

import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscription;

import javax.inject.Singleton;

import org.eclipse.e4.core.di.annotations.Creatable;

@Creatable
@Singleton
public class PrintMessageSubscriber implements Flow.Subscriber<String>
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
    }

    @Override
    public void onComplete()
    {
        System.out.print("\n\n");
        subscription.request(1);
    }

}
