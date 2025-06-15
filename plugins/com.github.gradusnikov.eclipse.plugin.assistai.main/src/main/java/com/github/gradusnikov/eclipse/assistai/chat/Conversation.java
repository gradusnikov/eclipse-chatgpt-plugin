package com.github.gradusnikov.eclipse.assistai.chat;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Singleton;

import org.eclipse.e4.core.di.annotations.Creatable;

@Creatable
@Singleton
public class Conversation
{
    public List<ChatMessage> conversation = new LinkedList<>();

    public int size()
    {
        return conversation.size();
    }
    
    public void clear()
    {
        conversation.clear();
    }
    
    public synchronized void add(ChatMessage message)
    {
        conversation.add(message);
    }
    
    public List<ChatMessage> messages()
    {
        return conversation;
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
