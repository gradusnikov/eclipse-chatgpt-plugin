package com.github.gradusnikov.eclipse.assistai.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plain JUnit tests for {@link ContentTypeDetector}, which relies only on the
 * JDK and therefore needs no Eclipse plugin runtime.
 */
public class ContentTypeDetectorTest
{
    private ContentTypeDetector detector;

    @BeforeEach
    public void setUp()
    {
        detector = new ContentTypeDetector();
    }

    // ---- detectCharset -------------------------------------------------

    @Test
    public void nullContentDefaultsToUtf8()
    {
        assertEquals( StandardCharsets.UTF_8.name(), detector.detectCharset( null ) );
    }

    @Test
    public void emptyContentDefaultsToUtf8()
    {
        assertEquals( StandardCharsets.UTF_8.name(), detector.detectCharset( new byte[0] ) );
    }

    @Test
    public void utf8BomIsDetected()
    {
        byte[] content = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i' };
        assertEquals( StandardCharsets.UTF_8.name(), detector.detectCharset( content ) );
    }

    @Test
    public void utf16BeBomIsDetected()
    {
        byte[] content = { (byte) 0xFE, (byte) 0xFF, 0x00, 'h' };
        assertEquals( StandardCharsets.UTF_16BE.name(), detector.detectCharset( content ) );
    }

    @Test
    public void utf16LeBomIsDetected()
    {
        byte[] content = { (byte) 0xFF, (byte) 0xFE, 'h', 0x00 };
        assertEquals( StandardCharsets.UTF_16LE.name(), detector.detectCharset( content ) );
    }

    @Test
    public void plainAsciiIsUtf8()
    {
        byte[] content = "plain ASCII text".getBytes( StandardCharsets.US_ASCII );
        assertEquals( StandardCharsets.UTF_8.name(), detector.detectCharset( content ) );
    }

    @Test
    public void validMultibyteContentIsUtf8()
    {
        byte[] content = "GrÃ¼Ãe â æ¥æ¬èª â â¬".getBytes( StandardCharsets.UTF_8 );
        assertEquals( StandardCharsets.UTF_8.name(), detector.detectCharset( content ) );
    }

    @Test
    public void invalidUtf8FallsBackToIso8859()
    {
        // 0xE9 is a lone Latin-1 'Ã©'; as UTF-8 it is an incomplete lead byte,
        // and the following ASCII bytes are not valid continuation bytes.
        byte[] content = { (byte) 0xE9, 'a', 'b', 'c', 'd', 'e', 'f' };
        assertEquals( StandardCharsets.ISO_8859_1.name(), detector.detectCharset( content ) );
    }

    @Test
    public void trailingTruncatedMultibyteIsToleratedAsUtf8()
    {
        // "abcâ¬" in UTF-8 ends with the 3-byte sequence E2 82 AC; dropping the
        // last byte simulates a multibyte char cut off at a sample boundary.
        byte[] full = "abcâ¬".getBytes( StandardCharsets.UTF_8 );
        byte[] truncated = new byte[full.length - 1];
        System.arraycopy( full, 0, truncated, 0, truncated.length );
        assertEquals( StandardCharsets.UTF_8.name(), detector.detectCharset( truncated ) );
    }

    // ---- detectContentType(byte[]) -------------------------------------

    @Test
    public void pngMagicBytesAreDetected()
    {
        byte[] png = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
        assertEquals( "image/png", detector.detectContentType( png ) );
    }

    @Test
    public void gifMagicBytesAreDetected()
    {
        byte[] gif = "GIF89a".getBytes( StandardCharsets.US_ASCII );
        assertEquals( "image/gif", detector.detectContentType( gif ) );
    }

    @Test
    public void unrecognizedBytesReturnUnknown()
    {
        byte[] content = { 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01, 0x00 };
        assertEquals( "unknown", detector.detectContentType( content ) );
    }

    // ---- detectContentType(File) / (URL) -------------------------------

    @Test
    public void textFileIsDetectedAsTextByName( @TempDir File tempDir ) throws Exception
    {
        File file = new File( tempDir, "notes.txt" );
        Files.writeString( file.toPath(), "hello world" );
        assertTrue( detector.detectContentType( file ).startsWith( "text" ),
                "expected a text/* MIME type for a .txt file" );
    }

    @Test
    public void textUrlIsDetectedAsTextByName( @TempDir File tempDir ) throws Exception
    {
        File file = new File( tempDir, "page.html" );
        Files.writeString( file.toPath(), "<html><body>hi</body></html>" );
        URL url = file.toURI().toURL();
        assertTrue( detector.detectContentType( url ).startsWith( "text" ),
                "expected a text/* MIME type for an .html URL" );
    }
}
