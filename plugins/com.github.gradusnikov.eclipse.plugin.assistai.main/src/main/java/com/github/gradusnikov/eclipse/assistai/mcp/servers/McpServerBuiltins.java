package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.gradusnikov.eclipse.assistai.mcp.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.McpServerDescriptor;

public class McpServerBuiltins
{
    
    public static final Class<?>[] BUILT_IN_MCP_SERVERS = {
            EclipseIntegrationsMcpServer.class,
            DuckDuckSearchMcpServer.class,
            TimeMcpServer.class,
            ReadWebPageMcpServer.class,
            MemoryMcpServer.class,
            EclipseCodeEditingMcpServer.class
    };
    
    public static List<McpServerDescriptor> listBuiltInImplementations()
    {
        return Stream.of( BUILT_IN_MCP_SERVERS )
                      .map( McpServerBuiltins::toBuiltInMcpServerDescriptor )
                      .collect( Collectors.toList() );        
    }
    
    private static McpServerDescriptor toBuiltInMcpServerDescriptor( Class<?> clazz )
    {
        String serverName = clazz.getAnnotation( McpServer.class ).name();
        return new McpServerDescriptor( serverName, 
                serverName, 
                "", 
                Collections.emptyList(),
                true, 
                true );
    }

    public static Class<?> findImplementation( String name )
    {
        Objects.requireNonNull( name );
        return Stream.of( BUILT_IN_MCP_SERVERS )
                     .filter( clazz -> clazz.getAnnotation( McpServer.class ).name().equals( name ) )
                     .findAny()
                     .orElseThrow( () -> new IllegalArgumentException( "No implementation for name: " + name  ) );
        
    }
}
