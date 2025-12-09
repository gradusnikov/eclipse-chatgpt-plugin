package com.github.gradusnikov.eclipse.assistai.chat;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.resources.ResourceCache;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class Conversation
{
    public List<ChatMessage> conversation = new LinkedList<>();
    
    @Inject
    private ResourceCache resourceCache;

    public int size()
    {
        return conversation.size();
    }
    
    public void clear()
    {
        conversation.clear();
        // Clear the resource cache when conversation is reset
        if (resourceCache != null) {
            resourceCache.clear();
        }
    }
    
    public synchronized void add(ChatMessage message)
    {
        conversation.add(message);
    }
    
    public List<ChatMessage> messages()
    {
        return conversation;
    }
    
    public void removeMessageById( String messageId )
    {
        conversation.stream()
                    .filter( message -> messageId.equals( message.getId() ) )
                    .findFirst()
                    .ifPresent( messageToRemove -> conversation.remove( messageToRemove ) );
        
    }
    
    public Optional<ChatMessage> removeLastMessage()
    {
        ChatMessage removed = !conversation.isEmpty() ? conversation.remove( conversation.size() - 1 ) : null;
        return Optional.ofNullable( removed );
    }
    
    public Optional<ChatMessage> lastMessage()
    {
    	return conversation.isEmpty() 
    			? Optional.empty() 
    			: Optional.of( conversation.get( conversation.size() - 1) );
    }
    
    
}
