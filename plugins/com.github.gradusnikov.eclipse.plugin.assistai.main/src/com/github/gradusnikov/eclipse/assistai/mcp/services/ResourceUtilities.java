package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

import org.apache.tika.Tika;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;

public class ResourceUtilities
{
    public static String readFileContent( IFile file ) throws IOException, CoreException
    {
        // Read file content
        try (InputStream is = file.getContents()) 
        {
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) != -1)
            {
                content.write(buffer, 0, length);
            }
            return content.toString( file.getCharset() );
        }
    }
    
    public static record FileInfo( String lang, boolean supported, String error ) {};
    
    public static FileInfo readFileInfo( IFile file )
    {
        int MAX_FILE_SIZE_KB = 1024;
        String  error = null;
        String  lang = null;
        // Check file size to avoid loading extremely large files
        long fileSizeInKB = file.getLocation().toFile().length() / 1024;
        if (fileSizeInKB > MAX_FILE_SIZE_KB) 
        {
            // Limit to 1MB
            error = "Error: File '" + file.getFullPath().toFile() + "' is too large (" + fileSizeInKB + " KB). Maximum size is " + MAX_FILE_SIZE_KB +  " KB.";
        }
        
        if ( Objects.nonNull( error ) )
        {
            // Use Apache Tika to detect content type
            Tika tika = new Tika();
            try
            {
                String mimeType = tika.detect(file.getLocation().toFile());
                
                // Check if this is a text file
                if ( !isTextMimeType( mimeType )) {
                    
                    error = "Error: Cannot read binary file '" + file.getFullPath().toFile() + "' with MIME type '" + mimeType + 
                            "'. Only text files are supported.";
                }   
                else
                {
                    lang = getLanguageForMimeType( mimeType );
                    if ( lang.isBlank() )
                    {
                        lang = getLanguageForFile( file );
                    }
                }
            }
            catch (Exception e) 
            {
                error = "Error: Cannot detect the file content type. Reason: " + e.getMessage();
            }
        }
        return new FileInfo( lang, Objects.nonNull( error ), error );
    }

    private static boolean isTextMimeType( String mimeType )
    {
        return mimeType.startsWith("text/") || 
            mimeType.equals("application/json") || 
            mimeType.equals("application/xml") ||
            mimeType.equals("application/javascript") ||
            mimeType.contains("+xml") ||
            mimeType.contains("+json");
    }
    
    /**
     * Determines the language for syntax highlighting based on file extension.
     * 
     * @param fileExtension The file extension
     * @return The language identifier for syntax highlighting
     */
    public static String getLanguageForFile(IFile file)
    {
        return Optional.ofNullable( file )
                .map( IFile::getFileExtension )
                .map( ResourceUtilities::getLanguageForExtension )
                .orElse( "" );
    }
    
    public static String getLanguageForMimeType( String mimeType )
    {
        return switch ( mimeType ) {
            case String s when s.contains( "java" ) -> "java";
            case String s when s.contains( "python" ) -> "python";
            case String s when s.contains( "javascript" ) -> "javascript";
            case String s when s.contains( "html" ) -> "html";
            case String s when s.contains( "xml" ) -> "xml";
            case String s when s.contains( "json" ) -> "json";
            case String s when s.contains( "markdown" ) -> "markdown";
            case String s when s.contains( "x-c" ) -> "cpp";
            case String s when s.contains( "x-sh" ) -> "bash";
            default -> "";
        };
    }
    
    /**
     * Determines the language for syntax highlighting based on file extension.
     * 
     * @param fileExtension The file extension
     * @return The language identifier for syntax highlighting
     */
    public static String getLanguageForExtension(String fileExtension)
    {
        
        return switch ( Optional.ofNullable( fileExtension ).map( String::toLowerCase ).orElse( "" ) ) {
            case "java" -> "java";
            case "py"-> "python";
            case "js"-> "javascript";
            case "ts"-> "typescript";
            case "html"-> "html";
            case "xml"-> "xml";
            case "json"-> "json";
            case "md"-> "markdown";
            case "c"-> "cpp";
            case "cpp"-> "cpp";
            case "h"-> "cpp";
            case "hpp"-> "cpp";
            case "sh"-> "bash";
            case "properties"-> "properties";
            case "yaml"-> "yaml";
            case "yml"-> "yaml";
            case "txt"-> "text";
            default -> "";
            
        };
    }
}
