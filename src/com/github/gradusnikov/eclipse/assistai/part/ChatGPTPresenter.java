package com.github.gradusnikov.eclipse.assistai.part;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.di.UISynchronize;

import com.github.gradusnikov.eclipse.assistai.model.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.model.Conversation;
import com.github.gradusnikov.eclipse.assistai.prompt.JobFactory;
import com.github.gradusnikov.eclipse.assistai.subscribers.AppendMessageToViewSubscriber;

@Creatable
@Singleton
public class ChatGPTPresenter
{
    @Inject
    private ILog logger;
    
    @Inject
    private PartAccessor partAccessor;
    
    @Inject
    private Conversation conversation;
    
    @Inject
    private UISynchronize uiSync;
    
    @Inject
    private JobFactory jobFactory;        

    @Inject
    private AppendMessageToViewSubscriber appendMessageToViewSubscriber;
    
    @PostConstruct
    public void init()
    {
        appendMessageToViewSubscriber.setPresenter( this );
    }
    
    public void onClear()
    {
        conversation.clear();
        uiSync.asyncExec( () ->  partAccessor.findMessageView().ifPresent( ChatGPTViewPart::clearChatView ) );
    }

    public void onSendUserMessage( String text )
    {
        logger.info( "Send user message" );
        ChatMessage message;
        synchronized( conversation )
        {
            message = conversation.newMessage( "user" );
            message.setMessage( text );
            conversation.add( message );
        }
        partAccessor.findMessageView().ifPresent( part -> { 
            part.clearUserInput(); 
            part.appendMessage( message.getId());
            part.setMessageHtml( message.getId(), message.getContent() );
        });
        Job job = jobFactory.createSendUserMessageJob( text );
        job.schedule();
    }


    public void hello()
    {
        logger.info( "hello!" );
    }


    public ChatMessage beginMessageFromAssitant()
    {   
        ChatMessage message;
        synchronized (conversation)
        {
            message = conversation.newMessage( "assistant" );
            conversation.add(message);
        }
        partAccessor.findMessageView().ifPresent(messageView -> messageView.appendMessage(message.id));
        return message;
    }


    public void updateMessageFromAssistant( ChatMessage message )
    {
        partAccessor.findMessageView().ifPresent(messageView -> messageView.setMessageHtml( message.getId(), message.getContent() ));
    }


    public void endMessageFromAssistant()
    {
        
    }
}
