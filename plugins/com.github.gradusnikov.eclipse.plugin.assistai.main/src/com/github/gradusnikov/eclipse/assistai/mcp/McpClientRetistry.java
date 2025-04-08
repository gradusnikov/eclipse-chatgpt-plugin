
package com.github.gradusnikov.eclipse.assistai.mcp;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.workbench.lifecycle.PostWorkbenchClose;
import org.eclipse.jface.preference.IPreferenceStore;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.McpClientServerFactory.InMemorySyncClientServer;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.McpServerBuiltins;
import com.github.gradusnikov.eclipse.assistai.preferences.McpServerDescriptorUtilities;
import com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants;
import com.github.gradusnikov.eclipse.assistai.tools.EclipseVariableUtilities;
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

    private IPreferenceStore           preferenceStore;

    private Map<String, McpSyncClient> clients = new HashMap<>();

    private List<McpSyncServer>        servers = new ArrayList<>();

    @Inject
    private ILog                       logger;

    @Inject
    private McpClientServerFactory     factory;

    @Inject
    private IEclipseContext            eclipseContext;

    /**
     * Handles the shutdown process by closing all MCP clients gracefully.
     */
    @PostWorkbenchClose
    public void handleShutdown()
    {
        clients.values().forEach( McpSyncClient::closeGracefully );
        servers.forEach( McpSyncServer::closeGracefully );
    }

    /**
     * Initializes the MCP clients and servers. This method is called after the
     * construction of the object.
     */
    @PostConstruct
    public void init()
    {
        preferenceStore = Activator.getDefault().getPreferenceStore();

        var stored = getStoredServers();
        var builtin = McpServerBuiltins.listBuiltInImplementations();

        initializeBuiltInServers( stored, builtin );
        initializeUserDefinedServers( stored );

        clients.entrySet().stream().forEach( this::gracefullyInitialize );
    }
    
    private void gracefullyInitialize( Map.Entry<String, McpSyncClient> client )
    {
        try
        {
            logger.info( "Initializing MCP client: " + client.getKey()  );
            CompletableFuture.supplyAsync( () -> client.getValue().initialize() )
                             .get( 3, TimeUnit.SECONDS );
            logger.info( "Sucessfully initialized MCP client: " + client.getKey()  );
        }
        catch ( InterruptedException | ExecutionException | TimeoutException e )
        {
            logger.error( "Failed to initialize MCP client: " + client.getKey()  );
        }
    }
    
    /**
     * Initializes built-in MCP servers.
     *
     * @param stored
     *            List of stored server descriptors.
     * @param builtin
     *            List of built-in server descriptors.
     */
    private void initializeBuiltInServers( List<McpServerDescriptor> stored, List<McpServerDescriptor> builtin )
    {
        for ( McpServerDescriptor builtInServerDescriptor : builtin )
        {
            McpServerDescriptor updated = stored.stream()
                                                .filter( other -> builtInServerDescriptor.uid().equals( other.uid() ) )
                                                .findAny()
                                                .orElse( builtInServerDescriptor );

            if ( updated.enabled() )
            {
                var clazz = McpServerBuiltins.findImplementation( updated.name() );
                var implementation = ContextInjectionFactory.make( clazz, eclipseContext );
                Objects.requireNonNull( implementation, "No actual object of class " + clazz + " found!" );

                InMemorySyncClientServer  clientServerPair = factory.creteInMemorySyncClientServer( implementation );
                addClient( updated.name(), clientServerPair.client() );
                servers.add( clientServerPair.server() );
            }
        }
    }

    /**
     * Initializes user-defined MCP servers.
     *
     * @param stored
     *            List of stored server descriptors.
     */

    private void initializeUserDefinedServers(List<McpServerDescriptor> stored) {
        var userDefined = stored.stream()
                                .filter(Predicates.not(McpServerDescriptor::builtIn))
                                .filter(McpServerDescriptor::enabled)
                                .collect(Collectors.toList());
    
        for (var userMcp : userDefined)
        {
            // Replace variables in the command string
            String resolvedCommand = EclipseVariableUtilities.resolveEclipseVariables(userMcp.command());
            
            var commandParts = parseCommand(resolvedCommand);
    
            String executable = commandParts.get(0);
            String[] args = commandParts.subList(1, commandParts.size()).toArray(new String[0]);
    
            // Also resolve variables in environment variables
            Map<String, String> resolvedEnvVars = userMcp.environmentVariables().stream()
                    .collect(Collectors.toMap(
                        McpServerDescriptor.EnvironmentVariable::name,
                        ev -> EclipseVariableUtilities.resolveEclipseVariables(ev.value())
                    ));
    
            ServerParameters stdioParameters = ServerParameters.builder(executable)
                    .args(args)
                    .env(resolvedEnvVars)
                    .build();
    
            ClientMcpTransport mcpTransport = new StdioClientTransport(stdioParameters);
            McpSyncClient client = McpClient.sync(mcpTransport).build();
            addClient(userMcp.name(), client);
        }
    }

    

    /**
	 * Parses a command string into a list of command parts.
	 *
	 * @param command
	 *            The command string to parse.
	 * @return A list of command parts.
	 */
	private static List<String> parseCommand( String command )
	{
	    List<String> commandParts = new ArrayList<>();
	    Matcher matcher = Pattern.compile( "([^\"]\\S*|\".+?\")\\s*" ).matcher( command );
	    while ( matcher.find() )
	    {
	        commandParts.add( matcher.group( 1 ).replace( "\"", "" ) );
	    }
	    return commandParts;
	}

	/**
     * Retrieves all defined MCP servers from preferences.
     *
     * @return A list of MCP server descriptors.
     */
    public List<McpServerDescriptor> getStoredServers()
    {
        String serversJson = preferenceStore.getString( PreferenceConstants.ASSISTAI_DEFINED_MCP_SERVERS );
        return McpServerDescriptorUtilities.fromJson( serversJson );
    }

    /**
     * Adds a client to the registry.
     *
     * @param name
     *            The name of the client.
     * @param client
     *            The MCP sync client to add.
     */
    public void addClient( String name, McpSyncClient client )
    {
        clients.put( name, client );
    }

    /**
     * Lists all registered MCP clients.
     *
     * @return A map of client names to MCP sync clients.
     */
    public Map<String, McpSyncClient> listClients()
    {
        return clients;
    }
    
    public Map<String, McpSyncClient> listEnabledveClients()
    {
    	// map server name to its enabled status
    	Map<String, Boolean> enabled =  getStoredServers().stream()
    													  .collect( Collectors.toMap(McpServerDescriptor::name, McpServerDescriptor::enabled));
    	// return only enabled
    	return clients.entrySet().stream()
    				  			 .filter( e -> enabled.getOrDefault(e.getKey(), Boolean.FALSE ).booleanValue() )
    				  			 .collect(Collectors.toMap( Map.Entry::getKey, Map.Entry::getValue) );
    }

    /**
     * Finds a tool by client name.
     *
     * @param clientName
     *            The name of the client.
     * @return An optional containing the MCP sync client if found.
     */
    public Optional<McpSyncClient> findClient( String clientName )
    {
        return Optional.ofNullable( clients.get( clientName ) );
    }

    public void restart()
    {
        handleShutdown();
        clients.clear();
        servers.clear();
        init();
    }

    
}
