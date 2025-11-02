package com.github.gradusnikov.eclipse.assistai.tools;

import java.io.File;
import java.io.InputStream;
import java.net.URL;

import org.apache.commons.io.IOUtils;
import org.apache.tika.Tika;
import org.apache.tika.parser.txt.CharsetDetector;
import org.eclipse.e4.core.di.annotations.Creatable;

import jakarta.inject.Singleton;

/**
 * This service encapsulates an Apache {@link Tika} instance and provides
 * methods to guess file encodings and content types from file content.
 */
@Creatable
@Singleton
public class ContentTypeDetector
{
    private static final String UNKNOWN = "unknown";
    private final Tika              tika;

    public ContentTypeDetector()
    {
        this.tika = new Tika();
    }
    
    public String detectCharset( byte[] content )
    {
        return new CharsetDetector().setText( content ).detect().getName();
    }

    public String detectContentType( URL content )
    {
        try (InputStream in = content.openStream())
        {
            byte[] contentBytes = new byte[4096];
            IOUtils.read( in, contentBytes );
            return detectContentType( contentBytes );
        }
        catch ( Exception e )
        {
            return UNKNOWN;
        }
    }
    public String detectContentType( byte[] content )
    {
        // Tika code looks thread safe at first glance.
        return tika.detect( content );
    }

    public String detectContentType( File file )
    {
        try
        {
            var contentType =  tika.detect( file );
            return contentType;
        }
        catch ( Exception e )
        {
            return UNKNOWN;
        }
    }

}
