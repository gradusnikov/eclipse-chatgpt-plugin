package com.github.gradusnikov.eclipse.assistai.mcp;

import org.eclipse.e4.core.di.annotations.Creatable;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.server.McpServer;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class BuiltInMcpClientServerProvider
{
    
    
    
    
    public McpClient getMcpClient()
    {
        return null;
    }
    
    public McpServer getMcpServer()
    {
        return null;
    }
}
