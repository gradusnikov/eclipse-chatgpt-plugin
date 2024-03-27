package com.github.gradusnikov.eclipse.assistai.part.dnd.handlers;

import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.ImageData;

import com.github.gradusnikov.eclipse.assistai.part.ChatGPTPresenter;

import jakarta.inject.Inject;

public class ImageTransferHandler implements ITransferHandler
{
    private static final ImageTransfer TRANSFER = ImageTransfer.getInstance();
    @Inject
    private ChatGPTPresenter presenter;

    @Override
    public Transfer getTransferType()
    {
        return TRANSFER;
    }

    @Override
    public void handleTransfer( Object data )
    {
        if ( data instanceof ImageData )
        {
            ImageData image = (ImageData) data;
            presenter.onAttachmentAdded( image );
        }
    }
}
