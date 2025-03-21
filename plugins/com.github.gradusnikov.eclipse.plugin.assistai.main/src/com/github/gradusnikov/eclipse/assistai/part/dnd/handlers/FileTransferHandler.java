package com.github.gradusnikov.eclipse.assistai.part.dnd.handlers;


import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.Transfer;

import com.github.gradusnikov.eclipse.assistai.tools.ContentTypeDetector;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class FileTransferHandler implements ITransferHandler
{
    private static final FileTransfer TRANSFER = FileTransfer.getInstance();

    @Inject
    private AttachmentHelper attachmentHandler;

    @Inject
    private ContentTypeDetector      contentTypeDetector;

    @Inject
    private ILog             logger;

    @Override
    public Transfer getTransferType()
    {
        return TRANSFER;
    }

    @Override
    public void handleTransfer( Object data )
    {
        String[] files = (String[]) data;

        for ( String fullFileName : files )
        {
            File file = new File( fullFileName );
            
            String contentType = contentTypeDetector.detectContentType( file );
            

             // More comprehensive approach
             switch (contentType) {
                 case String s when s.contains("image") -> handleImageFile(file);
                 case String s when isTextContent(s) -> handleTextFile(file);
                 default -> logger.error("Unsupported file type: " + contentType);
             }
        }
    }
    private boolean isTextContent( String contentType ) 
    {
        // Common text-based MIME types
        if (contentType.contains("text") ) 
        {
            return true;
        }
        // Specific application types that are text-based
        String[] textBasedTypes = {
            "csv", "json", "xml", "javascript", "typescript", 
            "html", "css", "markdown", "yaml", "yml", 
            "properties", "java", "c", "cpp", "h", "py", "rb",
            "php", "sql", "sh", "bat", "ps1", "ini", "conf",
            "log", "gradle", "groovy", "kt", "scala", "md"
        };
        
        for (String type : textBasedTypes) 
        {
            if (contentType.contains(type)) 
            {
                return true;
            }
        }
        
        return false;
    }
    private void handleTextFile( File file )
    {
        try (InputStream in = new BufferedInputStream( new FileInputStream( file ) ))
        {
            attachmentHandler.handleText( file.getName(), in );
        }
        catch ( IOException e )
        {
            logger.error( e.getMessage(), e );
        }
    }

    private void handleImageFile( File file )
    {
        try (InputStream in = new BufferedInputStream( new FileInputStream( file ) ))
        {
            
            attachmentHandler.handleImage( file.toURI().toURL() );
        }
        catch ( IOException e )
        {
            logger.error( e.getMessage(), e );
        }
    }

}
