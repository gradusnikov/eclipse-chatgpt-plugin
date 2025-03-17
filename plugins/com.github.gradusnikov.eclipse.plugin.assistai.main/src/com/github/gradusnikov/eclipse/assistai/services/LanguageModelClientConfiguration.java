package com.github.gradusnikov.eclipse.assistai.services;

import jakarta.inject.Singleton;

import java.util.Optional;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.preference.IPreferenceStore;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.model.ModelApiDescriptor;
import com.github.gradusnikov.eclipse.assistai.preferences.ModelApiDescriptorUtilities;
import com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants;

@Creatable
@Singleton
public class LanguageModelClientConfiguration 
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
