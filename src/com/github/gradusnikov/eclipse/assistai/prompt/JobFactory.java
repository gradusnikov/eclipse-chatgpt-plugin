package com.github.gradusnikov.eclipse.assistai.prompt;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.model.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.model.Conversation;
import com.github.gradusnikov.eclipse.assistai.services.OpenAIStreamJavaHttpClient;
import com.github.gradusnikov.eclipse.assistai.subscribers.OpenAIHttpClientProvider;

@Creatable
@Singleton
public class JobFactory
{
    public enum JobType
    {
        REFACTOR,
        UNIT_TEST,
        DOCUMENT,
        EXPLAIN
    }
    @Inject
    private ILog logger;
    
    @Inject
    private OpenAIHttpClientProvider clientProvider;
    
    @Inject
    private Conversation conversation;
    
    public Job createJob( JobType type, 
                          String context, 
                          String selectedSnippet,
                          String selectedJavaElement, 
                          String selectedJavaType )
    {
        Supplier<String> propmtSupplier;
        switch ( type )
        {
            case DOCUMENT:
                propmtSupplier = javaDocPromptSupplier( context, selectedJavaElement, selectedJavaType );
                break;
            case UNIT_TEST:
                propmtSupplier = unitTestSupplier( context, selectedJavaElement, selectedJavaType );
                break;
            case REFACTOR:
                propmtSupplier = refactorPromptSupplier( context, selectedSnippet, selectedJavaType );
                break;
            default:
                throw new IllegalArgumentException();
        }
        return new SendMessageJob( propmtSupplier );        
    }
    
    private Supplier<String> javaDocPromptSupplier( String documentText, String selectedJavaElement, String selectedJavaType )
    {
        return () -> createPromptText("document-prompt.txt", 
                    "${documentText}", documentText,
                    "${javaType}", selectedJavaType,
                    "${name}", selectedJavaElement);
    }
    private Supplier<String> refactorPromptSupplier( String documentText, String selectedJavaElement, String selectedJavaType )
    {
        return () -> createPromptText("refactor-prompt.txt", 
                "${documentText}", documentText,
                "${selectedText}", selectedJavaElement,
                "${fileName}", "");
    }
    private Supplier<String> unitTestSupplier( String documentText, String selectedJavaElement, String selectedJavaType )
    {
        return () -> createPromptText("testcase-prompt.txt", 
                "${documentText}", documentText,
                "${javaType}", selectedJavaType,
                "${name}", selectedJavaElement);
    }

    
    private String createPromptText(String resourceFile, String... substitutions) 
    {
        try (InputStream in = getClass().getResourceAsStream(resourceFile);
             DataInputStream dis = new DataInputStream(in);)
        {

            String prompt = new String(dis.readAllBytes(), StandardCharsets.UTF_8);

            if (substitutions.length % 2 != 0)
            {
                throw new IllegalArgumentException("Expecting key, value pairs");

            }
            for (int i = 0; i < substitutions.length; i = i + 2)
            {
                prompt = prompt.replace(substitutions[i], substitutions[i + 1]);
            }
            return prompt.toString();
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }

    }

    public Job createSendUserMessageJob( String prompt )
    {
        return new SendMessageJob( () -> prompt );
    }
    
    private class SendMessageJob extends Job
    {
        private final Supplier<String> promptSupplier;
        public SendMessageJob( Supplier<String> promptSupplier )
        {
            super("Asking ChatGPT for help.");
            this.promptSupplier = promptSupplier;
        }
        @Override
        protected IStatus run(IProgressMonitor arg0) {
            logger.info( this.getName() );
            OpenAIStreamJavaHttpClient openAIClient = clientProvider.get(  );
            try 
            {
                synchronized ( conversation )
                {
                    ChatMessage message = conversation.newMessage( "user" );
                    message.setMessage( promptSupplier.get() );
                    conversation.add( message );
                }
                openAIClient.run(conversation);
            } 
            catch (Exception e)
            {
                return Status.error("Unable to run the task: " + e.getMessage(), e);
            }
            return Status.OK_STATUS;
        }
        
    }
    

}
