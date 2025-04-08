package com.github.gradusnikov.eclipse.assistai.view.dnd.handlers;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.ImageData;

import com.github.gradusnikov.eclipse.assistai.view.ChatGPTPresenter;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
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
