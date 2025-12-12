package com.github.gradusnikov.eclipse.assistai.chat;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Wraps a Conversation and provides context-specific configuration and continuation control.
 * This allows different use cases (ChatView, code completion) to configure tool access
 * and control conversation flow while sharing the same execution infrastructure.
 */
public class ConversationContext
{
    private final String contextId;
    private final Conversation conversation;
    private final Set<String> allowedTools;
    
    private ConversationContext(Builder builder)
    {
        this.contextId = builder.contextId != null ? builder.contextId : UUID.randomUUID().toString();
        this.conversation = Objects.requireNonNull(builder.conversation, "Conversation cannot be null");
        this.allowedTools = builder.allowedTools != null ? Set.copyOf(builder.allowedTools) : null;
    }
    
    /**
     * Returns the unique identifier for this context.
     */
    public String getContextId()
    {
        return contextId;
    }
    
    /**
     * Returns the wrapped conversation.
     */
    public Conversation getConversation()
    {
        return conversation;
    }
    
    /**
     * Adds a message to the wrapped conversation.
     */
    public void addMessage(ChatMessage message)
    {
        conversation.add(message);
    }
    
    /**
     * Checks if a specific tool is allowed in this context.
     * If no tool restrictions are set (allowedTools is null), all tools are allowed.
     * 
     * @param toolName The full tool name (e.g., "eclipse-ide__getSource")
     * @return true if the tool is allowed, false otherwise
     */
    public boolean isToolAllowed(String toolName)
    {
        if (allowedTools == null)
        {
            return true; // No restrictions
        }
        return allowedTools.contains(toolName);
    }
    
    /**
     * Returns the set of allowed tools, or null if all tools are allowed.
     */
    public Set<String> getAllowedTools()
    {
        return allowedTools != null ? Collections.unmodifiableSet(allowedTools) : null;
    }
    
    /**
     * Creates a new builder for ConversationContext.
     */
    public static Builder builder()
    {
        return new Builder();
    }
    
    /**
     * Builder for ConversationContext.
     */
    public static class Builder
    {
        private String contextId;
        private Conversation conversation;
        private Set<String> allowedTools;
        
        /**
         * Sets the context ID. If not set, a random UUID will be generated.
         */
        public Builder contextId(String contextId)
        {
            this.contextId = contextId;
            return this;
        }
        
        /**
         * Sets the conversation to wrap. Required.
         */
        public Builder conversation(Conversation conversation)
        {
            this.conversation = conversation;
            return this;
        }
        
        /**
         * Sets the allowed tools for this context. 
         * If null or not set, all tools are allowed.
         */
        public Builder allowedTools(Set<String> tools)
        {
            this.allowedTools = tools;
            return this;
        }
        
        /**
         * Builds the ConversationContext.
         */
        public ConversationContext build()
        {
            return new ConversationContext(this);
        }
    }
    
    @Override
    public String toString()
    {
        return "ConversationContext[id=" + contextId + 
               ", messageCount=" + conversation.size() + 
               ", hasToolRestrictions=" + (allowedTools != null) + "]";
    }
}
