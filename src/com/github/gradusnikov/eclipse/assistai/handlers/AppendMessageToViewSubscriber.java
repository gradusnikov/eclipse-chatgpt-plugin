package com.github.gradusnikov.eclipse.assistai.handlers;

import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscription;

import javax.inject.Inject;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.model.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.model.Conversation;
import com.github.gradusnikov.eclipse.assistai.part.PartAccessor;

@Creatable
public class AppendMessageToViewSubscriber implements Flow.Subscriber<String>
{
    @Inject
    private PartAccessor parts;
    @Inject
    private Conversation conversation;
    
    private Flow.Subscription subscription;
    
    private ChatMessage message;
    
    public AppendMessageToViewSubscriber()
    {
    }
    
    @Override
    public void onSubscribe(Subscription subscription)
    {
        this.subscription = subscription;
        synchronized (conversation)
        {
            message = new ChatMessage(conversation.size(), "assistent");
            conversation.add(message);
        }
        parts.findMessageView().ifPresent(messageView -> messageView.appendMessage(message.id));
        subscription.request(1);
    }

    @Override
    public void onNext(String item)
    {
        message.append(item);
        parts.findMessageView().ifPresent(messageView -> messageView.setMessageHtml( message.id, message.message ));
        subscription.request(1);
    }

    @Override
    public void onError(Throwable throwable)
    {
        Activator.getDefault().getLog().error(throwable.getMessage(), throwable);
    }

    @Override
    public void onComplete()
    {
        subscription.request(1);
    }
    

}
