package com.github.gradusnikov.eclipse.assistai.preferences.mcp;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.ILog;
import org.eclipse.jface.preference.IPreferenceStore;

import com.github.gradusnikov.eclipse.assistai.mcp.McpClientRetistry;
import com.github.gradusnikov.eclipse.assistai.mcp.McpServerDescriptor;
import com.github.gradusnikov.eclipse.assistai.mcp.McpServerDescriptor.McpServerDescriptorWithStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.McpServerDescriptor.Status;
import com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants;

/**
 * Presenter for MCP Server preferences
 */
public class McpServerPreferencePresenter
{

    private static final int MCP_SERVER_PING_TIMEOUT_SECONDS = 1;
    private final IPreferenceStore  preferenceStore;
    private final McpClientRetistry clientRetistry;
    private final ILog logger;
    
    private McpServerPreferencePage view;

    public McpServerPreferencePresenter( IPreferenceStore preferenceStore, 
                                         McpClientRetistry mcpClientRetistry,
                                         ILog logger
                                         )
    {
        this.preferenceStore = preferenceStore;
        this.clientRetistry = mcpClientRetistry;
        this.logger = logger;
    }


    /**
     * Get all defined MCP servers
     * 
     * @return list of MCP server descriptors
     */
    public List<McpServerDescriptor> getServers()
    {
        String serversJson = preferenceStore.getString( PreferenceConstants.ASSISTAI_DEFINED_MCP_SERVERS );
        return McpServerDescriptorUtilities.fromJson( serversJson );
    }
    
