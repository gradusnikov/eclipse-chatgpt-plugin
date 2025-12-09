package com.github.gradusnikov.eclipse.assistai.view.dnd.handlers;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.ImageData;

import com.github.gradusnikov.eclipse.assistai.view.ChatViewPresenter;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Handles drag and drop of images (e.g., from clipboard or other applications).
 * Images are added as Attachments (not to ResourceCache) because LLMs have
 * dedicated vision APIs for handling images.
 */
@Creatable
@Singleton
public class ImageTransferHandler implements ITransferHandler
{
    private static final ImageTransfer TRANSFER = ImageTransfer.getInstance();
    
    @Inject
    private ChatViewPresenter presenter;

    @Override
    public Transfer getTransferType()
    {
        return TRANSFER;
    }

    @Override
    public void handleTransfer(Object data)
    {
        if (data instanceof ImageData imageData)
        {
            // Use the Attachment system for images - LLMs have dedicated vision APIs
            presenter.onAttachmentAdded(imageData);
        }
    }
}
