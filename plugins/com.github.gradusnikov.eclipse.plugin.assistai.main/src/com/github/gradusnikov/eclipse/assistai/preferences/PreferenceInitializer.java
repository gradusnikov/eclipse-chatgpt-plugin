package com.github.gradusnikov.eclipse.assistai.preferences;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.DuckDuckSearchMcpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.EclipseIntegrationsMcpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.McpServerBuiltins;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.ReadWebPageMcpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.TimeMcpServer;
import com.github.gradusnikov.eclipse.assistai.model.McpServerDescriptor;
import com.github.gradusnikov.eclipse.assistai.model.McpServerDescriptor.EnvironmentVariable;
import com.github.gradusnikov.eclipse.assistai.model.ModelApiDescriptor;
import com.github.gradusnikov.eclipse.assistai.prompt.PromptLoader;
import com.github.gradusnikov.eclipse.assistai.prompt.Prompts;

/**
 * Class used to initialize default preference values.
 */
public class PreferenceInitializer extends AbstractPreferenceInitializer
{

    public void initializeDefaultPreferences()
    {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        store.setDefault( PreferenceConstants.ASSISTAI_CONNECTION_TIMEOUT_SECONDS, 10 );
        store.setDefault( PreferenceConstants.ASSISTAI_REQUEST_TIMEOUT_SECONDS, 30 );
        
        ModelApiDescriptor gpt4 = new ModelApiDescriptor( "1", "openai", "https://api.openai.com/v1/chat/completions", "", "gpt-4o", 7, true, true );
        ModelApiDescriptor claude = new ModelApiDescriptor( "2", "openai", "https://api.anthropic.com/v1/chat/completions", "", "qwen-qwq-32b", 7, false, true );
        ModelApiDescriptor groq = new ModelApiDescriptor( "2", "openai", "https://api.groq.com/openai/v1/chat/completions", "", "claude-3-7-sonnet-20250219", 7, true, true );
        
        
        String modelsJson = ModelApiDescriptorUtilities.toJson( gpt4, claude, groq );
        store.setDefault( PreferenceConstants.ASSISTAI_SELECTED_MODEL, gpt4.uid() );
        store.setDefault( PreferenceConstants.ASSISTAI_DEFINED_MODELS, modelsJson );

        
        var descriptors = McpServerBuiltins.listBuiltInImplementations();
        
        // Initialize MCP server descriptors
        McpServerDescriptor exampleServer = new McpServerDescriptor(
                UUID.randomUUID().toString(),
                "server-filesystem",
                "wsl.exe npx -y @modelcontextprotocol/server-filesystem ${project_loc}",
                Arrays.asList(),
                true,
                false
        );
        descriptors.add( exampleServer );
        
        String mcpServersJson = McpServerDescriptorUtilities.toJson( descriptors );
        store.setDefault(PreferenceConstants.ASSISTAI_DEFINED_MCP_SERVERS, mcpServersJson);

        
        PromptLoader promptLoader = new PromptLoader();
        for ( Prompts prompt : Prompts.values() )
        {
            store.setDefault( prompt.preferenceName(), promptLoader.getDefaultPrompt( prompt.getFileName() ) );
        }
    }
    

}
