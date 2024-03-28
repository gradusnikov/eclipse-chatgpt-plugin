package com.github.gradusnikov.eclipse.assistai.services;

import java.io.File;
import java.io.IOException;

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
public class TikaSupport
{
    private final Tika              tika = new Tika();

    public String detectCharset( byte[] content )
    {
        return new CharsetDetector().setText( content ).detect().getName();
    }

    public String detectContentType( byte[] content )
    {
        // Tika code looks thread safe at first glance.
        return tika.detect( content );
    }

    public String detectContentType( File file ) throws IOException
    {
        // Tika code looks thread safe at first glance.
        return tika.detect( file );
    }

}
