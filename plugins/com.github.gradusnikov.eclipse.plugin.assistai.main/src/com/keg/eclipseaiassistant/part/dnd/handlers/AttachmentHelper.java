package com.keg.eclipseaiassistant.part.dnd.handlers;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.util.Arrays;

import org.apache.commons.io.IOUtils;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.text.Document;
import org.eclipse.swt.graphics.ImageData;

import com.keg.eclipseaiassistant.part.ChatGPTPresenter;
import com.keg.eclipseaiassistant.part.Attachment.FileContentAttachment;
import com.keg.eclipseaiassistant.services.ContentTypeDetector;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;


@Creatable
@Singleton
public class AttachmentHelper
{
    @Inject
    private ChatGPTPresenter presenter;
    @Inject
    private ContentTypeDetector tika;
    
    
    public void handleText( String fileName, InputStream in ) throws IOException, UnsupportedEncodingException
    {
        // Load file content into string, guessing the file encoding
        byte[] fileContent = IOUtils.toByteArray( in );
        String charsetName = tika.detectCharset( Arrays.copyOf( fileContent, Math.min( fileContent.length, 4096 ) ) );
        String textContent = new String( fileContent, charsetName );
        Document document = new Document( textContent );
        presenter.onAttachmentAdded( new FileContentAttachment( fileName, 1, document.getNumberOfLines(), textContent ) );
    }

    public void handleImage( URL url )
    {
        ImageDescriptor imageDescriptor = ImageDescriptor.createFromURL( url );
        ImageData imageData = imageDescriptor.getImageData( 100 );
        presenter.onAttachmentAdded( imageData );
    }
}
