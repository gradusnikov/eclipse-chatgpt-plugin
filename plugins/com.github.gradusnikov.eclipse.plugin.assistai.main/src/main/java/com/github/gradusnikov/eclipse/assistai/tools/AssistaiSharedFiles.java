package com.github.gradusnikov.eclipse.assistai.tools;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.e4.core.di.annotations.Creatable;

import jakarta.inject.Singleton;

@Creatable
@Singleton
public class AssistaiSharedFiles
{
    private static final String baseURI = "platform:/plugin/com.github.gradusnikov.eclipse.plugin.assistai.main/";
    
    private final Map<String, String> cache = new HashMap<String, String>();
    
    public AssistaiSharedFiles()
    {
    }
    public String readFile( String platformRelativePath )
    {
        return cache.computeIfAbsent(platformRelativePath, this::readResourceString );
    }
    
    private URI createURI( String platformRelativePath )
    {
        var path =  platformRelativePath.startsWith( "/" ) ? platformRelativePath.substring( 1 ) : platformRelativePath;
        return URI.create( baseURI + path );
        
    }
    
    private String readResourceString( String platformRelativePath )
    {
        return new String( readResourceBytes( platformRelativePath ), StandardCharsets.UTF_8 );
    }

    private String readResourceBase64( String platformRelativePath )
    {
        
        return Base64.getEncoder().encodeToString(readResourceBytes(platformRelativePath));
    }

    
    public byte[] readResourceBytes( String platformRelativePath )
    {
        try
        {
            var uri = createURI(platformRelativePath).toURL();
            try ( InputStream in = FileLocator.toFileURL( uri ).openStream() )
            {
                var bytes = in.readAllBytes();
                return bytes;
            }
        }
        catch ( Exception e )
        {
            throw new RuntimeException( "Cannot read resource file: " + platformRelativePath + ":" + e.getMessage(), e );
        }
        
    }
    
    public String readFileBase64( String platformRelativePath)
    {
        return cache.computeIfAbsent(platformRelativePath, this::readResourceBase64 );
    }
}
