package com.github.gradusnikov.eclipse.assistai.preferences.models;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.models.ModelApiDescriptor;
import com.github.gradusnikov.eclipse.assistai.models.ModelApiDescriptorRepository;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;


@Creatable
@Singleton
public class ModelListPreferencePresenter
{

    private final ModelApiDescriptorRepository repository;
    
    private ModelListPreferencePage view;
    
    @Inject
    public ModelListPreferencePresenter( ModelApiDescriptorRepository repository )
    {
        this.repository = repository;
    }
    
    
    public void addModel()
    {
        view.clearModelSelection();
        view.clearModelDetails();
        view.setDetailsEditable( true );
    }
    
    public void removeModel( int selectedIndex )
    {
    	repository.findByIndex(selectedIndex)
    			  .ifPresent(
    					  selected -> {
    						  repository.delete( selected);
    				          view.showModels( repository.listModelApiDescriptors() );
    				          view.clearModelDetails();
    					  } );
    }
    
    public void saveModel( int selectedIndex, ModelApiDescriptor updatedModelStub )    
    {
        repository.save( selectedIndex, updatedModelStub );
    	view.showModels( repository.listModelApiDescriptors() );
        view.clearModelDetails();
    }

    public void setSelectedModel( int selectedIndex )
    {
    	repository.findByIndex(selectedIndex)
    			  .ifPresentOrElse( selected -> view.showModelDetails(selected),
    					  			() -> view.clearModelDetails() );
    }

    public void registerView( ModelListPreferencePage modelListPreferencePage )
    {
        view = modelListPreferencePage; 
        view.showModels( repository.listModelApiDescriptors() );
    }

    public void onPerformDefaults()
    {
        view.showModels( repository.setToDefault() );
        view.clearModelDetails();
    }
}