    /**
     * Get all defined MCP servers
     * 
     * @return list of MCP server descriptors
     */
    public List<McpServerDescriptorWithStatus> getServersWithStatus() {
        var servers = getServers();
        var list = servers.stream().map(server -> {
            try 
            {
                var client = clientRetistry.listClients().get(server.name());
                Objects.requireNonNull(server.name(), "Failed to ping MCP server: " + server.name());
                var result = CompletableFuture.supplyAsync(client::ping)
                                              .get(MCP_SERVER_PING_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                Objects.requireNonNull(result, "Failed to ping MCP server: " + server.name());
                return new McpServerDescriptorWithStatus(server, Status.RUNNING);
            }
            catch (TimeoutException e) 
            {
                logger.error("Ping to MCP server timed out: " + server.name());
                return new McpServerDescriptorWithStatus(server, Status.FAILED);
            }
            catch (Exception e) 
            {
                logger.error("Failed to connect to MCP server: " + e.getMessage());
                return new McpServerDescriptorWithStatus(server, Status.FAILED);
            }
        }).collect(Collectors.toList());
        return list;
    }

    /**
     * Get a specific MCP server by index
     * 
     * @param index
     *            the index of the server
     * @return optional containing the server or empty if not found
     */
    public Optional<McpServerDescriptor> getServerAt( int index )
    {
        var servers = getServers();
        return index >= 0 && index < servers.size() ? Optional.of( servers.get( index ) ) : Optional.empty();
    }

    /**
     * Add a new MCP server
     */
    public void addServer()
    {
        view.clearServerSelection();
        view.clearServerDetails();
        view.setDetailsEditable( true );
    }

    /**
     * Toggle the enabled state of a server
     * 
     * @param serverIndex
     *            the index of the server
     * @param enabled
     *            the new enabled state
     */
    public void toggleServerEnabled( int serverIndex, boolean enabled )
    {
        var servers = getServers();
        if ( serverIndex >= 0 && serverIndex < servers.size() )
        {
            McpServerDescriptor server = servers.get( serverIndex );
            McpServerDescriptor updated = new McpServerDescriptor( server.uid(), 
                                                                   server.name(), 
                                                                   server.command(), 
                                                                   server.environmentVariables(), 
                                                                   enabled, 
                                                                   server.builtIn() );
            servers.set( serverIndex, updated );
            save( servers );
//            view.showServers( getServersWithStatus() );
        }
    }

    /**
     * Remove a server
     * 
     * @param selectedIndex
     *            the index of the server to remove
     */
    public void removeServer( int selectedIndex )
    {
        var servers = getServers();
        if ( selectedIndex >= 0 && selectedIndex < servers.size() )
        {
            McpServerDescriptor server = servers.get( selectedIndex );
            if ( !server.builtIn() )
            {
                servers.remove( selectedIndex );
                save( servers );
                clientRetistry.restart();
                view.showServers( getServersWithStatus() );
                view.clearServerDetails();
                view.setDetailsEditable( false );
            }
        }
    }

    /**
     * Save the list of servers
     * 
     * @param servers
     *            the servers to save
     */
    public void save( List<McpServerDescriptor> servers )
    {
        String json = McpServerDescriptorUtilities.toJson( servers );
        preferenceStore.setValue( PreferenceConstants.ASSISTAI_DEFINED_MCP_SERVERS, json );
        logger.info( "updated" );
    }

    /**
     * Save a server
     * 
     * @param selectedIndex
     *            the index of the server to save or -1 to add a new one
     * @param updatedServerStub
     *            the updated server data
     */
    public void saveServer( int selectedIndex, McpServerDescriptor updatedServerStub )
    {
        List<McpServerDescriptor> storedDescriptors = getServers();

        // Check for duplicate name
        boolean nameExists = storedDescriptors.stream()
                .filter( server -> !server.uid()
                        .equals( selectedIndex >= 0 && selectedIndex < storedDescriptors.size() ? storedDescriptors.get( selectedIndex ).uid() : "" ) )
                .anyMatch( server -> server.name().equals( updatedServerStub.name() ) );

        if ( nameExists )
        {
            view.showError( "Server name must be unique" );
            return;
        }

        String uid;
        Consumer<McpServerDescriptor> update;

        if ( selectedIndex >= 0 && selectedIndex < storedDescriptors.size() )
        {
            uid = storedDescriptors.get( selectedIndex ).uid();
            update = server -> storedDescriptors.set( selectedIndex, server );
        }
        else
        {
            uid = UUID.randomUUID().toString();
            update = server -> storedDescriptors.add( server );
        }

        McpServerDescriptor toStore = new McpServerDescriptor( uid, 
                                                               updatedServerStub.name(), 
                                                               updatedServerStub.command(),
                                                               updatedServerStub.environmentVariables(), 
                                                               updatedServerStub.enabled(), 
                                                               false );

        update.accept( toStore );
        save( storedDescriptors );
        clientRetistry.restart();
        view.showServers( getServersWithStatus() );
        view.clearServerDetails();
    }

    /**
     * Set the selected server
     * 
     * @param selectedIndex
     *            the index of the server to select
     */
    public void setSelectedServer( int selectedIndex )
    {
        var servers = getServers();
        if ( selectedIndex >= 0 && selectedIndex < servers.size() )
        {
            var selected = servers.get( selectedIndex );
            view.showServerDetails( selected );
            view.setDetailsEditable( !selected.builtIn() );
            view.setRemoveEditable( !selected.builtIn() );
        }
        else
        {
            view.clearServerDetails();
            view.setDetailsEditable( false );
        }
    }

    /**
     * Register the view
     * 
     * @param mcpServerPreferencePage
     *            the view to register
     */
    public void registerView( McpServerPreferencePage mcpServerPreferencePage )
    {
        view = mcpServerPreferencePage;
        view.showServers( getServersWithStatus() );
        view.setDetailsEditable( false );
    }

    /**
     * Reset to default values
     */
    public void onPerformDefaults()
    {
        preferenceStore.setToDefault( PreferenceConstants.ASSISTAI_DEFINED_MCP_SERVERS );
        clientRetistry.restart();
        view.showServers( getServersWithStatus() );
        view.clearServerDetails();
        view.setDetailsEditable( false );
    }
}