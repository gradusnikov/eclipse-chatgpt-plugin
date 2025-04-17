package com.github.gradusnikov.eclipse.assistai.preferences.prompts;

import java.util.Arrays;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.prompt.Prompts;
import com.github.gradusnikov.eclipse.assistai.repository.PromptRepository;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class PromptsPreferencePresenter
{
    private PromptsPreferencePage view;
    private PromptRepository promptRepository;
    
    @Inject
    public PromptsPreferencePresenter( PromptRepository promptRepository )
    {
        this.promptRepository = promptRepository;
    }
    
    public void registerView( PromptsPreferencePage view )
    {
        this.view = view;
        initializeView();
    }
    
    private void initializeView()
    {
        String[] prompts = Arrays.stream( Prompts.values() ).map( Prompts::getDescription ).toArray( String[]::new );
        view.setPrompts( prompts );
    }
    
    public void setSelectedPrompt( int index )
    {
        if ( index < 0 )
        {
            view.setCurrentPrompt( "" );
        }
        else
        {
            var prompt = promptRepository.getPromptByIndex( index ); 
            view.setCurrentPrompt( prompt );
        }
    }

    public void savePrompt( int selectedIndex, String text )
    {
        promptRepository.save( selectedIndex, text );
    }

    public void resetPrompt( int selectedIndex )
    {
        var defaultValue = promptRepository.resetToDefault( selectedIndex );
        view.setCurrentPrompt( defaultValue );
    }

}
