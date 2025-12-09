package com.github.gradusnikov.eclipse.assistai.view.dnd.handlers;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.Arrays;

import org.apache.commons.io.IOUtils;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.content.IContentTypeManager;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.swt.graphics.ImageData;

import com.github.gradusnikov.eclipse.assistai.chat.ResourceCache;
import com.github.gradusnikov.eclipse.assistai.chat.ResourceDescriptor;
import com.github.gradusnikov.eclipse.assistai.chat.ResourceDescriptor.ResourceType;
import com.github.gradusnikov.eclipse.assistai.tools.ContentTypeDetector;
import com.github.gradusnikov.eclipse.assistai.view.ChatViewPresenter;
import com.google.common.collect.Sets;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Helper class for adding dropped resources to the ResourceCache.
 * 
 * Text files and directories are added to ResourceCache for context injection into the LLM system prompt.
 * Images are handled via the Attachment system since LLMs have dedicated vision APIs.
 */
@Creatable
@Singleton
public class ResourceCacheHelper
{
    private static final String TOOL_NAME = "dnd";
    
    @Inject
    private ResourceCache resourceCache;
    
    @Inject
    private ContentTypeDetector contentTypeDetector;
    
    @Inject
    private ChatViewPresenter presenter;
    
    @Inject
    private ILog logger;
    
    @PostConstruct
    public void init()
    {
        logger.info("ResourceCacheHelper initialized with ResourceCache instance: " + System.identityHashCode(resourceCache));
    }
    
    /**
     * Adds a workspace file to the resource cache.
     * Images are handled via the Attachment system for LLM vision APIs.
     */
    public void addWorkspaceFile(IFile file)
    {
        try
        {
            if (isTextFile(file))
            {
                String content = readWorkspaceFileContent(file);
                String formattedContent = formatFileContent(file.getFullPath().toString(), content);
                ResourceDescriptor descriptor = ResourceDescriptor.fromWorkspaceFile(file, TOOL_NAME);
                resourceCache.put(descriptor, formattedContent);
                logger.info("Added workspace file to cache: " + file.getFullPath());
            }
            else if (isImageFile(file))
            {
                // Use Attachment system for images - LLMs have dedicated vision APIs
                ImageData imageData = new ImageData(file.getLocation().toFile().getAbsolutePath());
                presenter.onAttachmentAdded(imageData);
                logger.info("Added workspace image as attachment: " + file.getFullPath());
            }
            else
            {
                logger.warn("Unsupported workspace file type: " + file.getFullPath());
            }
        }
        catch (CoreException | IOException e)
        {
            logger.error("Error adding workspace file to cache: " + file.getFullPath(), e);
        }
    }
    
    /**
     * Adds a workspace container (folder/project) to the resource cache.
     * Lists the directory structure.
     */
    public void addWorkspaceContainer(IContainer container)
    {
        try
        {
            String content = formatDirectoryListing(container);
            URI uri = URI.create("workspace://" + container.getFullPath().toString() + "/listing");
            ResourceDescriptor descriptor = new ResourceDescriptor(
                uri,
                ResourceType.WORKSPACE_FILE,
                container.getName() + " (directory)",
                container.getFullPath(),
                TOOL_NAME
            );
            resourceCache.put(descriptor, content);
            logger.info("Added workspace directory to cache: " + container.getFullPath());
        }
        catch (CoreException e)
        {
            logger.error("Error adding workspace directory to cache: " + container.getFullPath(), e);
        }
    }
    
    /**
     * Adds an external text file (not in workspace) to the resource cache.
     * Note: Image files should be handled by the caller using the Attachment system.
     */
    public void addExternalFile(File file)
    {
        try
        {
            String content = readExternalFileContent(file);
            String formattedContent = formatFileContent(file.getAbsolutePath(), content);
            URI uri = file.toURI();
            ResourceDescriptor descriptor = new ResourceDescriptor(
                uri,
                ResourceType.EXTERNAL_FILE,
                file.getName(),
                null, // No workspace path for external files
                TOOL_NAME
            );
            resourceCache.put(descriptor, formattedContent);
            logger.info("Added external file to cache: " + file.getAbsolutePath());
        }
        catch (IOException e)
        {
            logger.error("Error adding external file to cache: " + file.getAbsolutePath(), e);
        }
    }
    
    /**
     * Adds an external directory to the resource cache.
     */
    public void addExternalDirectory(File directory)
    {
        try
        {
            String content = formatExternalDirectoryListing(directory);
            URI uri = URI.create(directory.toURI().toString() + "/listing");
            ResourceDescriptor descriptor = new ResourceDescriptor(
                uri,
                ResourceType.EXTERNAL_FILE,
                directory.getName() + " (directory)",
                null,
                TOOL_NAME
            );
            resourceCache.put(descriptor, content);
            logger.info("Added external directory to cache: " + directory.getAbsolutePath());
        }
        catch (IOException e)
        {
            logger.error("Error adding external directory to cache: " + directory.getAbsolutePath(), e);
        }
    }
    
