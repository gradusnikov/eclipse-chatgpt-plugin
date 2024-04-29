package com.github.gradusnikov.eclipse.assistai.preferences;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.eclipse.jface.preference.IPreferenceStore;

import com.github.gradusnikov.eclipse.assistai.model.ModelApiDescriptor;


public class ModelListPreferencePresenter
{

    public enum FORM_STATE {
            IDLE,
            ADDING_NEW,
            EDIT
    };
    
    private final IPreferenceStore preferenceStore;
    private ModelListPreferencePage view;
    
    public ModelListPreferencePresenter( IPreferenceStore preferenceStore )
    {
        this.preferenceStore = preferenceStore;
    }
    
    public List<ModelApiDescriptor> getModels()
    {
        String modelsJson = preferenceStore.getString( PreferenceConstants.ASSISTAI_DEFINED_MODELS );
        List<ModelApiDescriptor> models =  ModelApiDescriptorUtilities.fromJson( modelsJson );
        return models;
    }
    
    public Optional<ModelApiDescriptor> getModelAt( int index )
    {
        var models = getModels();
        return index >= 0 && index < models.size() ? Optional.of(models.get( index )) : Optional.empty();
    }
    
    public void addModel( ModelApiDescriptor model )
    {
        view.clearModelSelection();
        view.clearModelDetails();
        view.setDetailsEditable( true );
    }
    
    public void removeModel( int selectedIndex )
    {
        var models = getModels();
        if ( selectedIndex >= 0 && selectedIndex < models.size() )
        {
            models.remove( selectedIndex );
            save( models );
            view.showModels( models );
            view.clearModelDetails();
        }
    }
    
    public void save( List<ModelApiDescriptor> models )
    {
        String json = ModelApiDescriptorUtilities.toJson( models );
        preferenceStore.setValue( PreferenceConstants.ASSISTAI_DEFINED_MODELS, json );
    }

    public void saveModel( int selectedIndex, ModelApiDescriptor updatedModelStub )    
    {
        List<ModelApiDescriptor> storedDescriptors  = getModels();
        String uid;
        Consumer<ModelApiDescriptor> update; 
        if ( selectedIndex >= 0 && selectedIndex < storedDescriptors.size() )
        {
            uid = storedDescriptors.get( selectedIndex ).uid();
            update = model -> storedDescriptors.set( selectedIndex, model );
        }
        else
        {
            uid = UUID.randomUUID().toString();
            update = model -> storedDescriptors.add( model );
        }
        ModelApiDescriptor toStore  = new ModelApiDescriptor( 
                uid, 
                updatedModelStub.apiType(), 
                updatedModelStub.apiUrl(),
                updatedModelStub.apiKey(), 
                updatedModelStub.modelName(), 
                updatedModelStub.temperature(), 
                updatedModelStub.vision(),
                updatedModelStub.functionCalling()
                 );
        update.accept( toStore );
        save( storedDescriptors );
        view.showModels( getModels() );
        view.clearModelDetails();
    }

    public void setSelectedModel( int selectedIndex )
    {
        var models = getModels();
        if ( selectedIndex >= 0 && selectedIndex < models.size() )
        {
            view.showModelDetails( models.get( selectedIndex ) );
        }
        else
        {
            view.clearModelDetails();
        }
    }

    public void registerView( ModelListPreferencePage modelListPreferencePage )
    {
        view = modelListPreferencePage; 
        view.showModels( getModels() );
    }

    public void onPerformDefaults()
    {
        preferenceStore.setToDefault( PreferenceConstants.ASSISTAI_DEFINED_MODELS );
        view.showModels( getModels() );
        view.clearModelDetails();
    }
    
    
    
}
