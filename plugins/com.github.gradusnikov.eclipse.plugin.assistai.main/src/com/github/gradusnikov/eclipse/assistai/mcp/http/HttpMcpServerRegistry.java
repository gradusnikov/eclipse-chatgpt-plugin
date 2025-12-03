package com.github.gradusnikov.eclipse.assistai.mcp.http;


import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.workbench.lifecycle.PostWorkbenchClose;

import com.github.gradusnikov.eclipse.assistai.mcp.McpServerDescriptor;
import com.github.gradusnikov.eclipse.assistai.mcp.McpServerFactory;
import com.github.gradusnikov.eclipse.assistai.mcp.McpServerRepository;

import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.Servlet;

@Creatable
@Singleton
public class HttpMcpServerRegistry
{
    
    private static String MCP_ENDPOINT = "/mcp";
    
    private final HttpMcpServerPreferencesProvider httpServerPreferncesProvider;
    private final McpServerRepository mcpServerRepository;
    private final McpServerFactory mcpServerFactory;
    private final ILog logger;
    
    private final List<McpSyncServer> servers;
    private final ArrayList<String> endpoints;

    private Tomcat tomcat;
    private McpJsonMapperSupplier jsonMapperSupplier;

    
    @Inject
    public HttpMcpServerRegistry( HttpMcpServerPreferencesProvider serverPreferncesProvider,
                                  McpServerRepository mcpServerRepository, 
                                  McpServerFactory mcpServerFactory, 
                                  ILog logger )
    {
        Objects.requireNonNull( serverPreferncesProvider );
        Objects.requireNonNull( mcpServerRepository );
        Objects.requireNonNull( mcpServerFactory );
        Objects.requireNonNull( logger );
        this.httpServerPreferncesProvider = serverPreferncesProvider;
        this.mcpServerFactory = mcpServerFactory;
        this.mcpServerRepository = mcpServerRepository;
        this.logger = logger;
        
        this.servers = new ArrayList<>();
        this.endpoints = new ArrayList<>();
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
        logger.info( "Initializing MCP Http Server." );
        servers.clear();
        endpoints.clear();
        // Create Tomcat and ONE context upfront
        tomcat = createTomcatServer();
        
        String baseDir = System.getProperty("java.io.tmpdir");
        Context context = tomcat.addContext("", baseDir);  // Create context once
        
        var builtin = mcpServerRepository.listBuiltInServers();
        var stored  = mcpServerRepository.listStoredServers();
        initializeBuiltInServers(context, stored, builtin);  // Pass context

        restart();
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

        // Track the endpoint
        endpoints.add(serverName);
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
    
    public List<String> listEndpoints()
    {
        var config = httpServerPreferncesProvider.get();
        String baseUrl = "http://" + config.hostname() + ":" + config.port();
        
        return endpoints.stream()
                .map(name -> baseUrl + "/mcp/" + name)
                .toList();
    }

    
    private Tomcat createTomcatServer()
    {
        // Disable Tomcat's URL stream handler factory to avoid conflicts with OSGi
        System.setProperty("tomcat.util.buf.StringCache.byte.enabled", "true");
        org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
        var tomcat = new Tomcat();
        tomcat.setPort(httpServerPreferncesProvider.get().port());
        tomcat.setHostname(httpServerPreferncesProvider.get().hostname());
        
        String baseDir = System.getProperty("java.io.tmpdir");
        tomcat.setBaseDir(baseDir);

        var connector = tomcat.getConnector();
        connector.setAsyncTimeout(3000);

        return tomcat;
    }

    public boolean isRunning()
    {
        return LifecycleState.STARTED.equals( tomcat.getServer().getState() );
    }

    public void restart()
    {
        try
        {
            if ( isRunning() )
            {
                logger.info( "Stopping MCP Http Server." );
                tomcat.stop();
                logger.info( "MCP Http Server state: " + tomcat.getServer().getState() + " ." );
            }
        }
        catch ( LifecycleException e )
        {
            logger.error( "Error stopping Tomcat server: " + e.getMessage(), e );
            throw new RuntimeException( e );
        }
        if ( httpServerPreferncesProvider.isEnabled() )
        {
            try
            {
                logger.info( "Starting MCP Http Server." );
                tomcat.start();
                logger.info( "MCP Http Server state: " + tomcat.getServer().getState() + " @" + tomcat.getServer().getAddress() + ":" + tomcat.getServer().getPort() );
                logger.info( "MCP Http Server endpoints:\n " + listEndpoints().stream().collect( Collectors.joining("\n") ) );
            }
            catch ( LifecycleException e )
            {
                logger.error( "Error starting MCP Http Server: " + e.getMessage(), e );
            }
        }

    }
    
    
}
