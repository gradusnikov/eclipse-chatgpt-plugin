package com.github.gradusnikov.eclipse.assistai.mcp;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.DuckDuckSearchMcpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.EclipseCodeEditingMcpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.EclipseIntegrationsMcpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.MemoryMcpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.ReadWebPageMcpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.TimeMcpServer;

import jakarta.inject.Singleton;

@Creatable
@Singleton
class McpServerBuiltins
{
    
    public static final Class<?>[] BUILT_IN_MCP_SERVERS = {
            EclipseIntegrationsMcpServer.class,
            DuckDuckSearchMcpServer.class,
            TimeMcpServer.class,
            ReadWebPageMcpServer.class,
            MemoryMcpServer.class,
            EclipseCodeEditingMcpServer.class
    };
    
    public List<McpServerDescriptor> listBuiltInImplementations()
    {
        return Stream.of( BUILT_IN_MCP_SERVERS )
                      .map( this::toBuiltInMcpServerDescriptor )
                      .collect( Collectors.toList() );        
    }
    
    private McpServerDescriptor toBuiltInMcpServerDescriptor( Class<?> clazz )
    {
        String serverName = clazz.getAnnotation( McpServer.class ).name();
        return new McpServerDescriptor( serverName, 
                serverName, 
                "", 
                Collections.emptyList(),
                true, 
                true );
    }

    public Class<?> findImplementation( String name )
    {
        Objects.requireNonNull( name );
        return Stream.of( BUILT_IN_MCP_SERVERS )
                     .filter( clazz -> clazz.getAnnotation( McpServer.class ).name().equals( name ) )
                     .findAny()
                     .orElseThrow( () -> new IllegalArgumentException( "No implementation for name: " + name  ) );
        
    }
}
