package com.github.gradusnikov.eclipse.assistai.subscribers;

import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscription;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.chat.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.chat.Conversation;
import com.github.gradusnikov.eclipse.assistai.chat.Incoming;
import com.github.gradusnikov.eclipse.assistai.chat.Incoming.Type;
import com.github.gradusnikov.eclipse.assistai.view.ChatGPTPresenter;

@Creatable
@Singleton
public class AppendMessageToViewSubscriber implements Flow.Subscriber<Incoming>
{
    @Inject
    private ILog logger;
    
    private Flow.Subscription subscription;
    
    private ChatGPTPresenter presenter;
    
    private ChatMessage currentMessage;
    
    private ChatMessage currentFunctionCallMessage;
    
    private Type lastType;
    
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
        this.lastType = null;
        this.currentMessage = null;
        this.currentFunctionCallMessage = null;
        subscription.request(1);
    }

    @Override
    public void onNext(Incoming item)
    {
        Objects.requireNonNull( presenter );
        Objects.requireNonNull( subscription );
        
        if ( item.type() != lastType )
        {
            if ( Objects.nonNull(currentMessage))
            {
            	presenter.endMessageFromAssistant( currentMessage );
            	currentMessage = null;
            }
            if ( Objects.nonNull(currentFunctionCallMessage) )
            {
            	presenter.endMessageFromAssistant( currentFunctionCallMessage );
            	currentMessage = null;
            }
            lastType = item.type();
        }
        
        switch ( item.type() )
        {
        	case Type.FUNCTION_CALL -> handleFunctionCall( item.payload() );  
        	case Type.CONTENT -> handleContentMessage( item.payload()  );
        }
    	subscription.request(1);
    }
    
    private void handleContentMessage( Object payload ) 
    {
    	if ( Objects.isNull( currentMessage ) )
    	{
			currentMessage = presenter.beginMessageFromAssistant();
    	}
    	if ( Objects.nonNull( currentMessage) )
    	{
    		currentMessage.append( payload.toString() );
    		presenter.updateMessageFromAssistant(currentMessage);
    	}
	}

	private void handleFunctionCall( Object payload )
    {
		if ( Objects.isNull(currentFunctionCallMessage) )
		{
			currentFunctionCallMessage = presenter.beginFunctionCallMessage();
		}
		if ( Objects.nonNull(currentFunctionCallMessage))
		{
			currentFunctionCallMessage.append( payload.toString() );
    		presenter.updateMessageFromAssistant(currentFunctionCallMessage);
		}
			
    }

    @Override
    public void onError(Throwable throwable)
    {
        logger.error(throwable.getMessage(), throwable);
    }

    @Override
    public void onComplete()
    {
        Objects.requireNonNull( presenter );
        if ( Objects.nonNull(currentMessage))
        {
        	presenter.endMessageFromAssistant( currentMessage );
        	currentMessage = null;
        }
        if ( Objects.nonNull(currentFunctionCallMessage) )
        {
        	presenter.endMessageFromAssistant( currentFunctionCallMessage );
        	currentMessage = null;
        }
    	subscription = null;
        subscription.request(1);
    }
    

}
