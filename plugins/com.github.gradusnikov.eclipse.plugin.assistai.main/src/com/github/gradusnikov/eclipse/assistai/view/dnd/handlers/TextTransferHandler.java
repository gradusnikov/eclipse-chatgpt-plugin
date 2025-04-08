package com.github.gradusnikov.eclipse.assistai.view.dnd.handlers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class TextTransferHandler implements ITransferHandler
{
    private static final TextTransfer TRANSFER = TextTransfer.getInstance();
    
    @Inject
    private AttachmentHelper attachmentHandler;

    @Inject
    private ILog                      logger;

    @Override
    public Transfer getTransferType()
    {
        return TRANSFER;
    }

    @Override
    public void handleTransfer( Object data )
    {
        try
        {
            attachmentHandler.handleText( "unknown", new ByteArrayInputStream( ( (String) data ).getBytes( StandardCharsets.UTF_8 ) ) );
        }
        catch ( IOException e )
        {
            logger.error( e.getMessage(), e );
        }
    }

}
