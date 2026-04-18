package com.github.gradusnikov.eclipse.assistai.preferences.mcp;

import java.util.ArrayList;
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
import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.mcp.McpServerDescriptor;
import com.github.gradusnikov.eclipse.assistai.mcp.McpServerDescriptor.McpServerDescriptorWithStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.McpServerDescriptor.Status;
import com.github.gradusnikov.eclipse.assistai.mcp.http.HttpMcpServerRegistry;
import com.github.gradusnikov.eclipse.assistai.mcp.local.InMemoryMcpClientRetistry;
import com.github.gradusnikov.eclipse.assistai.mcp.McpServerRepository;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Presenter for MCP Server preferences
 */
@Creatable
@Singleton
public class McpServerPreferencePresenter
{

    private static final int MCP_SERVER_PING_TIMEOUT_SECONDS = 1;
    private final InMemoryMcpClientRetistry clientRetistry;
    private final HttpMcpServerRegistry httpMcpServerRegistry;
    private final McpServerRepository mcpServerRepository;
    private final ILog logger;
    
    private McpServerPreferencePage view;

    @Inject
    public McpServerPreferencePresenter( InMemoryMcpClientRetistry mcpClientRetistry,
                                         HttpMcpServerRegistry httpMcpServerRegistry,
                                         McpServerRepository mcpServerRepository,
                                         ILog logger
                                         )
    {
        Objects.requireNonNull( mcpClientRetistry );
        Objects.requireNonNull( httpMcpServerRegistry );
        Objects.requireNonNull( mcpServerRepository );
        Objects.requireNonNull( logger );
        
        this.clientRetistry = mcpClientRetistry;
        this.httpMcpServerRegistry = httpMcpServerRegistry;
        this.mcpServerRepository = mcpServerRepository;
        this.logger = logger;
    }
    
    /**
     * Get all defined MCP servers
     * 
     * @return list of MCP server descriptors
     */
    public List<McpServerDescriptorWithStatus> getServersWithStatus() {
        var servers = mcpServerRepository.listStoredServers();
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
        var servers = mcpServerRepository.listStoredServers();
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
        var servers = mcpServerRepository.listStoredServers();
        if ( serverIndex >= 0 && serverIndex < servers.size() )
        {
            McpServerDescriptor server = servers.get( serverIndex );
            McpServerDescriptor updated = new McpServerDescriptor( server.uid(), 
                                                                   server.name(), 
                                                                   server.command(), 
                                                                   server.environmentVariables(), 
                                                                   enabled, 
                                                                   server.builtIn(),
                                                                   server.excludedTools() );
            servers.set( serverIndex, updated );
            mcpServerRepository.save( servers );
            restartServers();
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
        var servers = mcpServerRepository.listStoredServers();
        if ( selectedIndex >= 0 && selectedIndex < servers.size() )
        {
            McpServerDescriptor server = servers.get( selectedIndex );
            if ( !server.builtIn() )
            {
                servers.remove( selectedIndex );
                mcpServerRepository.save( servers );
                restartServers();
                view.showServers( getServersWithStatus() );
                view.clearServerDetails();
                view.setDetailsEditable( false );
            }
        }
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
        List<McpServerDescriptor> storedDescriptors = mcpServerRepository.listStoredServers();

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
                                                               false,
                                                               updatedServerStub.excludedTools() );

        update.accept( toStore );
        mcpServerRepository.save( storedDescriptors );
        restartServers();
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
        var servers = mcpServerRepository.listStoredServers();
        if ( selectedIndex >= 0 && selectedIndex < servers.size() )
        {
            var selected = servers.get( selectedIndex );
            view.showServerDetails( selected );
            view.setDetailsEditable( !selected.builtIn() );
            view.setRemoveEditable( !selected.builtIn() );
            var allTools = mcpServerRepository.listToolsForServer( selected.name() );
            view.showToolList( allTools, selected.excludedTools() );
        }
        else
        {
            view.clearServerDetails();
            view.setDetailsEditable( false );
        }
    }

    public void toggleToolEnabled( int serverIndex, String toolName, boolean enabled )
    {
        var servers = mcpServerRepository.listStoredServers();
        if ( serverIndex >= 0 && serverIndex < servers.size() )
        {
            McpServerDescriptor server = servers.get( serverIndex );
            List<String> excludedTools = new ArrayList<>( server.excludedTools() );
            if ( enabled )
            {
                excludedTools.remove( toolName );
            }
            else
            {
                if ( !excludedTools.contains( toolName ) )
                {
                    excludedTools.add( toolName );
                }
            }
            McpServerDescriptor updated = new McpServerDescriptor( server.uid(),
                    server.name(), server.command(), server.environmentVariables(),
                    server.enabled(), server.builtIn(), excludedTools );
            servers.set( serverIndex, updated );
            mcpServerRepository.save( servers );
            restartServers();
        }
    }

    private void restartServers()
    {
        clientRetistry.restart();
        httpMcpServerRegistry.restart();
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
        mcpServerRepository.setToDefault();
        restartServers();
        view.showServers( getServersWithStatus() );
        view.clearServerDetails();
        view.setDetailsEditable( false );
    }
}
