package com.github.gradusnikov.eclipse.assistai.part.dnd.handlers;

import static com.github.gradusnikov.eclipse.assistai.part.dnd.handlers.CommonDataTypeUtil.handleImage;
import static com.github.gradusnikov.eclipse.assistai.part.dnd.handlers.CommonDataTypeUtil.handleText;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import org.eclipse.core.runtime.ILog;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.Transfer;

import com.github.gradusnikov.eclipse.assistai.part.ChatGPTPresenter;
import com.github.gradusnikov.eclipse.assistai.services.TikaSupport;

import jakarta.inject.Inject;

public class FileTransferHandler implements ITransferHandler
{
    private static final FileTransfer TRANSFER = FileTransfer.getInstance();

    @Inject
    private ChatGPTPresenter presenter;

    @Inject
    private TikaSupport      tika;

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

            String contentType;
            try
            {
                // First try cheaply the Java 7 way
                contentType = Files.probeContentType( file.toPath() );

                // Fallback to Apache Tika
                if ( contentType == null )
                {
                    contentType = tika.detectContentType( file );
                }
            }
            catch ( IOException e )
            {
                contentType = "unknown";
            }

            if ( contentType.startsWith( "image" ) )
            {
                try (InputStream in = new BufferedInputStream( new FileInputStream( file ) ))
                {
                    handleImage( presenter, file.toURI().toURL() );
                }
                catch ( IOException e )
                {
                    logger.error( e.getMessage(), e );
                }
            }
            else if ( contentType.startsWith( "text" ) )
            {
                try (InputStream in = new BufferedInputStream( new FileInputStream( file ) ))
                {
                    handleText( presenter, tika, file.getName(), in );
                }
                catch ( IOException e )
                {
                    logger.error( e.getMessage(), e );
                }
            }
            else
            {
                logger.error( "Unsupported file type: " + contentType );
            }
        }
    }

}
