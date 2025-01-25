package com.keg.eclipseaiassistant.services;

import jakarta.inject.Singleton;

import java.util.Optional;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.preference.IPreferenceStore;

import com.keg.eclipseaiassistant.Activator;
import com.keg.eclipseaiassistant.model.ModelApiDescriptor;
import com.keg.eclipseaiassistant.preferences.ModelApiDescriptorUtilities;
import com.keg.eclipseaiassistant.preferences.PreferenceConstants;

@Creatable
@Singleton
public class OpenAIClientConfiguration 
{

    
    public Optional<ModelApiDescriptor> getSelectedModel()
    {
        IPreferenceStore prefernceStore = Activator.getDefault().getPreferenceStore();
        var selected = prefernceStore.getString( PreferenceConstants.ASSISTAI_SELECTED_MODEL );
        var modelsJson = prefernceStore.getString( PreferenceConstants.ASSISTAI_DEFINED_MODELS );
        var models =  ModelApiDescriptorUtilities.fromJson( modelsJson );
        
        return models.stream().filter( model -> model.uid().equals( selected ) ).findFirst();
    }
    
    public int getConnectionTimoutSeconds()
    {
        IPreferenceStore prefernceStore = Activator.getDefault().getPreferenceStore();
        return Integer.parseInt( prefernceStore.getString(PreferenceConstants.ASSISTAI_CONNECTION_TIMEOUT_SECONDS) );
        
    }
    
    public int getRequestTimoutSeconds()
    {
        IPreferenceStore prefernceStore = Activator.getDefault().getPreferenceStore();
        return Integer.parseInt( prefernceStore.getString(PreferenceConstants.ASSISTAI_REQUEST_TIMEOUT_SECONDS) );
        
    }
    
}
