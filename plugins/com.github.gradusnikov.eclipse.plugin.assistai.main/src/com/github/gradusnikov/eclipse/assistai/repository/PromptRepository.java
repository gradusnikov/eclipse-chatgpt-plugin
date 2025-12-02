package com.github.gradusnikov.eclipse.assistai.repository;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.preference.IPreferenceStore;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.prompt.Prompts;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class PromptRepository
{
    @Inject
    ILog logger;
    
    @Inject
    public PromptRepository( ILog logger )
    {
        Objects.requireNonNull( logger );
        this.logger = logger;
    }
    
    public IPreferenceStore getPreferenceStore()
    {
        return Activator.getDefault().getPreferenceStore();
    }
    
    public String getPrompt( String key )
    {
        try
        {
            var prompt = Prompts.valueOf( key );
            return getPreferenceStore().getString( prompt.preferenceName() );
        }
        catch ( Exception e )
        {
            if ( getPreferenceStore().contains( key ) )
            {
                return getPreferenceStore().getString( key );
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
        getPreferenceStore().setValue( key, promptText );
    }

    public String getPromptByIndex( int index )
    {
        var prompt = getPreferenceStore().getString( getPreferenceName( index ) );
        return prompt;
    }
    
    private String getPreferenceName( int index )
    {
        return Prompts.values()[index].preferenceName();
    }

    public void save( int selectedIndex, String text )
    {
        getPreferenceStore().setValue( getPreferenceName( selectedIndex ), text );
    }

    public String resetToDefault( int selectedIndex )
    {
        var propertyName = getPreferenceName( selectedIndex );
        var defaultValue = getPreferenceStore().getDefaultString( propertyName );
        getPreferenceStore().setValue( propertyName, defaultValue );
        return defaultValue;
    }

    public List<String> findMatchingCommands( String group )
    {
        return Arrays.stream( Prompts.values() )
                     .map( Prompts::getCommandName )
                     .filter( commandName -> commandName.startsWith( group ) )
                     .toList();
    }
    public Optional<Prompts> findPromptByCommandName( String name )
    {
        return Arrays.stream( Prompts.values() )
                     .filter( prompt -> prompt.getCommandName().equals( name ) )
                     .findFirst();
    }
    public List<String> listCommands()
    {
        return Arrays.stream( Prompts.values() ).map( Prompts::getCommandName ).toList();
    }
    
    public List<Prompts> getAllPrompts()
    {
        return Arrays.stream( Prompts.values() ).toList();
    }
}

