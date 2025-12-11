package com.github.gradusnikov.eclipse.assistai.chat;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Wraps a Conversation and provides context-specific callbacks for function call handling.
 * This allows different use cases (ChatView, code completion) to handle function results
 * appropriately while sharing the same execution infrastructure.
 */
public class ConversationContext
{
    private final String contextId;
    private final Conversation conversation;
    private final BiConsumer<FunctionCall, CallToolResult> onFunctionResult;
    private final Runnable onConversationContinue;
    private final Set<String> allowedTools;
    
    private ConversationContext(Builder builder)
    {
        this.contextId = builder.contextId != null ? builder.contextId : UUID.randomUUID().toString();
        this.conversation = Objects.requireNonNull(builder.conversation, "Conversation cannot be null");
        this.onFunctionResult = builder.onFunctionResult;
        this.onConversationContinue = builder.onConversationContinue;
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
     * Handles the result of a function call execution.
     * Invokes the registered callback if present.
     * 
     * @param functionCall The function call that was executed
     * @param result The result of the execution
     */
    public void handleFunctionResult(FunctionCall functionCall, CallToolResult result)
    {
        if (onFunctionResult != null)
        {
            onFunctionResult.accept(functionCall, result);
        }
    }
    
    /**
     * Triggers continuation of the conversation after a function result has been added.
     * This typically schedules another LLM request.
     */
    public void continueConversation()
    {
        if (onConversationContinue != null)
        {
            onConversationContinue.run();
        }
    }
    
    /**
     * Checks if the conversation should continue after function execution.
     * Returns true if a continuation callback is registered.
     */
    public boolean shouldContinueConversation()
    {
        return onConversationContinue != null;
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
        private BiConsumer<FunctionCall, CallToolResult> onFunctionResult;
        private Runnable onConversationContinue;
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
         * Sets the callback invoked when a function call completes.
         */
        public Builder onFunctionResult(BiConsumer<FunctionCall, CallToolResult> callback)
        {
            this.onFunctionResult = callback;
            return this;
        }
        
        /**
         * Sets the callback invoked to continue the conversation after function execution.
         */
        public Builder onConversationContinue(Runnable callback)
        {
            this.onConversationContinue = callback;
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
