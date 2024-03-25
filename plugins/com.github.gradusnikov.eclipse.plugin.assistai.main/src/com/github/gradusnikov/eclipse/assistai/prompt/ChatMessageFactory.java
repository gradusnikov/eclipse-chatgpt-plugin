package com.github.gradusnikov.eclipse.assistai.prompt;

import java.util.UUID;
import java.util.function.Supplier;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.preference.IPreferenceStore;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.handlers.Context;
import com.github.gradusnikov.eclipse.assistai.model.ChatMessage;

@Creatable
@Singleton
public class ChatMessageFactory
{
	private IPreferenceStore preferenceStore;
    
	@Inject
    private PromptLoader promptLoader;

    public ChatMessageFactory()
    {
        
    }
    @PostConstruct
    public void init()
    {
        preferenceStore = Activator.getDefault().getPreferenceStore();
    }

    public ChatMessage createAssistantChatMessage( String text )
    {
        ChatMessage message = new ChatMessage( UUID.randomUUID().toString(), "assistant" );
        message.setContent( text );
        return message;

    }
    
    public ChatMessage createUserChatMessage( Prompts type, Context context )
    {
        Supplier<String> promptSupplier =
            switch ( type )
            {
                case DOCUMENT   -> javaDocPromptSupplier( context );
                case TEST_CASE  -> unitTestSupplier( context );
                case REFACTOR   -> refactorPromptSupplier( context );
                case DISCUSS    -> discussCodePromptSupplier( context );
                case FIX_ERRORS -> fixErrorsPromptSupplier( context );
                default ->
                    throw new IllegalArgumentException();
            };
        return createUserChatMessage( promptSupplier );
    }
    
    private Supplier<String> fixErrorsPromptSupplier( Context context )
    {
        return () -> promptLoader.updatePromptText( preferenceStore.getString( Prompts.FIX_ERRORS.preferenceName() ), 
                "${documentText}", context.fileContents(),
                "${fileName}", context.fileName(),
                "${lang}", context.lang(),
                "${errors}", context.selectedContent()
                );
    }

    private Supplier<String> discussCodePromptSupplier( Context context )
    {
        return () -> promptLoader.updatePromptText( preferenceStore.getString( Prompts.DISCUSS.preferenceName() ), 
                "${documentText}", context.fileContents(),
                "${fileName}", context.fileName(),
                "${lang}", context.lang()
                );
    }

    private Supplier<String> javaDocPromptSupplier( Context context )
    {
        return () -> promptLoader.updatePromptText( preferenceStore.getString( Prompts.DOCUMENT.preferenceName() ), 
                    "${documentText}", context.fileContents(),
                    "${javaType}", context.selectedItemType(),
                    "${name}", context.selectedItem(),
                    "${lang}", context.lang()
                    );
    }
    private Supplier<String> refactorPromptSupplier( Context context )
    {
        return () -> promptLoader.updatePromptText( preferenceStore.getString( Prompts.REFACTOR.preferenceName() ), 
                "${documentText}", context.fileContents(),
                "${selectedText}", context.selectedContent(),
                "${fileName}", context.fileName(),
                "${lang}", context.lang()
                );
    }
    private Supplier<String> unitTestSupplier( Context context )
    {
        return () -> promptLoader.updatePromptText( preferenceStore.getString( Prompts.TEST_CASE.preferenceName() ), 
                "${documentText}", context.fileContents(),
                "${javaType}", context.selectedItemType(),
                "${name}", context.selectedItem(),
                "${lang}", context.lang()
                );
    }

    
    public ChatMessage createGenerateGitCommitCommentJob( String patch )
    {
        Supplier<String> promptSupplier  =  () -> promptLoader.updatePromptText( preferenceStore.getString( Prompts.GIT_COMMENT.preferenceName() ), 
                "${content}", patch );
        
        return createUserChatMessage( promptSupplier );
    }
    
    public ChatMessage createUserChatMessage( Supplier<String> promptSupplier )
    {
        ChatMessage message = new ChatMessage( UUID.randomUUID().toString(), "user" );
        message.setContent( promptSupplier.get() );
        return message;        
    }



}
