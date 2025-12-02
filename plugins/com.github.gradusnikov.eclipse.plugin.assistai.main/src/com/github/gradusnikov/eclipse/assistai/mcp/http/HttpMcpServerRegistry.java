package com.github.gradusnikov.eclipse.assistai.mcp.http;


import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.workbench.lifecycle.PostWorkbenchClose;

import com.github.gradusnikov.eclipse.assistai.mcp.McpServerDescriptor;
import com.github.gradusnikov.eclipse.assistai.mcp.McpServerFactory;
import com.github.gradusnikov.eclipse.assistai.mcp.McpServerRepository;
import com.google.inject.Singleton;

import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.servlet.Servlet;

@Creatable
@Singleton
public class HttpMcpServerRegistry
{
    
    private static String MCP_ENDPOINT = "/mcp";
    private static int PORT = 8123;
    private static String HOST = "0.0.0.0";
    
    private final McpServerRepository mcpServerRepository;
    private final McpServerFactory mcpServerFactory;
    private final ILog logger;
    
    private final List<McpSyncServer> servers;

    private Tomcat tomcat;
    private McpJsonMapperSupplier jsonMapperSupplier;
    
    @Inject
    public HttpMcpServerRegistry( McpServerRepository mcpServerRepository, McpServerFactory mcpServerFactory, ILog logger )
    {
        Objects.requireNonNull( mcpServerRepository );
        Objects.requireNonNull( mcpServerFactory );
        Objects.requireNonNull( logger );
        this.mcpServerFactory = mcpServerFactory;
        this.mcpServerRepository = mcpServerRepository;
        this.logger = logger;
        
        this.servers = new ArrayList<>();
        this.jsonMapperSupplier = new JacksonMcpJsonMapperSupplier();

    }
    
    /**
     * Handles the shutdown process by closing all MCP clients gracefully.
     */
    @PostWorkbenchClose
    public void handleShutdown()
    {
        servers.forEach( McpSyncServer::closeGracefully );
        try
        {
            tomcat.stop();
        }
        catch ( LifecycleException e )
        {
            logger.error( "Tomcat server failed to stop: " + e.getMessage(), e );
        }
    }

    @PostConstruct
    public void init()
    {
        // Create Tomcat and ONE context upfront
        tomcat = createTomcatServer();
        
        String baseDir = System.getProperty("java.io.tmpdir");
        Context context = tomcat.addContext("", baseDir);  // Create context once
        
        var builtin = mcpServerRepository.listBuiltInServers();
        var stored  = mcpServerRepository.listStoredServers();
        initializeBuiltInServers(context, stored, builtin);  // Pass context
    
        try
        {
            tomcat.start();
            logger.error( "MCP Http Server Started." );
        }
        catch ( LifecycleException e )
        {
            logger.error( "Error starting Tomcat server: " + e.getMessage(), e );
        }
    }
    
    private void initializeBuiltInServers(Context context, List<McpServerDescriptor> stored, List<McpServerDescriptor> builtin )
    {
        for ( McpServerDescriptor builtInServerDescriptor : builtin )
        {
            McpServerDescriptor updated = stored.stream()
                                                .filter( other -> builtInServerDescriptor.uid().equals( other.uid() ) )
                                                .findAny()
                                                .orElse( builtInServerDescriptor );
    
            if ( updated.enabled() )
            {
                var implementation = mcpServerRepository.makeImplementation( updated.name() );
                var transportProvider = createStreamableHttpTransportProvider( updated.name() );
                var server = mcpServerFactory.createSyncServer( implementation, transportProvider );
                servers.add( server );
                addServlet(context, updated.name(), transportProvider);  // Pass context and name
            }
        }
    }
    
    private void addServlet(Context context, String serverName, Servlet servlet)
    {
        // Add transport servlet to the shared context
        var wrapper = context.createWrapper();
        wrapper.setName("mcpServlet_" + serverName);  // Unique name per server
        wrapper.setServlet(servlet);
        wrapper.setLoadOnStartup(1);
        wrapper.setAsyncSupported(true);
        context.addChild(wrapper);
        context.addServletMappingDecoded("/mcp/" + serverName + "/*", "mcpServlet_" + serverName);
    }

    private HttpServletStreamableServerTransportProvider createStreamableHttpTransportProvider( String name )
    {
        var transportProvider = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapperSupplier.get())
//                .keepAliveInterval(Duration.ofSeconds(10))
                .mcpEndpoint(MCP_ENDPOINT + "/" + name )
                .build();
        return transportProvider;
    }
    
    
    private Tomcat createTomcatServer()
    {
        // Disable Tomcat's URL stream handler factory to avoid conflicts with OSGi
        System.setProperty("tomcat.util.buf.StringCache.byte.enabled", "true");
        org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
        
        var tomcat = new Tomcat();
        tomcat.setPort(PORT);
        tomcat.setHostname(HOST);

        String baseDir = System.getProperty("java.io.tmpdir");
        tomcat.setBaseDir(baseDir);

        var connector = tomcat.getConnector();
        connector.setAsyncTimeout(3000);

        return tomcat;
    }
    
    
}
