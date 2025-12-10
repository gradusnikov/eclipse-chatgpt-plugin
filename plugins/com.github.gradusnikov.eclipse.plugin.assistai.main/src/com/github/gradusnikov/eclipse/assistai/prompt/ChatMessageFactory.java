package com.github.gradusnikov.eclipse.assistai.prompt;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.chat.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.chat.Conversation;
import com.github.gradusnikov.eclipse.assistai.completion.CompletionContext;

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
                case FIX_ERRORS -> fixErrorsPromptSupplier();
                case COMPLETION -> completionPromptSupplier();
                default ->
                    throw new IllegalArgumentException();
            };
        return createUserChatMessage( promptSupplier );
    }
    
    /**
     * Creates a user chat message for code completion with the given context.
     * The completion context is set in the PromptContextValueProvider for variable substitution.
     * 
     * @param completionContext The completion context containing cursor position and surrounding code
     * @return A ChatMessage with the completion prompt
     */
    public ChatMessage createCompletionChatMessage(CompletionContext completionContext)
    {
        try
        {
            // Set the completion context for variable substitution
            contextValues.setCompletionContext(completionContext);
            
            // Create the message using the standard flow
            return createUserChatMessage(Prompts.COMPLETION);
        }
        finally
        {
            // Always clear the context to prevent memory leaks
            contextValues.clearCompletionContext();
        }
    }
    
    /**
     * Creates a Conversation with a single user message for completion.
     * This is a convenience method that combines context setup, message creation,
     * and conversation creation.
     * 
     * @param completionContext The completion context
     * @return A Conversation ready to be sent to the LLM
     */
    public Conversation createCompletionConversation(CompletionContext completionContext)
    {
        Conversation conversation = new Conversation();
        ChatMessage message = createCompletionChatMessage(completionContext);
        conversation.add(message);
        return conversation;
    }
    
    private Supplier<String> completionPromptSupplier()
    {
        return () -> promptRepository.getPrompt( Prompts.COMPLETION.name() );
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
