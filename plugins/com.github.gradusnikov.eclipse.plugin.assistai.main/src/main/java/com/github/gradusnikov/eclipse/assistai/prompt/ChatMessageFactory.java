package com.github.gradusnikov.eclipse.assistai.prompt;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.chat.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.repository.PromptRepository;

import codingagent.models.Prompts;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class ChatMessageFactory
{
    
	@Inject
	private PromptContextValueProvider contextValues;
	
	@Inject
	private PromptRepository promptRepository;
	
    public ChatMessageFactory()
    {
        
    }
    public ChatMessage createAssistantChatMessage( String text )
    {
        ChatMessage message = new ChatMessage( UUID.randomUUID().toString(), "assistant" );
        message.setContent( text );
        return message;

    }
    
    public ChatMessage createUserChatMessage( Prompts type)
    {
        Supplier<String> promptSupplier =
            switch ( type )
            {
                case DOCUMENT   -> javaDocPromptSupplier();
                case TEST_CASE  -> unitTestSupplier();
                case REFACTOR   -> refactorPromptSupplier(); 
                case DISCUSS    -> discussCodePromptSupplier();
                case FIX_ERRORS -> fixErrorsPromptSupplier( );
                default ->
                    throw new IllegalArgumentException();
            };
        return createUserChatMessage( promptSupplier );
    }
    
    private Supplier<String> fixErrorsPromptSupplier( )
    {
        return () -> promptRepository.getPrompt( Prompts.FIX_ERRORS.name() );
    }

    private Supplier<String> discussCodePromptSupplier()
    {
        return () -> promptRepository.getPrompt( Prompts.DISCUSS.name() );
    }

    private Supplier<String> javaDocPromptSupplier()
    {
        return () -> promptRepository.getPrompt( Prompts.DOCUMENT.name() );
    }
    private Supplier<String> refactorPromptSupplier()
    {
        return () -> promptRepository.getPrompt( Prompts.REFACTOR.name() );
    }
    private Supplier<String> unitTestSupplier( )
    {
        return () -> promptRepository.getPrompt( Prompts.TEST_CASE.name() );
    }

    
    public ChatMessage createGenerateGitCommitCommentJob( )
    {
        Supplier<String> promptSupplier  =  () -> promptRepository.getPrompt( Prompts.GIT_COMMENT.name() );
        
        return createUserChatMessage( promptSupplier );
    }
    
    public ChatMessage createUserChatMessage( Supplier<String> promptSupplier )
    {
        ChatMessage message = new ChatMessage( UUID.randomUUID().toString(), "user" );
        message.setContent( updatePromptText( promptSupplier.get() ) );
        return message;        
    }
    
    public String updatePromptText(String promptText) 
    {
        var pattern = Pattern.compile("\\$\\{(\\S+)\\}");
        var matcher = pattern.matcher(promptText);
        var out = new StringBuilder();
        while (matcher.find()) 
        {
            var key = matcher.group(1);
            String replacement = Optional.ofNullable( contextValues.getContextValue(key) )
                                         .map(Matcher::quoteReplacement)
                                         .orElse( "" );
            matcher.appendReplacement(out, replacement);
        }
        matcher.appendTail(out);
        return out.toString();
    }


}
