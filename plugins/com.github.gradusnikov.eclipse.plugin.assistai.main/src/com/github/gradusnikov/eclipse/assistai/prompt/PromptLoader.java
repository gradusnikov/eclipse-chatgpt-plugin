package com.github.gradusnikov.eclipse.assistai.prompt;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.inject.Singleton;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.e4.core.di.annotations.Creatable;

/**
 * A singleton class responsible for loading prompt text from resource files and applying substitutions.
 * The resource files are located in the "prompts" folder of the plugin.
 */
@Creatable
@Singleton
public class PromptLoader 
{
	private final String BASE_URL = "platform:/plugin/com.github.gradusnikov.eclipse.plugin.assistai.main/prompts/" ; 
	
	public PromptLoader()
	{
	}
	
    public String createPromptText(String resourceFile, String... substitutions) 
    {
        try (var in = FileLocator.toFileURL( new URL( new URL(BASE_URL), resourceFile )  ).openStream();
             var dis = new DataInputStream(in);)
        {

            var prompt = new String(dis.readAllBytes(), StandardCharsets.UTF_8);

            if (substitutions.length % 2 != 0)
            {
                throw new IllegalArgumentException("Expecting key, value pairs");

            }
            for (int i = 0; i < substitutions.length; i = i + 2)
            {
                prompt = prompt.replace(substitutions[i], substitutions[i + 1]);
            }
            return prompt;
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }

    }

}
