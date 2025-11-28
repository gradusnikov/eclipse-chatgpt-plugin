package com.github.gradusnikov.eclipse.assistai.mcp;

import java.util.List;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.preference.IPreferenceStore;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants;
import com.github.gradusnikov.eclipse.assistai.preferences.mcp.McpServerDescriptorUtilities;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class McpServerRepository
{
    private IPreferenceStore           preferenceStore;

    
    @PostConstruct
    public void init()
    {
        preferenceStore = Activator.getDefault().getPreferenceStore();
    }
    
    /**
     * Retrieves all defined MCP servers from preferences.
     *
     * @return A list of MCP server descriptors.
     */
    public List<McpServerDescriptor> listStoredServers()
    {
        String serversJson = preferenceStore.getString( PreferenceConstants.ASSISTAI_DEFINED_MCP_SERVERS );
        return McpServerDescriptorUtilities.fromJson( serversJson );
    }
}
