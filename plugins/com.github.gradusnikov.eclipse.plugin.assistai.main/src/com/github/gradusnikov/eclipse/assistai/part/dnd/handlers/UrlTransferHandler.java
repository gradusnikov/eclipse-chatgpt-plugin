package com.github.gradusnikov.eclipse.assistai.part.dnd.handlers;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;

import org.apache.commons.io.IOUtils;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.dnd.URLTransfer;

import com.github.gradusnikov.eclipse.assistai.services.ContentTypeDetector;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class UrlTransferHandler implements ITransferHandler
{
    private static final URLTransfer TRANSFER = URLTransfer.getInstance();

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
    public void handleTransfer( Object dataObj )
    {
        String data = (String) dataObj;
        try
        {
            
            URL url = new URI( data ).toURL();
            String contentType = contentTypeDetector.detectContentType( url );

            if ( contentType.startsWith( "image" ) )
            {
                attachmentHandler.handleImage( url );
            }
            else if ( contentType.startsWith( "text" ) )
            {
                attachmentHandler.handleText( toFileName( url ), url.openStream() );
            }
        }
        catch ( URISyntaxException | IOException e )
        {
            logger.error( e.getMessage(), e );
        }
    }

    private String toFileName( URL url )
    {
        try
        {
            return Paths.get( url.toURI() ).getFileName().toString();
        }
        catch ( URISyntaxException e )
        {
            return "unknown";
        }
    }

}
