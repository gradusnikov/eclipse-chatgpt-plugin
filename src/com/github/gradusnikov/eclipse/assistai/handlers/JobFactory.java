package com.github.gradusnikov.eclipse.assistai.handlers;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.services.OpenAIStreamJavaHttpClient;

@Creatable
@Singleton
public class JobFactory
{
    @Inject
    private AppendMessageToViewSubscriber subscriber;

    @Inject
    private PrintMessageSubscriber debugSubscriber;
    
    @Inject
    private Provider<OpenAIStreamJavaHttpClient> clientProvider;
    
    public Job createRefactorJob( String documentText, String selectedText, String fileName )
    {
        return new Job("Asking AI for help") {
            @Override
            protected IStatus run(IProgressMonitor arg0) {
                OpenAIStreamJavaHttpClient openAIClient = clientProvider.get();
                openAIClient.subscribe(subscriber);
                openAIClient.subscribe(debugSubscriber);

                try 
                {
                    String prompt = createPromptText("refactor-prompt.txt", 
                                                     "${documentText}", documentText,
                                                     "${selectedText}", selectedText,
                                                     "${fileName}", fileName);
                    openAIClient.run(prompt);
                } 
                catch (Exception e)
                {
                    return Status.error("Unable to run the task: " + e.getMessage(), e);
                }
                return Status.OK_STATUS;
            }
        };        
    }
    
    public String createPromptText(String resourceFile, String... substitutions) throws IOException
    {
        try (InputStream in = getClass().getResourceAsStream("refactor-prompt.txt");
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

    }
    
    
}
