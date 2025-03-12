
package com.github.gradusnikov.eclipse.assistai.mcp;

import java.util.*;
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
    }

    /**
     * Initializes the MCP clients and servers. This method is called after the
     * construction of the object.
     */
    @PostConstruct
    public void init()
    {
        logger.info( "Initializing MCPs" );
        preferenceStore = Activator.getDefault().getPreferenceStore();

        var stored = getStoredServers();
        var builtin = McpServerBuiltins.listBuiltInImplementations();

        initializeBuiltInServers( stored, builtin );
        initializeUserDefinedServers( stored );

        clients.values().parallelStream().forEach( McpSyncClient::initialize );
        logger.info( "Assist AI MCPs initialized" );
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
    private void initializeUserDefinedServers( List<McpServerDescriptor> stored )
    {
        var userDefined = stored.stream()
                                .filter( Predicates.not( McpServerDescriptor::builtIn ) )
                                .filter( McpServerDescriptor::enabled )
                                .collect( Collectors.toList() );

        for ( var userMcp : userDefined )
        {
            var commandParts = parseCommand( userMcp.command() );

            String executable = commandParts.get( 0 );
            String[] args = commandParts.subList( 1, commandParts.size() ).toArray( new String[0] );

            ServerParameters stdioParameters = ServerParameters.builder( executable )
                    .args( args )
                    .env( userMcp.environmentVariables().stream()
                            .collect( Collectors.toMap( McpServerDescriptor.EnvironmentVariable::name, 
                                                        McpServerDescriptor.EnvironmentVariable::value ) ) )
                    .build();

            ClientMcpTransport mcpTransport = new StdioClientTransport( stdioParameters );
            McpSyncClient client = McpClient.sync( mcpTransport ).build();
            addClient( userMcp.name(), client );
        }
    }

    /**
     * Parses a command string into a list of command parts.
     *
     * @param command
     *            The command string to parse.
     * @return A list of command parts.
     */
    private List<String> parseCommand( String command )
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

    /**
     * Finds a tool by client name.
     *
     * @param clientName
     *            The name of the client.
     * @param name
     *            The name of the tool.
     * @return An optional containing the MCP sync client if found.
     */
    public Optional<McpSyncClient> findTool( String clientName, String name )
    {
        return Optional.ofNullable( clients.get( clientName ) );
    }
}
