package com.github.gradusnikov.eclipse.assistai.prompt;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
	@Inject
	private PromptLoader promptLoader;
	
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
        return () -> promptLoader.createPromptText("fix-errors-prompt.txt", 
                "${documentText}", context.fileContents(),
                "${fileName}", context.fileName(),
                "${lang}", context.lang(),
                "${errors}", context.selectedContent()
                );
    }

    private Supplier<String> discussCodePromptSupplier( Context context )
    {
        return () -> promptLoader.createPromptText("discuss-prompt.txt", 
                "${documentText}", context.fileContents(),
                "${fileName}", context.fileName(),
                "${lang}", context.lang()
                );
    }

    private Supplier<String> javaDocPromptSupplier( Context context )
    {
        return () -> promptLoader.createPromptText("document-prompt.txt", 
                    "${documentText}", context.fileContents(),
                    "${javaType}", context.selectedItemType(),
                    "${name}", context.selectedItem(),
                    "${lang}", context.lang()
                    );
    }
    private Supplier<String> refactorPromptSupplier( Context context )
    {
        return () -> promptLoader.createPromptText("refactor-prompt.txt", 
                "${documentText}", context.fileContents(),
                "${selectedText}", context.selectedContent(),
                "${fileName}", context.fileName(),
                "${lang}", context.lang()
                );
    }
    private Supplier<String> unitTestSupplier( Context context )
    {
        return () -> promptLoader.createPromptText("testcase-prompt.txt", 
                "${documentText}", context.fileContents(),
                "${javaType}", context.selectedItemType(),
                "${name}", context.selectedItem(),
                "${lang}", context.lang()
                );
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
                synchronized ( conversation )
                {
                    ChatMessage message = conversation.newMessage( "user" );
                    String prompt = promptSupplier.get();
System.out.println( prompt );
                    message.setMessage( prompt );
                    conversation.add( message );
                }
                
                try 
                {
	                CompletableFuture<IStatus> future = CompletableFuture.runAsync( openAIClient.run(conversation) )
	        				.thenApply( v -> Status.OK_STATUS )
	        				.exceptionally( e -> Status.error("Unable to run the task: " + e.getMessage(), e) );
					return future.get();
				} 
                catch ( Exception e ) 
                {
                	return Status.error( e.getMessage(), e );
				}
//                Status status = openAIClient.run(conversation)
//                				.thenApply( v -> Status.OK_STATUS )
//                				.exceptionally( e -> Status.error("Unable to run the task: " + e.getMessage(), e) );
                
        }
        
    }
    

}
