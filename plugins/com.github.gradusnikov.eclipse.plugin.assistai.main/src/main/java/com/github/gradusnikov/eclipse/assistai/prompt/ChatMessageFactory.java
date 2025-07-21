package com.github.gradusnikov.eclipse.assistai.prompt;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.chat.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.repository.PromptRepository;

import codingagent.models.ChatModelQuery;
import codingagent.models.Prompts;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class ChatMessageFactory
{
	
	public static ChatMessageFactory INSTANCE; 	
    
	@Inject
	private PromptContextValueProvider contextValues;
	
	@Inject
	private PromptRepository promptRepository;
	
    public ChatMessageFactory()
    {
    	INSTANCE = this;
    }
    
    public ChatMessage createAssistantChatMessage( String text )
    {
        ChatMessage message = new ChatMessage( UUID.randomUUID().toString(), "assistant" );
        message.setContent( text );
        return message;

    }
    
	public ChatMessage createUserChatMessage(Prompts type) {
		return createUserChatMessage(() -> promptRepository.getPrompt(type.name()));
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
            String replacement = getContextValue(key);
            matcher.appendReplacement(out, replacement);
        }
        matcher.appendTail(out);
        return out.toString();
    }

	private String getContextValue(String key) {
		String replacement = Optional.ofNullable( contextValues.getContextValue(key) )
		                             .map(Matcher::quoteReplacement)
		                             .orElse( "" );
		return replacement;
	}

    public ChatModelQuery createQuery(Prompts type) {
    	ChatModelQuery query = type.buildQuery();
    	query.setPrompt(promptRepository.getPrompt(type.name()));    	
    	Map<String, String> valueByKey = query.getFields().stream().collect(Collectors.toMap(k -> k, k -> getContextValue(k)));    	    	         
    	query.completeThePrompt(valueByKey);    	
    	return query;
    }

}
