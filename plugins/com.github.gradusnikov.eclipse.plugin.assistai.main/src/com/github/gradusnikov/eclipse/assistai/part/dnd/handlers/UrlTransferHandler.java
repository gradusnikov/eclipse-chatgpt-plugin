package com.github.gradusnikov.eclipse.assistai.part.dnd.handlers;

import static com.github.gradusnikov.eclipse.assistai.part.dnd.handlers.CommonDataTypeUtil.handleImage;
import static com.github.gradusnikov.eclipse.assistai.part.dnd.handlers.CommonDataTypeUtil.handleText;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import org.apache.commons.io.IOUtils;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.dnd.URLTransfer;

import com.github.gradusnikov.eclipse.assistai.part.ChatGPTPresenter;
import com.github.gradusnikov.eclipse.assistai.services.TikaSupport;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class UrlTransferHandler implements ITransferHandler
{
    private static final URLTransfer TRANSFER = URLTransfer.getInstance();

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
    public void handleTransfer( Object dataObj )
    {
        String data = (String) dataObj;
        try
        {
            URL url = new URI( data ).toURL();
            String contentType;
            try (InputStream in = url.openStream())
            {
                byte[] contentBytes = new byte[4096];
                IOUtils.read( in, contentBytes );
                contentType = tika.detectContentType( contentBytes );
            }

            if ( contentType.startsWith( "image" ) )
            {
                handleImage( presenter, url );
            }
            else if ( contentType.startsWith( "text" ) )
            {
                handleText( presenter, tika, guessFile( url ), url.openStream() );
            }
        }
        catch ( URISyntaxException | IOException e )
        {
            logger.error( e.getMessage(), e );
        }
    }

    private String guessFile( URL url )
    {
        String path = url.getPath();
        String[] parts = path.split( "/" );
        if ( parts.length > 0 )
        {
            return parts[parts.length - 1];
        }
        else
        {
            return "unknown";
        }
    }

}
