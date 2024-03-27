package com.github.gradusnikov.eclipse.assistai.part.dnd.handlers;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.util.Arrays;

import org.apache.commons.io.IOUtils;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.text.Document;
import org.eclipse.swt.graphics.ImageData;

import com.github.gradusnikov.eclipse.assistai.part.Attachment.FileContentAttachment;
import com.github.gradusnikov.eclipse.assistai.part.ChatGPTPresenter;
import com.github.gradusnikov.eclipse.assistai.services.TikaSupport;

public class CommonDataTypeUtil
{
    public static void handleText( ChatGPTPresenter presenter, TikaSupport tika, String fileName, InputStream in )
            throws IOException, UnsupportedEncodingException
    {
        // Load file content into string, guessing the file encoding
        byte[] fileContent = IOUtils.toByteArray( in );
        String charsetName = tika.detectCharset( Arrays.copyOf( fileContent, Math.min( fileContent.length, 4096 ) ) );
        String textContent = new String( fileContent, charsetName );
        Document document = new Document( textContent );
        presenter.onAttachmentAdded( new FileContentAttachment( fileName, 1, document.getNumberOfLines(), textContent ) );
    }

    public static void handleImage( ChatGPTPresenter presenter, URL url )
    {
        ImageDescriptor imageDescriptor = ImageDescriptor.createFromURL( url );
        ImageData imageData = imageDescriptor.getImageData( 100 );
        presenter.onAttachmentAdded( imageData );
    }
}
