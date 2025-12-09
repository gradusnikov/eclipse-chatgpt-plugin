package com.github.gradusnikov.eclipse.assistai.view.dnd.handlers;

import java.io.File;
import java.util.Set;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.ImageData;

import com.github.gradusnikov.eclipse.assistai.view.ChatViewPresenter;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Handles drag and drop of external files (from file system).
 * - Image files are added as Attachments (for LLM vision APIs)
 * - Text files and directories are added to ResourceCache (for context injection)
 */
@Creatable
@Singleton
public class FileTransferHandler implements ITransferHandler
{
    private static final FileTransfer TRANSFER = FileTransfer.getInstance();
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp");

    @Inject
    private ResourceCacheHelper resourceCacheHelper;

    @Inject
    private ChatViewPresenter presenter;

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
        String[] files = (String[]) data;

        for (String fullFileName : files)
        {
            File file = new File(fullFileName);
            
            if (file.isDirectory())
            {
                resourceCacheHelper.addExternalDirectory(file);
            }
            else if (file.isFile())
            {
                if (isImageFile(file))
                {
                    // Use Attachment system for images - LLMs have dedicated vision APIs
                    try
                    {
                        ImageData imageData = new ImageData(file.getAbsolutePath());
                        presenter.onAttachmentAdded(imageData);
                    }
                    catch (Exception e)
                    {
                        logger.error("Failed to load image file: " + file.getAbsolutePath(), e);
                    }
                }
                else
                {
                    // Use ResourceCache for text files
                    resourceCacheHelper.addExternalFile(file);
                }
            }
            else
            {
                logger.warn("Unknown file type: " + fullFileName);
            }
        }
    }
    
    private boolean isImageFile(File file)
    {
        String name = file.getName().toLowerCase();
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < name.length() - 1)
        {
            String extension = name.substring(dotIndex + 1);
            return IMAGE_EXTENSIONS.contains(extension);
        }
        return false;
    }
}
