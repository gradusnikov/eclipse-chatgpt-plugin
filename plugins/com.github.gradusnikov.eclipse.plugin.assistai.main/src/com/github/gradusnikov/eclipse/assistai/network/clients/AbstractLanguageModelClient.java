package com.github.gradusnikov.eclipse.assistai.network.clients;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.eclipse.core.runtime.ILog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gradusnikov.eclipse.assistai.chat.ConversationContext;
import com.github.gradusnikov.eclipse.assistai.mcp.local.InMemoryMcpClientRetistry;
import com.github.gradusnikov.eclipse.assistai.models.ModelApiDescriptor;
import com.github.gradusnikov.eclipse.assistai.prompt.PromptRepository;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceCache;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.inject.Inject;

public abstract class AbstractLanguageModelClient implements LanguageModelClient
{
    protected ModelApiDescriptor model;
    protected ConversationContext conversationContext;    
    
    protected final ILog logger;
    
    protected final LanguageModelClientConfiguration configuration;
    
    protected final InMemoryMcpClientRetistry mcpClientRegistry;
    
    protected final ResourceCache resourceCache;
    
    protected final PromptRepository promptRepository;
    
    protected final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void setModel( ModelApiDescriptor model )
    {
        Objects.requireNonNull( model );
        this.model = model;
    }
    @Override
    public void setConversationContext( ConversationContext conversationContext )
    {
        Objects.requireNonNull( conversationContext );    
        this.conversationContext = conversationContext;    
    }
    
    @Inject
    public AbstractLanguageModelClient( ILog logger, 
                                        LanguageModelClientConfiguration configuration, 
                                        InMemoryMcpClientRetistry mcpClientRegistry, 
                                        ResourceCache resourceCache, 
                                        PromptRepository promptRepository )
    {
        this.logger = Objects.requireNonNull( logger );
        this.configuration = Objects.requireNonNull( configuration );
        this.mcpClientRegistry = Objects.requireNonNull( mcpClientRegistry );
        this.resourceCache = Objects.requireNonNull( resourceCache );
        this.promptRepository = Objects.requireNonNull( promptRepository );
    }
    
    /**
     * Returns a map of available tools for this {@link ConversationContext}
     * @return
     */
    public Map<String, Tool> listAvailableTools()
    {
        Map<String, Tool> result = new LinkedHashMap<>();
        for (var client : mcpClientRegistry.listEnabledClients().entrySet())
        {
            var clientName = client.getKey();
            var tools  = client.getValue().listTools().tools();
            for ( var tool : tools )
            {
                // tool name
                var toolName = toToolName( clientName, tool );
                // is tool allowed
                if ( conversationContext == null || (conversationContext != null && conversationContext.isToolAllowed( toolName ) ) )
                {
                    result.put( toolName, tool );    
                }
            }
        }
        return result;
    }
    protected String toToolName(String clientName, Tool tool )
    {
        return clientName + "__" + tool.name();
    }
    
}
