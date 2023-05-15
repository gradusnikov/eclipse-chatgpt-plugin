package com.github.gradusnikov.eclipse.assistai.subscribers;

import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscription;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.model.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.part.ChatGPTPresenter;

@Creatable
@Singleton
public class AppendMessageToViewSubscriber implements Flow.Subscriber<String>
{
    @Inject
    private ILog logger;
    
    private Flow.Subscription subscription;
    
    private ChatMessage message;
    private  ChatGPTPresenter presenter;
    
    public AppendMessageToViewSubscriber( )
    {
    }
    
    public void setPresenter(ChatGPTPresenter presenter)
    {
        this.presenter = presenter;
    }

    @Override
    public void onSubscribe(Subscription subscription)
    {
        Objects.requireNonNull( presenter );
        this.subscription = subscription;
        message = presenter.beginMessageFromAssitant();
        subscription.request(1);
    }

    @Override
    public void onNext(String item)
    {
        Objects.requireNonNull( presenter );
        Objects.requireNonNull( message );
        Objects.requireNonNull( subscription );
        message.append(item);
        presenter.updateMessageFromAssistant( message );
        subscription.request(1);
    }

    @Override
    public void onError(Throwable throwable)
    {
        message = null;
        logger.error(throwable.getMessage(), throwable);
    }

    @Override
    public void onComplete()
    {
        Objects.requireNonNull( presenter );
        message = null;
        subscription = null;
        presenter.endMessageFromAssistant();
        subscription.request(1);
    }
    

}
