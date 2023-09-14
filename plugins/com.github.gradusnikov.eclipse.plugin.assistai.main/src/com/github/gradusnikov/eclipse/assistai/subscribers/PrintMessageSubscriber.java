package com.github.gradusnikov.eclipse.assistai.subscribers;

import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscription;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.model.Incoming;

@Creatable
public class PrintMessageSubscriber implements Flow.Subscriber<Incoming>
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
        System.out.print(item.payload());
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
