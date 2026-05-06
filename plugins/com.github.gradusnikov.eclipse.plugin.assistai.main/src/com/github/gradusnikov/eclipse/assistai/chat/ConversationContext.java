package com.github.gradusnikov.eclipse.assistai.chat;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

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
    private final BiConsumer<FunctionCall, CallToolResult> onFunctionResult;
    private final Runnable onConversationContinue;
    
    private ConversationContext(Builder builder)
    {
        this.contextId = builder.contextId != null ? builder.contextId : UUID.randomUUID().toString();
        this.conversation = Objects.requireNonNull(builder.conversation, "Conversation cannot be null");
        this.allowedTools = builder.allowedTools != null ? Set.copyOf(builder.allowedTools) : null;
        this.onFunctionResult = builder.onFunctionResult;
        this.onConversationContinue = builder.onConversationContinue;
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
     * Invokes the onFunctionResult callback if one was registered.
     */
    public void handleFunctionResult(FunctionCall functionCall, CallToolResult result)
    {
        if (onFunctionResult != null)
        {
            onFunctionResult.accept(functionCall, result);
        }
    }
    
    /**
     * Returns true if a conversation continue callback is registered.
     */
    public boolean shouldContinueConversation()
    {
        return onConversationContinue != null;
    }
    
    /**
     * Invokes the onConversationContinue callback if one was registered.
     */
    public void continueConversation()
    {
        if (onConversationContinue != null)
        {
            onConversationContinue.run();
        }
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
        private BiConsumer<FunctionCall, CallToolResult> onFunctionResult;
        private Runnable onConversationContinue;
        
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
         * Sets the callback to invoke when a function result is available.
         */
        public Builder onFunctionResult(BiConsumer<FunctionCall, CallToolResult> callback)
        {
            this.onFunctionResult = callback;
            return this;
        }
        
        /**
         * Sets the callback to invoke when conversation should continue.
         */
        public Builder onConversationContinue(Runnable callback)
        {
            this.onConversationContinue = callback;
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
