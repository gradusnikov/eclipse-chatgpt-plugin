package com.github.gradusnikov.eclipse.assistai.view.dnd.handlers;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Arrays;

import org.apache.commons.io.IOUtils;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.dnd.URLTransfer;
import org.eclipse.swt.graphics.ImageData;

import com.github.gradusnikov.eclipse.assistai.resources.ResourceCache;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceDescriptor;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceDescriptor.ResourceType;
import com.github.gradusnikov.eclipse.assistai.tools.ContentTypeDetector;
import com.github.gradusnikov.eclipse.assistai.view.ChatViewPresenter;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Handles drag and drop of URLs (e.g., from browser).
 * - Image URLs are added as Attachments (for LLM vision APIs)
 * - Text URLs are added to ResourceCache (for context injection)
 */
@Creatable
@Singleton
public class UrlTransferHandler implements ITransferHandler
{
    private static final URLTransfer TRANSFER = URLTransfer.getInstance();
    private static final String TOOL_NAME = "dnd";

    @Inject
    private ResourceCache resourceCache;
    
    @Inject
    private ContentTypeDetector contentTypeDetector;

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
    public void handleTransfer(Object dataObj)
    {
        String data = (String) dataObj;
        try
        {
            URL url = new URI(data).toURL();
            String contentType = contentTypeDetector.detectContentType(url);

            if (contentType.startsWith("image"))
            {
                handleImageUrl(url);
            }
            else if (contentType.startsWith("text"))
            {
                handleTextUrl(url);
            }
            else
            {
                logger.warn("Unsupported URL content type: " + contentType + " for " + url);
            }
        }
        catch (URISyntaxException | IOException e)
        {
            logger.error("Error handling URL: " + data, e);
        }
    }

    private void handleImageUrl(URL url) throws IOException
    {
        // Use Attachment system for images - LLMs have dedicated vision APIs
        ImageDescriptor imageDescriptor = ImageDescriptor.createFromURL(url);
        ImageData imageData = imageDescriptor.getImageData(100);
        
        if (imageData != null)
        {
            presenter.onAttachmentAdded(imageData);
        }
    }

    private void handleTextUrl(URL url) throws IOException, URISyntaxException
    {
        String fileName = toFileName(url);
        byte[] content = IOUtils.toByteArray(new BufferedInputStream(url.openStream()));
        String charsetName = contentTypeDetector.detectCharset(Arrays.copyOf(content, Math.min(content.length, 4096)));
        String textContent = new String(content, charsetName);
        
        String formattedContent = formatUrlContent(url.toString(), textContent);
        
        ResourceDescriptor descriptor = new ResourceDescriptor(
            url.toURI(),
            ResourceType.EXTERNAL_FILE,
            fileName,
            null,
            TOOL_NAME
        );
        resourceCache.put(descriptor, formattedContent);
        logger.info("Added URL content to cache: " + url);
    }

    private String formatUrlContent(String url, String content)
    {
        String[] lines = content.split("\n");
        int numDigits = Integer.toString(lines.length).length();
        
        StringBuilder out = new StringBuilder();
        out.append("=== URL: ").append(url).append(" ===\n");
        for (int i = 0; i < lines.length; i++)
        {
            out.append(String.format("%0" + numDigits + "d: %s\n", i + 1, lines[i]));
        }
        out.append("=== END URL ===\n");
        return out.toString();
    }

    private String toFileName(URL url)
    {
        try
        {
            String path = url.getPath();
            if (path != null && !path.isEmpty() && !path.equals("/"))
            {
                String fileName = Paths.get(path).getFileName().toString();
                if (!fileName.isEmpty())
                {
                    return fileName;
                }
            }
            return url.getHost();
        }
        catch (Exception e)
        {
            return "unknown";
        }
    }
}
