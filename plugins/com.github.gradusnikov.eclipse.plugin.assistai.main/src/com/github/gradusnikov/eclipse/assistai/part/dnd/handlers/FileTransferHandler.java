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

import com.github.gradusnikov.eclipse.assistai.services.ContentTypeDetector;

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
            
            if ( contentType.startsWith( "image" ) )
            {
                handleImageFile( file );
            }
            else if ( contentType.startsWith( "text" ) )
            {
                handleTextFile( file );
            }
            else
            {
                logger.error( "Unsupported file type: " + contentType );
            }
        }
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