    /**
     * Adds text content (e.g., from clipboard) to the resource cache.
     */
    public void addTextContent(String name, String content)
    {
        String formattedContent = formatFileContent(name, content);
        URI uri = URI.create("text:///" + name.replace(" ", "_") + "_" + System.currentTimeMillis());
        ResourceDescriptor descriptor = new ResourceDescriptor(
            uri,
            ResourceType.EXTERNAL_FILE, // Use EXTERNAL_FILE instead of TRANSIENT so it's cacheable
            name,
            null,
            TOOL_NAME
        );
        resourceCache.put(descriptor, formattedContent);
        logger.info("Added text content to cache: " + name);
    }
    
    // --- Private helper methods ---
    
    private boolean isTextFile(IFile file) throws CoreException
    {
        var contentDescription = file.getContentDescription();
        if (contentDescription != null)
        {
            var contentType = contentDescription.getContentType();
            if (contentType != null && contentType.isKindOf(Platform.getContentTypeManager().getContentType(IContentTypeManager.CT_TEXT)))
            {
                return true;
            }
        }
        // Fallback: check by extension
        String extension = file.getFileExtension();
        return extension != null && isTextExtension(extension);
    }
    
    private boolean isImageFile(IFile file)
    {
        String extension = file.getFileExtension();
        return extension != null && Sets.newHashSet("jpg", "jpeg", "png", "gif", "bmp", "webp").contains(extension.toLowerCase());
    }
    
    private boolean isTextExtension(String extension)
    {
        String[] textExtensions = {
            "txt", "java", "xml", "json", "html", "css", "js", "ts",
            "py", "rb", "php", "sql", "sh", "bat", "ps1", "ini", "conf",
            "log", "gradle", "groovy", "kt", "scala", "md", "yaml", "yml",
            "properties", "c", "cpp", "h", "hpp", "cs", "go", "rs", "swift"
        };
        return Arrays.asList(textExtensions).contains(extension.toLowerCase());
    }
    
    private String readWorkspaceFileContent(IFile file) throws IOException, CoreException
    {
        return new String(Files.readAllBytes(file.getLocation().toFile().toPath()), file.getCharset());
    }
    
    private String readExternalFileContent(File file) throws IOException
    {
        byte[] fileContent = IOUtils.toByteArray(new BufferedInputStream(new FileInputStream(file)));
        String charsetName = contentTypeDetector.detectCharset(Arrays.copyOf(fileContent, Math.min(fileContent.length, 4096)));
        return new String(fileContent, charsetName);
    }
    
    private String formatFileContent(String path, String content)
    {
        String[] lines = content.split("\n");
        int numDigits = Integer.toString(lines.length).length();
        
        StringBuilder out = new StringBuilder();
        out.append("=== FILE: ").append(path).append(" ===\n");
        for (int i = 0; i < lines.length; i++)
        {
            out.append(String.format("%0" + numDigits + "d: %s\n", i + 1, lines[i]));
        }
        out.append("=== END FILE ===\n");
        return out.toString();
    }
    
    private String formatDirectoryListing(IContainer container) throws CoreException
    {
        StringBuilder out = new StringBuilder();
        out.append("=== DIRECTORY: ").append(container.getFullPath()).append(" ===\n");
        listWorkspaceResources(container, "", out, 0, 3);
        out.append("=== END DIRECTORY ===\n");
        return out.toString();
    }
    
    private void listWorkspaceResources(IContainer container, String indent, StringBuilder out, int depth, int maxDepth) throws CoreException
    {
        if (depth >= maxDepth)
        {
            return;
        }
        
        for (IResource member : container.members())
        {
            String prefix = member instanceof IContainer ? "[D] " : "[F] ";
            out.append(indent).append(prefix).append(member.getName()).append("\n");
            
            if (member instanceof IContainer && depth < maxDepth - 1)
            {
                listWorkspaceResources((IContainer) member, indent + "  ", out, depth + 1, maxDepth);
            }
        }
    }
    
    private String formatExternalDirectoryListing(File directory) throws IOException
    {
        StringBuilder out = new StringBuilder();
        out.append("=== DIRECTORY: ").append(directory.getAbsolutePath()).append(" ===\n");
        listExternalFiles(directory, "", out, 0, 3);
        out.append("=== END DIRECTORY ===\n");
        return out.toString();
    }
    
    private void listExternalFiles(File directory, String indent, StringBuilder out, int depth, int maxDepth) throws IOException
    {
        if (depth >= maxDepth)
        {
            return;
        }
        
        File[] files = directory.listFiles();
        if (files == null)
        {
            return;
        }
        
        for (File file : files)
        {
            // Skip hidden files
            if (file.getName().startsWith("."))
            {
                continue;
            }
            
            String prefix = file.isDirectory() ? "[D] " : "[F] ";
            out.append(indent).append(prefix).append(file.getName()).append("\n");
            
            if (file.isDirectory() && depth < maxDepth - 1)
            {
                listExternalFiles(file, indent + "  ", out, depth + 1, maxDepth);
            }
        }
    }
}
