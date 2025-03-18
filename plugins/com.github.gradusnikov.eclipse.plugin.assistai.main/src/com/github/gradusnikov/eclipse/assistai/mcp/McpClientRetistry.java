
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


import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISelectionService;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.ResourceUtil;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;


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
            String resolvedCommand = resolveEclipseVariables(userMcp.command());
            
            var commandParts = parseCommand(resolvedCommand);
    
            String executable = commandParts.get(0);
            String[] args = commandParts.subList(1, commandParts.size()).toArray(new String[0]);
    
            // Also resolve variables in environment variables
            Map<String, String> resolvedEnvVars = userMcp.environmentVariables().stream()
                    .collect(Collectors.toMap(
                        McpServerDescriptor.EnvironmentVariable::name,
                        ev -> resolveEclipseVariables(ev.value())
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
     * Resolves Eclipse IDE variables in the given string and converts Windows paths to WSL paths if needed.
     * 
     * @param input The string containing Eclipse variables to resolve
     * @param isWsl Whether the command is being executed in WSL
     * @return The string with all variables replaced with their actual values
     */
    private String resolveEclipseVariables(String input) {
        if (input == null || input.isEmpty()) 
        {
            return input;
        }
        
        // Check if this is a WSL command
        boolean isWsl = input.trim().startsWith("wsl") || 
                        input.contains("\\wsl") || 
                        input.contains("/wsl");
        
        // Pattern to match Eclipse variables like ${workspace_loc}, ${project_loc}, etc.
        Pattern pattern = Pattern.compile("\\$\\{([^}]+)\\}");
        Matcher matcher = pattern.matcher(input);
        
        StringBuffer result = new StringBuffer();
        while (matcher.find()) 
        {
            String variable = matcher.group(1);
            String replacement = getVariableValue(variable);
            
            // Convert Windows path to WSL path if needed
            if (isWsl && replacement != null && !replacement.isEmpty()) 
            {
                replacement = convertToWslPath(replacement);
            }
            
            // Escape backslashes and dollar signs for the replacement
            replacement = replacement.replace("\\", "\\\\").replace("$", "\\$");
            matcher.appendReplacement(result, replacement);
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    /**
     * Converts a Windows path to a WSL path.
     * 
     * @param windowsPath The Windows path to convert
     * @return The equivalent WSL path
     */
    private String convertToWslPath(String windowsPath) 
    {
        if (windowsPath == null || windowsPath.isEmpty()) 
        {
            return windowsPath;
        }
        
        // Check if it's a valid Windows path
        if (windowsPath.length() > 1 && windowsPath.charAt(1) == ':') 
        {
            char driveLetter = Character.toLowerCase(windowsPath.charAt(0));
            String path = windowsPath.substring(2).replace('\\', '/');
            return "/mnt/" + driveLetter + path;
        }
        
        // If it's not a recognizable Windows path, return it unchanged
        return windowsPath;
    }

    
    /**
     * Gets the value for an Eclipse IDE variable.
     * 
     * @param variable The variable name (without ${})
     * @return The resolved value of the variable
     */
    private String getVariableValue(String variable) {
        return switch ( variable )
        {
            case "workspace_loc" ->  resolveWorkspaceLocation(variable);
            case "project_loc" -> resolveProjectLocation(variable);
            case "container_loc" -> resolveContainerLocation(variable);
            case "resource_loc" -> resolveResourceLocation(variable);
            default -> "${" + variable + "}";
                
        };
    }
    
    /**
     * Resolves workspace location variables like ${workspace_loc} or ${workspace_loc:/path}
     */
    private String resolveWorkspaceLocation(String variable) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        String location = root.getLocation().toOSString();
        
        // Check if there's a path after workspace_loc
        if (variable.length() > "workspace_loc".length()) 
        {
            String path = variable.substring("workspace_loc".length());
            if (path.startsWith(":")) 
            {
                path = path.substring(1); // Remove the colon
                IResource resource = root.findMember(path);
                if (resource != null) 
                {
                    return resource.getLocation().toOSString();
                }
            }
        }
        
        return location;
    }
    /**
     * Resolves project location variables like ${project_loc} or ${project_loc:/path}
     */
    private String resolveProjectLocation(String variable) 
    {
        IResource resource = getSelectedResource();
        if (resource == null) 
        {
            return "${" + variable + "}";
        }
        
        IProject project = resource.getProject();
        if (project == null) 
        {
            return "${" + variable + "}";
        }
        
        // Check if there's a path after project_loc
        if (variable.length() > "project_loc".length()) 
        {
            String path = variable.substring("project_loc".length());
            if (path.startsWith(":")) 
            {
                path = path.substring(1); // Remove the colon
                IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
                IResource pathResource = root.findMember(path);
                if (pathResource != null && pathResource.getProject() != null) 
                {
                    return pathResource.getProject().getLocation().toOSString();
                }
            }
        }
        
        return project.getLocation().toOSString();
    }

    /**
     * Resolves the container location (folder containing the selected resource)
     */
    private String resolveContainerLocation(String variable) {
        IResource resource = getSelectedResource();
        if (resource == null) 
        {
            return "${" + variable + "}";
        }
        
        IContainer container;
        if (resource instanceof IContainer) 
        {
            container = (IContainer) resource;
        }
        else 
        {
            container = resource.getParent();
        }
        
        if (container == null) 
        {
            return "${" + variable + "}";
        }
        
        return container.getLocation().toOSString();
    }

    /**
     * Resolves the selected resource location
     */
    private String resolveResourceLocation(String variable) {
        IResource resource = getSelectedResource();
        if (resource == null) 
        {
            return "${" + variable + "}";
        }
        
        return resource.getLocation().toOSString();
    }

    /**
     * Gets the currently selected resource in the workbench
     * 
     * @return The selected resource or null if none is selected
     */
    private IResource getSelectedResource()
    {
        try
        {
            // Try to get the resource from the active editor
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if ( window != null )
            {
                IWorkbenchPage page = window.getActivePage();
                if ( page != null )
                {
                    // First check active editor
                    IEditorPart editor = page.getActiveEditor();
                    if ( editor != null )
                    {
                        IEditorInput input = editor.getEditorInput();
                        IResource resource = ResourceUtil.getResource( input );
                        if ( resource != null )
                        {
                            return resource;
                        }
                    }

                    // Then check selection
                    ISelectionService selectionService = window.getSelectionService();
                    ISelection selection = selectionService.getSelection();
                    if ( selection instanceof IStructuredSelection )
                    {
                        IStructuredSelection structuredSelection = (IStructuredSelection) selection;
                        Object firstElement = structuredSelection.getFirstElement();
                        if ( firstElement instanceof IResource )
                        {
                            return (IResource) firstElement;
                        }
                    }
                }
            }
        }
        catch ( Exception e )
        {
            logger.error( "Error getting selected resource", e );
        }

        return null;
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

    public void restart()
    {
        handleShutdown();
        clients.clear();
        servers.clear();
        init();
    }
}
