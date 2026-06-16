package com.github.gradusnikov.eclipse.assistai.tools;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import org.eclipse.e4.core.di.annotations.Creatable;

import jakarta.inject.Singleton;

/**
 * Detects content (MIME) types and character encodings using only the JDK and
 * the Eclipse platform &mdash; no third-party document-analysis libraries.
 * <p>
 * MIME types are guessed from the file name and, failing that, from the leading
 * "magic" bytes of the content via {@link URLConnection}. Character sets are
 * guessed from a byte-order mark when present, otherwise by validating the bytes
 * as UTF-8 and falling back to ISO-8859-1, which round-trips any byte sequence.
 */
@Creatable
@Singleton
public class ContentTypeDetector
{
    private static final String UNKNOWN = "unknown";

    /**
     * Guesses the character set of a byte sample so the bytes can be decoded to
     * text. Recognises common BOMs first, then validates the sample as UTF-8;
     * if that fails it falls back to ISO-8859-1 (Latin-1), which never throws.
     */
    public String detectCharset( byte[] content )
    {
        if ( content == null || content.length == 0 )
        {
            return StandardCharsets.UTF_8.name();
        }
        String fromBom = detectBomCharset( content );
        if ( fromBom != null )
        {
            return fromBom;
        }
        return isLikelyUtf8( content )
                ? StandardCharsets.UTF_8.name()
                : StandardCharsets.ISO_8859_1.name();
    }

    public String detectContentType( URL content )
    {
        try ( InputStream in = new BufferedInputStream( content.openStream() ) )
        {
            String byName = URLConnection.guessContentTypeFromName( content.getPath() );
            if ( byName != null )
            {
                return byName;
            }
            String byContent = URLConnection.guessContentTypeFromStream( in );
            // Default to text so extension-less text resources are still handled.
            return byContent != null ? byContent : "text/plain";
        }
        catch ( Exception e )
        {
            return UNKNOWN;
        }
    }

    public String detectContentType( byte[] content )
    {
        try ( InputStream in = new ByteArrayInputStream( content ) )
        {
            String type = URLConnection.guessContentTypeFromStream( in );
            return type != null ? type : UNKNOWN;
        }
        catch ( Exception e )
        {
            return UNKNOWN;
        }
    }

    public String detectContentType( File file )
    {
        try ( InputStream in = new BufferedInputStream( new FileInputStream( file ) ) )
        {
            String byName = URLConnection.guessContentTypeFromName( file.getName() );
            if ( byName != null )
            {
                return byName;
            }
            String byContent = URLConnection.guessContentTypeFromStream( in );
            return byContent != null ? byContent : UNKNOWN;
        }
        catch ( Exception e )
        {
            return UNKNOWN;
        }
    }

    private static String detectBomCharset( byte[] b )
    {
        if ( b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF )
        {
            return StandardCharsets.UTF_8.name();
        }
        if ( b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF )
        {
            return StandardCharsets.UTF_16BE.name();
        }
        if ( b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE )
        {
            return StandardCharsets.UTF_16LE.name();
        }
        return null;
    }

    /**
     * Returns {@code true} if the bytes are valid UTF-8. A multi-byte character
     * may be split at the end of a sampled buffer, so an error that begins within
     * the last three bytes is tolerated as a truncated trailing sequence.
     */
    private static boolean isLikelyUtf8( byte[] content )
    {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput( CodingErrorAction.REPORT )
                .onUnmappableCharacter( CodingErrorAction.REPORT );
        ByteBuffer in = ByteBuffer.wrap( content );
        CharBuffer out = CharBuffer.allocate( content.length + 1 );
        CoderResult result = decoder.decode( in, out, true );
        if ( result.isError() )
        {
            return in.position() >= content.length - 3;
        }
        return true;
    }
}
