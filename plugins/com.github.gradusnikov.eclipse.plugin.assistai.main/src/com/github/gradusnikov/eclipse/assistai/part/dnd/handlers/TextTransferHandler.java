package com.github.gradusnikov.eclipse.assistai.part.dnd.handlers;

import static com.github.gradusnikov.eclipse.assistai.part.dnd.handlers.CommonDataTypeUtil.handleText;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.runtime.ILog;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;

import com.github.gradusnikov.eclipse.assistai.part.ChatGPTPresenter;
import com.github.gradusnikov.eclipse.assistai.services.TikaSupport;

import jakarta.inject.Inject;

public class TextTransferHandler implements ITransferHandler
{
    private static final TextTransfer TRANSFER = TextTransfer.getInstance();

    @Inject
    private ChatGPTPresenter          presenter;

    @Inject
    private TikaSupport               tika;

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
            handleText( presenter, tika, "unknown", new ByteArrayInputStream( ( (String) data ).getBytes( StandardCharsets.UTF_8 ) ) );
        }
        catch ( IOException e )
        {
            logger.error( e.getMessage(), e );
        }
    }

}
