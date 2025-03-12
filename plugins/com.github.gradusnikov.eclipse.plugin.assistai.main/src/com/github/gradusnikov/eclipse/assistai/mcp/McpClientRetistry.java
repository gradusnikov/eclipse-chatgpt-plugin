package com.github.gradusnikov.eclipse.assistai.mcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.workbench.lifecycle.PostWorkbenchClose;
import org.eclipse.jface.preference.IPreferenceStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.McpClientServerFactory.InMemorySyncClientServer;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.DuckDuckSearchMcpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.EclipseIntegrationsMcpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.McpServerBuiltins;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.ReadWebPageMcpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.TimeMcpServer;
import com.github.gradusnikov.eclipse.assistai.model.McpServerDescriptor;
import com.github.gradusnikov.eclipse.assistai.preferences.McpServerDescriptorUtilities;
import com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants;
import com.google.common.base.Predicates;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.ClientMcpTransport;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class McpClientRetistry
{
    private IPreferenceStore preferenceStore;
    
    private Map<String, McpSyncClient> clients = new HashMap<>();
    
    @Inject
    ILog logger;
    
    private  List<McpSyncServer> servers = new ArrayList<>();
    
    @Inject
    McpClientServerFactory factory;

    @Inject
    IEclipseContext eclipseContext;

    
//    @Inject
//    DuckDuckSearchMcpServer duckDuckSearchMcpServer;
//    
//    @Inject
//    ReadWebPageMcpServer webPageMcpServer;
//    
//    @Inject
//    EclipseIntegrationsMcpServer eclipseIntegrationsMcpServer;
//    
//    @Inject
//    TimeMcpServer timeMcpServer;
    
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
        
        
        // user defined servers
        var stored  = getStorredServers();
        // built-in servers
        var builtin = McpServerBuiltins.listBuiltInImplementations();
        
        for ( McpServerDescriptor builtInServerDescriptor : builtin )
        {
            var updated = stored.stream()
                                .filter( other -> builtInServerDescriptor.uid().equals( other.uid() ) )
                                .findAny()
                                .orElse( builtInServerDescriptor );
            
            if ( updated.enabled() )
            {
                Class<?> clazz = McpServerBuiltins.findImplementation( updated.name() );
                System.out.println( ">> " + clazz );
                // inject object
                Object   implementation = ContextInjectionFactory.make(clazz, eclipseContext);
                Objects.requireNonNull( implementation, "No actual object of class " + clazz + " found!" );
                
                InMemorySyncClientServer clientServerPair; 
                clientServerPair = factory.creteInMemorySyncClientServer( implementation );
                addClient( updated.name(), clientServerPair.client() );
                servers.add( clientServerPair.server() );
            }
        }
        
        var userDefined = stored.stream()
                                .filter( Predicates.not( McpServerDescriptor::builtIn ) )
                                .filter( McpServerDescriptor::enabled )
                                .collect( Collectors.toList() );
        
        for ( McpServerDescriptor userMcp : userDefined )
        {
            List<String> commandParts = new ArrayList<>();
            Matcher matcher = Pattern.compile("([^\"]\\S*|\".+?\")\\s*").matcher(userMcp.command());
            while (matcher.find()) {
                commandParts.add(matcher.group(1).replace("\"", ""));
            }

            String executable = commandParts.get(0);
            String[] args = commandParts.subList(1, commandParts.size()).toArray(new String[0]);

            ServerParameters stdioParameters = ServerParameters.builder(executable)
                    .args(args)
                    .env(userMcp.environmentVariables().stream()
                        .collect(Collectors.toMap(
                            McpServerDescriptor.EnvironmentVariable::name,
                            McpServerDescriptor.EnvironmentVariable::value)))
                    .build();
            ClientMcpTransport mcpTransport = new StdioClientTransport( stdioParameters );
            McpSyncClient client = McpClient.sync( mcpTransport ).build();
            addClient( userMcp.name(), client );
        }

        // initialize clients
        clients.values().parallelStream().forEach( McpSyncClient::initialize );
        
        logger.info( "Assist AI MCPs initialized" );
    }
    
    /**
     * Get all defined MCP servers
     * 
     * @return list of MCP server descriptors
     */
    public List<McpServerDescriptor> getStorredServers()
    {
        String serversJson = preferenceStore.getString( PreferenceConstants.ASSISTAI_DEFINED_MCP_SERVERS );
        return McpServerDescriptorUtilities.fromJson( serversJson );
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
