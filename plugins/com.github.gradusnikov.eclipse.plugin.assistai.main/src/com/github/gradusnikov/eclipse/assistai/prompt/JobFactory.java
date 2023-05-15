package com.github.gradusnikov.eclipse.assistai.prompt;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.handlers.Context;
import com.github.gradusnikov.eclipse.assistai.model.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.model.Conversation;
import com.github.gradusnikov.eclipse.assistai.services.OpenAIStreamJavaHttpClient;
import com.github.gradusnikov.eclipse.assistai.subscribers.OpenAIHttpClientProvider;

@Creatable
@Singleton
public class JobFactory
{
    public static final String JOB_PREFIX = "AssistAI: ";
    
    public enum JobType
    {
        REFACTOR,
        UNIT_TEST,
        DOCUMENT,
        EXPLAIN,
        DISCUSS_CODE,
        FIX_ERRORS
    }
    @Inject
    private ILog logger;
    
    @Inject
    private OpenAIHttpClientProvider clientProvider;
    
    @Inject
    private Conversation conversation;
    
    public Job createJob( JobType type, Context context )
    {
        Supplier<String> propmtSupplier;
        switch ( type )
        {
            case DOCUMENT:
                propmtSupplier = javaDocPromptSupplier( context );
                break;
            case UNIT_TEST:
                propmtSupplier = unitTestSupplier( context );
                break;
            case REFACTOR:
                propmtSupplier = refactorPromptSupplier( context );
                break;
            case DISCUSS_CODE:
                propmtSupplier = discussCodePromptSupplier( context );
                break;
            case FIX_ERRORS:
                propmtSupplier = fixErrorsPromptSupplier( context );
                break;
            default:
                throw new IllegalArgumentException();
        }
        return new SendMessageJob( propmtSupplier );        
    }
    
    private Supplier<String> fixErrorsPromptSupplier( Context context )
    {
        return () -> createPromptText("fix-errors-prompt.txt", 
                "${documentText}", context.fileContents(),
                "${fileName}", context.fileName(),
                "${lang}", context.lang(),
                "${errors}", context.selectedContent()
                );
    }

    private Supplier<String> discussCodePromptSupplier( Context context )
    {
        return () -> createPromptText("discuss-prompt.txt", 
                "${documentText}", context.fileContents(),
                "${fileName}", context.fileName(),
                "${lang}", context.lang()
                );
    }

    private Supplier<String> javaDocPromptSupplier( Context context )
    {
        return () -> createPromptText("document-prompt.txt", 
                    "${documentText}", context.fileContents(),
                    "${javaType}", context.selectedItemType(),
                    "${name}", context.selectedItem(),
                    "${lang}", context.lang()
                    );
    }
    private Supplier<String> refactorPromptSupplier( Context context )
    {
        return () -> createPromptText("refactor-prompt.txt", 
                "${documentText}", context.fileContents(),
                "${selectedText}", context.selectedContent(),
                "${fileName}", context.fileName(),
                "${lang}", context.lang()
                );
    }
    private Supplier<String> unitTestSupplier( Context context )
    {
        return () -> createPromptText("testcase-prompt.txt", 
                "${documentText}", context.fileContents(),
                "${javaType}", context.selectedItemType(),
                "${name}", context.selectedItem(),
                "${lang}", context.lang()
                );
    }

    
    private String createPromptText(String resourceFile, String... substitutions) 
    {
        try (InputStream in = FileLocator.toFileURL( new URL("platform:/plugin/com.github.gradusnikov.eclipse.plugin.assistai.main/prompts/" + resourceFile) ).openStream();
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
    
    /**
     * 
     */
    private class SendMessageJob extends Job
    {
        private final Supplier<String> promptSupplier;
        public SendMessageJob( Supplier<String> promptSupplier )
        {
            super( JOB_PREFIX + "asking ChatGPT for help");
            this.promptSupplier = promptSupplier;
            
        }
        @Override
        protected IStatus run(IProgressMonitor progressMonitor) {
            logger.info( this.getName() );
            OpenAIStreamJavaHttpClient openAIClient = clientProvider.get();
            openAIClient.setCancelPrivider( () -> progressMonitor.isCanceled()   ); 
            try 
            {
                synchronized ( conversation )
                {
                    ChatMessage message = conversation.newMessage( "user" );
                    String prompt = promptSupplier.get();
System.out.println( prompt );
                    message.setMessage( prompt );
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
