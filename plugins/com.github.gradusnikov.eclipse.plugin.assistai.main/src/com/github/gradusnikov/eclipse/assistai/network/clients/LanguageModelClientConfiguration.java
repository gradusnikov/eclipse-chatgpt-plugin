package com.github.gradusnikov.eclipse.assistai.network.clients;

import java.util.Optional;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.preference.IPreferenceStore;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants;
import com.github.gradusnikov.eclipse.assistai.preferences.models.ModelApiDescriptor;
import com.github.gradusnikov.eclipse.assistai.repository.ModelApiDescriptorRepository;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class LanguageModelClientConfiguration 
{
    private final ModelApiDescriptorRepository repository;
    
    @Inject
    public LanguageModelClientConfiguration( ModelApiDescriptorRepository repository )
    {
        this.repository = repository;
    }
    
    public Optional<ModelApiDescriptor> getSelectedModel()
    {
        return Optional.ofNullable( repository.getModelInUse() );
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
