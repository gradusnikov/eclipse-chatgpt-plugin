package com.github.gradusnikov.eclipse.assistai.mcp;

import java.util.List;
import java.util.Objects;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.widgets.Display;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants;
import com.github.gradusnikov.eclipse.assistai.preferences.mcp.McpServerDescriptorUtilities;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class McpServerRepository
{
    private final ILog logger;
    private final McpServerBuiltins mcpBuiltins;
    

    @Inject
    public McpServerRepository(McpServerBuiltins mcpServerBuiltins,  ILog logger)
    {
        Objects.requireNonNull( mcpServerBuiltins );
        Objects.requireNonNull( logger );
        this.mcpBuiltins = mcpServerBuiltins;
        this.logger = logger;
    }
    
    public IPreferenceStore getPreferenceStore()
    {
        return  Activator.getDefault().getPreferenceStore();
    }
    
    /**
     * Retrieves all defined MCP servers from preferences.
     *
     * @return A list of MCP server descriptors.
     */
    public List<McpServerDescriptor> listStoredServers()
    {
        String serversJson = getPreferenceStore().getString( PreferenceConstants.ASSISTAI_DEFINED_MCP_SERVERS );
        return McpServerDescriptorUtilities.fromJson( serversJson );
    }

    public List<McpServerDescriptor> listBuiltInServers()
    {
        return mcpBuiltins.listBuiltInImplementations();
    }

    public Class<?> findImplementation( String name )
    {
        return mcpBuiltins.findImplementation( name );
    }
    
    public Object makeImplementation( String name )
    {
        var clazz = findImplementation( name );
        var implementation =  Activator.getDefault().make( clazz );
        Objects.requireNonNull( implementation, "No actual object of class " + clazz + " found!" );
        return implementation;
        
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
        getPreferenceStore().setValue( PreferenceConstants.ASSISTAI_DEFINED_MCP_SERVERS, json );
        logger.info( "MCP Servers Updated" );
    }

    public void setToDefault()
    {
        getPreferenceStore().setToDefault( PreferenceConstants.ASSISTAI_DEFINED_MCP_SERVERS );
        logger.info( "MCP Servers re-set to defaults" );
        
    }
}
