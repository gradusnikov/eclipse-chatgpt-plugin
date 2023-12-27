package com.github.gradusnikov.eclipse.assistai.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import com.github.gradusnikov.eclipse.assistai.Activator;
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
        store.setDefault(PreferenceConstants.OPENAI_API_BASE, "https://api.openai.com");
        store.setDefault(PreferenceConstants.OPENAI_API_END_POINT, "/v1/chat/completions");
        store.setDefault( PreferenceConstants.OPENAI_API_KEY, "" );
        store.setDefault( PreferenceConstants.OPENAI_MODEL_NAME, "gpt-4" );
        
        PromptLoader promptLoader = new PromptLoader();
        for ( Prompts prompt : Prompts.values() )
        {
            store.setDefault( prompt.preferenceName(), promptLoader.getRawPrompt( prompt.getFileName() ) );
        }
    }

}
