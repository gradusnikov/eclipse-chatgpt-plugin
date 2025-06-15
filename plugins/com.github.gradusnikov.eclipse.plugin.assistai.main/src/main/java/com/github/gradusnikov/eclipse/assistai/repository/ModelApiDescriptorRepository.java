package com.github.gradusnikov.eclipse.assistai.repository;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.preference.IPreferenceStore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants;
import com.github.gradusnikov.eclipse.assistai.preferences.models.ModelApiDescriptor;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

@Creatable
public class ModelApiDescriptorRepository 
{
	@Inject
	ILog logger; 

	private IPreferenceStore preferenceStore;
	
	public ModelApiDescriptorRepository()
	{
		
	}
	@PostConstruct
	public void init()
	{
		preferenceStore = Activator.getDefault().getPreferenceStore();
	}
	
	public List<ModelApiDescriptor> listModelApiDescriptors()
	{
        String modelsJson = preferenceStore.getString( PreferenceConstants.ASSISTAI_DEFINED_MODELS );
        List<ModelApiDescriptor> models =  fromJson( modelsJson );
        return models;
	}
	
	public Optional<ModelApiDescriptor> findById( String modelId )
	{
		return listModelApiDescriptors().stream().filter( model -> model.uid().equals(modelId) ).findAny();
	}

	public int indexOf( String modelId )
	{
		var models = listModelApiDescriptors();
		for ( int i = 0; i < models.size(); i++ )
		{
			if ( models.get(i).uid().equals(modelId) )
			{
				return i;
			}
		}
		return -1;
	}

	public ModelApiDescriptor save( int selectedIndex, ModelApiDescriptor updatedModelStub )
	{
		List<ModelApiDescriptor> storedDescriptors  = listModelApiDescriptors();
		
        String uid;
        Consumer<ModelApiDescriptor> addOrReplace; 
        if ( selectedIndex >= 0 && selectedIndex < storedDescriptors.size() )
        {
            uid = storedDescriptors.get( selectedIndex ).uid();
            addOrReplace = model -> storedDescriptors.set( selectedIndex, model );
        }
        else
        {
            uid = UUID.randomUUID().toString();
            addOrReplace = model -> storedDescriptors.add( model );
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
        addOrReplace.accept( toStore );
        
        // replace preferences
        saveAll(storedDescriptors);
        return toStore;
	}
	
	public List<ModelApiDescriptor> setToDefault() 
	{
        preferenceStore.setToDefault( PreferenceConstants.ASSISTAI_DEFINED_MODELS );
        return listModelApiDescriptors();
	}
	public Optional<ModelApiDescriptor> findByIndex(int selectedIndex) 
	{
		var models = listModelApiDescriptors();
		if ( selectedIndex >= 0 && selectedIndex < models.size() )
		{
			return Optional.of( models.get(selectedIndex) );
		}
		return Optional.empty();
	}
	public void delete(ModelApiDescriptor selected) 
	{
		var models = listModelApiDescriptors();
		models.remove(selected);
		saveAll(models);
	}
	
	private void saveAll( List<ModelApiDescriptor> models )
	{
        String json = toJson( models );
        preferenceStore.setValue( PreferenceConstants.ASSISTAI_DEFINED_MODELS, json );
        logger.info( "AI models updated" );
	}
	
	public ModelApiDescriptor getModelInUse()
	{
        String currentModel = preferenceStore.getString( PreferenceConstants.ASSISTAI_SELECTED_MODEL );
        var models = listModelApiDescriptors();
        return models.isEmpty() 
        			? null 
        			: findById( currentModel ).orElse( listModelApiDescriptors().getFirst() );
		
	}
	public ModelApiDescriptor setModelInUse(String modelId) 
	{
		preferenceStore.setValue(PreferenceConstants.ASSISTAI_SELECTED_MODEL, modelId);
		return getModelInUse();
	}
	
	
    public static String toJson( ModelApiDescriptor ... descriptors )
    {
        var list = Arrays.asList( descriptors );
        return toJson( list );
    }

    public static List<ModelApiDescriptor> fromJson( String json )
    {
        ObjectMapper mapper = new ObjectMapper();
        try
        {
            List<ModelApiDescriptor> values = mapper.readValue( json.getBytes(), new TypeReference<List<ModelApiDescriptor>>()
            {
            } );
            return values;
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }
    }

    public static String toJson( List<ModelApiDescriptor> list )
    {
        ObjectMapper mapper = new ObjectMapper();
        try
        {
            return mapper.writeValueAsString( list );
        }
        catch ( JsonProcessingException e )
        {
            throw new RuntimeException( e );
        }
    }
    public void initializeDefaultDescriptors( ModelApiDescriptor ...apiDescriptors )
    {
        var modelsJson = toJson( apiDescriptors );
        preferenceStore.setDefault( PreferenceConstants.ASSISTAI_DEFINED_MODELS, modelsJson );
        
    }
    public void initializeDefaultDescriptorInUse( ModelApiDescriptor descriptor )
    {
        preferenceStore.setDefault( PreferenceConstants.ASSISTAI_SELECTED_MODEL, descriptor.uid() );
    }

}
