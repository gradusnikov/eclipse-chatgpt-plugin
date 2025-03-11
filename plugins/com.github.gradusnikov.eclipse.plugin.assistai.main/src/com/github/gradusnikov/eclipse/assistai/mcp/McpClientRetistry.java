package com.github.gradusnikov.eclipse.assistai.mcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.workbench.lifecycle.PostWorkbenchClose;
import org.eclipse.jface.preference.IPreferenceStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.McpClientServerFactory.InMemorySyncClientServer;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.DuckDuckSearchMcpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.EclipseIntegrationsMcpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.ReadWebPageMcpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.TimeMcpServer;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.server.McpSyncServer;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class McpClientRetistry
{
    private IPreferenceStore preferenceStore;
    
    private Map<String, McpSyncClient> clients = new HashMap<>();
    private ObjectMapper objectMapper = new ObjectMapper();
    
    @Inject
    ILog logger;
    
    private  List<McpSyncServer> servers = new ArrayList<>();
    
    @Inject
    McpClientServerFactory factory;
    
    @Inject
    DuckDuckSearchMcpServer duckDuckSearchMcpServer;
    
    @Inject
    ReadWebPageMcpServer webPageMcpServer;
    
    @Inject
    EclipseIntegrationsMcpServer eclipseIntegrationsMcpServer;
    
    @Inject
    TimeMcpServer timeMcpServer;
    
    @PostWorkbenchClose
    public void handleShutdown()
    {
        clients.values().stream().forEach( McpSyncClient::closeGracefully );
    }
    
    @PostConstruct
    public void init()
    {
        logger.info( "Initializing MCPs" );
        preferenceStore = Activator.getDefault().getPreferenceStore();
        
        
        InMemorySyncClientServer clientServerPair; 
        clientServerPair = factory.creteInMemorySyncClientServer( duckDuckSearchMcpServer );
        addClient( "duckduck-search", clientServerPair.client() );
        servers.add( clientServerPair.server() );
        
        clientServerPair = factory.creteInMemorySyncClientServer( webPageMcpServer );
        addClient( "webpage-reader", clientServerPair.client() );
        servers.add( clientServerPair.server() );

        clientServerPair = factory.creteInMemorySyncClientServer( eclipseIntegrationsMcpServer );
        addClient( "eclipse-ide", clientServerPair.client() );
        servers.add( clientServerPair.server() );
        
        clientServerPair = factory.creteInMemorySyncClientServer( timeMcpServer );
        addClient( "time", clientServerPair.client() );
        servers.add( clientServerPair.server() );

        // initialize clients
        clients.values().parallelStream().forEach( McpSyncClient::initialize );
        
        logger.info( "Assist AI MCPs initialized" );
    }
    
//    private void initializeEverythingServer()
//    {
//        ServerParameters stdioParams = ServerParameters.builder("wsl.exe")
//                .args("npx", "-y", "@modelcontextprotocol/server-everything", "dir")
//                .build();
//        ClientMcpTransport transport = new StdioClientTransport( stdioParams );
//        McpSyncClient client = McpClient.sync( transport ).build();
//        client.initialize();
//        addClient( "everything", client );
//    }
//    private void initializeMemory()
//    {
//        ServerParameters stdioParams = ServerParameters.builder("wsl.exe")
//                .args("npx", "-y", "@modelcontextprotocol/server-memory")
//                .addEnvVar( "MEMORY_FILE_PATH", "/tmp/memory.json" )
//                .build();
//        ClientMcpTransport transport = new StdioClientTransport( stdioParams );
//        McpSyncClient client = McpClient.sync( transport ).build();
//        client.initialize();
//        addClient( "memory", client );
//    }
    
    public void addClient( String name, McpSyncClient client )
    {
        clients.put( name, client );
    }
    
    public Map<String, McpSyncClient> listClients()
    {
        return clients;
    }
    public Optional<McpSyncClient> findTool(String clientName, String name)
    {
        return Optional.ofNullable( clients.get( clientName ) );
    }
    
    
}
