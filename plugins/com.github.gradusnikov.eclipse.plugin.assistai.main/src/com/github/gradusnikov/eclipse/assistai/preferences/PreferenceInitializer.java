package com.github.gradusnikov.eclipse.assistai.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import com.github.gradusnikov.eclipse.assistai.Activator;
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
        
        ModelApiDescriptor gpt4 = new ModelApiDescriptor( "1", "openai", "https://api.openai.com/v1/chat/completions", "", "gpt-4-turbo", 7, true, true );
        ModelApiDescriptor gpt35 = new ModelApiDescriptor( "2", "openai", "https://api.openai.com/v1/chat/completions", "", "gpt-3.5-turbo", 7, true, true );
        String modelsJson = ModelApiDescriptorUtilities.toJson( gpt4, gpt35 );
        store.setDefault( PreferenceConstants.ASSISTAI_SELECTED_MODEL, gpt4.uid() );
        store.setDefault( PreferenceConstants.ASSISTAI_DEFINED_MODELS, modelsJson );

        PromptLoader promptLoader = new PromptLoader();
        for ( Prompts prompt : Prompts.values() )
        {
            store.setDefault( prompt.preferenceName(), promptLoader.getRawPrompt( prompt.getFileName() ) );
        }
    }
}
