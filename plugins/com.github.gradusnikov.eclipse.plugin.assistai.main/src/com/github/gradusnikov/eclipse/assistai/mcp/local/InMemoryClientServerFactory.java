
package com.github.gradusnikov.eclipse.assistai.mcp.local;

import java.util.Objects;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.mcp.McpServerFactory;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Factory for creating Model Context Protocol (MCP) client-server pairs that communicate
 * through an in-memory transport mechanism. This class facilitates the integration of
 * AI assistants with Eclipse IDE tools by providing a communication bridge between them.
 * <p>
 * The factory extracts tool definitions from annotated methods, configures the MCP server
 * with these tools, and creates a client that can communicate with the server.
 */
@Creatable
@Singleton
public class InMemoryClientServerFactory
{
    private final McpServerFactory mcpServerFactory;
    
    /**
     * Record representing a synchronized MCP client-server pair that communicate
     * through an in-memory transport.
     */
    public record InMemorySyncClientServer( McpSyncClient client, McpSyncServer server ) {};
    
    @Inject
    public InMemoryClientServerFactory(McpServerFactory serverFactory )
    {
        Objects.requireNonNull( serverFactory );
        this.mcpServerFactory = serverFactory;
    }
    
    /**
     * Creates a synchronized MCP client-server pair that communicate through an
     * in-memory transport. The server implementation must be annotated with
     * {@link com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer}.
     * 
     * @param serverImplementation An object whose class is annotated with {@link com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer}
     *                            and contains methods annotated with {@link com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool}
     * @return A record containing the synchronized client and server
     * @throws IllegalArgumentException If the serverImplementation is not annotated with {@link com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer}
     */
    public InMemorySyncClientServer creteInMemorySyncClientServerPair( Object serverImplementation )
    {
        // create built-in MCP client server
        var transports =  InMemoryTransport.createEntangledTransportPair();

        var server = mcpServerFactory.createSyncServer(serverImplementation, transports.getServerTransport() );
        
        McpSyncClient client = buildClient( server.getServerInfo(), transports.getClientTransport() );
        
        return new InMemorySyncClientServer( client, server );
        
    }

    /**
     * Builds an MCP client that communicates through the provided transport.
     * 
     * @param info Information about the client implementation
     * @param transports The transport pair containing client and server transports
     * @return A synchronized MCP client
     */
    private McpSyncClient buildClient( McpSchema.Implementation info, McpClientTransport clientTransport )
    {
        McpSyncClient client = McpClient.sync( clientTransport ).clientInfo( info ).build();
        return client;
    }
}
