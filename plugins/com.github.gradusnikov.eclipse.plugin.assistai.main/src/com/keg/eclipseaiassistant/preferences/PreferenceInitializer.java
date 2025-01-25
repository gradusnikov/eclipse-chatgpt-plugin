package com.keg.eclipseaiassistant.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import com.keg.eclipseaiassistant.Activator;
import com.keg.eclipseaiassistant.model.ModelApiDescriptor;
import com.keg.eclipseaiassistant.prompt.PromptLoader;
import com.keg.eclipseaiassistant.prompt.Prompts;

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
        
        ModelApiDescriptor myModel = new ModelApiDescriptor( "1", "codestral-22b-v0.1", "http://127.0.0.1:1234", "", "Codestral-22B-v0.1-Q4_K_M.gguf", 7, false, false );
        
        String modelsJson = ModelApiDescriptorUtilities.toJson( myModel );
        store.setDefault( PreferenceConstants.ASSISTAI_SELECTED_MODEL, myModel.uid() );
        store.setDefault( PreferenceConstants.ASSISTAI_DEFINED_MODELS, modelsJson );

        PromptLoader promptLoader = new PromptLoader();
        for ( Prompts prompt : Prompts.values() )
        {
            store.setDefault( prompt.preferenceName(), promptLoader.getDefaultPrompt( prompt.getFileName() ) );
        }
    }
}
