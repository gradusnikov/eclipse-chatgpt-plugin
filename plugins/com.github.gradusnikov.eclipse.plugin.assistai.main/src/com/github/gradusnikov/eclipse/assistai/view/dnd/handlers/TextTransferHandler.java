package com.github.gradusnikov.eclipse.assistai.view.dnd.handlers;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Handles drag and drop of plain text (e.g., from other applications).
 * Adds dropped text to the ResourceCache instead of rendering attachments in ChatView.
 */
@Creatable
@Singleton
public class TextTransferHandler implements ITransferHandler
{
    private static final TextTransfer TRANSFER = TextTransfer.getInstance();
    
    @Inject
    private ResourceCacheHelper resourceCacheHelper;

    @Inject
    private ILog logger;

    @Override
    public Transfer getTransferType()
    {
        return TRANSFER;
    }

    @Override
    public void handleTransfer(Object data)
    {
        if (data instanceof String text && !text.isBlank())
        {
            String name = "dropped_text_" + System.currentTimeMillis();
            resourceCacheHelper.addTextContent(name, text);
        }
    }
}
