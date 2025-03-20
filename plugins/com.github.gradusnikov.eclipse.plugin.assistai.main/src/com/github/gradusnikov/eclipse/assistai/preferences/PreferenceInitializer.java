package com.github.gradusnikov.eclipse.assistai.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.McpServerBuiltins;
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
        

        ModelApiDescriptor gpt4 = new ModelApiDescriptor(   "5e8d3a9f-c5e2-4c1d-9f3b-a7e6b4d2c1e0", "openai", "https://api.openai.com/v1/chat/completions", "", "gpt-4o", 7, true, true );
        ModelApiDescriptor claude = new ModelApiDescriptor( "7b9a2e5d-f8c1-4a3b-9d7e-6c5f4a3b2d1c", "claude", "https://api.anthropic.com/v1/chat/completions", "", "claude-3-7-sonnet-20250219", 7, true, true );
        ModelApiDescriptor groq = new ModelApiDescriptor(   "9c4d7e8f-a1b2-3c4d-5e6f-7a8b9c0d1e2f", "groq", "https://api.groq.com/openai/v1/chat/completions", "", "qwen-qwq-32b", 7, false, true );
        
        
        String modelsJson = ModelApiDescriptorUtilities.toJson( gpt4, claude, groq );
        store.setDefault( PreferenceConstants.ASSISTAI_SELECTED_MODEL, gpt4.uid() );
        store.setDefault( PreferenceConstants.ASSISTAI_DEFINED_MODELS, modelsJson );

        
        var descriptors = McpServerBuiltins.listBuiltInImplementations();
        
        String mcpServersJson = McpServerDescriptorUtilities.toJson( descriptors );
        store.setDefault(PreferenceConstants.ASSISTAI_DEFINED_MCP_SERVERS, mcpServersJson);

        PromptLoader promptLoader = new PromptLoader();
        for ( Prompts prompt : Prompts.values() )
        {
            store.setDefault( prompt.preferenceName(), promptLoader.getDefaultPrompt( prompt.getFileName() ) );
        }
    }
    

}
