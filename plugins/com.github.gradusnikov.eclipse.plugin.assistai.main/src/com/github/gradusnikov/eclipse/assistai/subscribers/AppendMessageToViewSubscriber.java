package com.github.gradusnikov.eclipse.assistai.subscribers;

import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscription;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.model.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.model.Incoming;
import com.github.gradusnikov.eclipse.assistai.part.ChatGPTPresenter;

@Creatable
@Singleton
public class AppendMessageToViewSubscriber implements Flow.Subscriber<Incoming>
{
    @Inject
    private ILog logger;
    
    private Flow.Subscription subscription;
    
    private ChatMessage message;
    private ChatGPTPresenter presenter;
    private Incoming.Type messageType;
    
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
        subscription.request(1);
    }

    @Override
    public void onNext(Incoming item)
    {
        Objects.requireNonNull( presenter );
        Objects.requireNonNull( subscription );
        
        synchronized ( this )
        {
        	if ( Objects.isNull( message ) ||  messageType != item.type() )
        	{
        		if ( Objects.nonNull( message ) )
        		{
        			presenter.endMessageFromAssistant( message );
        		}
        		messageType = item.type();
	        	message = presenter.beginMessageFromAssistant();
        	}
        	message.append( item.payload().toString() );
	        presenter.updateMessageFromAssistant( message ); 
        	subscription.request(1);
        }
    }

    @Override
    public void onError(Throwable throwable)
    {
        messageType = null;
        message = null;
        logger.error(throwable.getMessage(), throwable);
    }

    @Override
    public void onComplete()
    {
        Objects.requireNonNull( presenter );
        synchronized ( this )
        {
    		presenter.endMessageFromAssistant( message );
        	message = null;
        	subscription = null;
        	messageType = null;
        	subscription.request(1);
        }
    }
    

}
