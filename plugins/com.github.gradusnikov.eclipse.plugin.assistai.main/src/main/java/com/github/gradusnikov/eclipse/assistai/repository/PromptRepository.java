package com.github.gradusnikov.eclipse.assistai.repository;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

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
    
    private IPreferenceStore preferenceStore;
    
    @PostConstruct
    public void init()
    {
        preferenceStore = Activator.getDefault().getPreferenceStore();
    }
    
    public String getPrompt( String key )
    {
        try
        {
            var prompt = Prompts.valueOf( key );
            return preferenceStore.getString( prompt.preferenceName() );
        }
        catch ( Exception e )
        {
            if ( preferenceStore.contains( key ) )
            {
                return preferenceStore.getString( key );
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
        preferenceStore.setValue( key, promptText );
    }

    public String getPromptByIndex( int index )
    {
        var prompt = preferenceStore.getString( getPreferenceName( index ) );
        return prompt;
    }
    
    private String getPreferenceName( int index )
    {
        return Prompts.values()[index].preferenceName();
    }

    public void save( int selectedIndex, String text )
    {
        preferenceStore.setValue( getPreferenceName( selectedIndex ), text );
    }

    public String resetToDefault( int selectedIndex )
    {
        var propertyName = getPreferenceName( selectedIndex );
        var defaultValue = preferenceStore.getDefaultString( propertyName );
        preferenceStore.setValue( propertyName, defaultValue );
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

