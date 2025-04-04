package com.github.gradusnikov.eclipse.assistai.repository;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.preference.IPreferenceStore;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.prompt.Prompts;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class PromptRepository
{
    @Inject
    ILog logger;
    
    private IPreferenceStore prefrences;
    
    @PostConstruct
    public void init()
    {
        prefrences = Activator.getDefault().getPreferenceStore();
    }
    
    public String getPrompt( String key )
    {
        try
        {
            var prompt = Prompts.valueOf( key );
            return prefrences.getString( prompt.preferenceName() );
        }
        catch ( Exception e )
        {
            if ( prefrences.contains( key ) )
            {
                return prefrences.getString( key );
            }
            logger.error(e.getMessage(), e);
            return "";
        }
    }
    public void setDefaultValue( String key, String promptText )
    {
        
    }
    public void setPrompt( String key, String promptText )
    {
        prefrences.setValue( key, promptText );
    }
    
}
