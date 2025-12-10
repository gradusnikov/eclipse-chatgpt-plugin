package com.github.gradusnikov.eclipse.assistai.completion;

import java.util.Optional;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.ui.text.java.ContentAssistInvocationContext;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;

import com.github.gradusnikov.eclipse.assistai.mcp.services.EditorService;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceCache;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceToolResult;

import jakarta.inject.Inject;

/**
 * Builds CompletionContext from the current editor state.
 * Extracts code before/after cursor and ensures file is cached.
 */
@Creatable
public class CompletionContextBuilder 
{
    
    private static final int MAX_CONTEXT_CHARS = 4000; // ~1000 tokens per direction
    
    @Inject
    private ILog logger;
    
    @Inject
    private EditorService editorService;
    
    @Inject
    private ResourceCache resourceCache;
    
    /**
     * Builds completion context from JDT ContentAssistInvocationContext.
     */
    public Optional<CompletionContext> build(ContentAssistInvocationContext context) 
    {
        try 
        {
            IDocument document = context.getDocument();
            int offset = context.getInvocationOffset();
            
            if (document == null) 
            {
                return Optional.empty();
            }
            
            String fullContent = document.get();
            
            // Extract code before and after cursor with limits
            String codeBeforeCursor = extractBeforeCursor(fullContent, offset);
            String codeAfterCursor = extractAfterCursor(fullContent, offset);
            
            // Get file information
            String fileName = "";
            String projectName = "";
            String fileExtension = "";
            
            Optional<IFile> currentFile = editorService.getCurrentlyOpenedFile();
            if (currentFile.isPresent()) 
            {
                IFile file = currentFile.get();
                fileName = file.getName();
                projectName = file.getProject().getName();
                fileExtension = file.getFileExtension() != null ? file.getFileExtension() : "";
                
                // Ensure file is in ResourceCache for full context
                ensureFileInCache(file);
            }
            
            // Calculate line and column
            int line = document.getLineOfOffset(offset) + 1; // 1-based
            int lineOffset = document.getLineOffset(document.getLineOfOffset(offset));
            int column = offset - lineOffset + 1; // 1-based
            
            return Optional.of(new CompletionContext(
                fileName,
                projectName,
                fileExtension,
                codeBeforeCursor,
                codeAfterCursor,
                offset,
                line,
                column
            ));
            
        } 
        catch (Exception e) 
        {
            logger.error("Error building completion context", e);
            return Optional.empty();
        }
    }
    
    /**
     * Builds completion context from ITextEditor (for non-JDT editors).
     */
    public Optional<CompletionContext> build(ITextEditor editor) 
    {
        try 
        {
            IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
            ISelection selection = editor.getSelectionProvider().getSelection();
            
            if (document == null || !(selection instanceof ITextSelection textSelection)) 
            {
                return Optional.empty();
            }
            
            int offset = textSelection.getOffset();
            String fullContent = document.get();
            
            String codeBeforeCursor = extractBeforeCursor(fullContent, offset);
            String codeAfterCursor = extractAfterCursor(fullContent, offset);
            
            // Get file information
            String fileName = "";
            String projectName = "";
            String fileExtension = "";
            
            IEditorInput input = editor.getEditorInput();
            if (input instanceof IFileEditorInput fileInput) 
            {
                IFile file = fileInput.getFile();
                fileName = file.getName();
                projectName = file.getProject().getName();
                fileExtension = file.getFileExtension() != null ? file.getFileExtension() : "";
                
                ensureFileInCache(file);
            }
            
            int line = document.getLineOfOffset(offset) + 1;
            int lineOffset = document.getLineOffset(document.getLineOfOffset(offset));
            int column = offset - lineOffset + 1;
            
            return Optional.of(new CompletionContext(
                fileName,
                projectName,
                fileExtension,
                codeBeforeCursor,
                codeAfterCursor,
                offset,
                line,
                column
            ));
            
        } 
        catch (Exception e) 
        {
            logger.error("Error building completion context from editor", e);
            return Optional.empty();
        }
    }
    
    /**
     * Extracts code before cursor, limited to MAX_CONTEXT_CHARS.
     * Tries to break at a line boundary for cleaner context.
     */
    private String extractBeforeCursor(String content, int offset) 
    {
        if (offset <= 0) 
        {
            return "";
        }
        
        int start = Math.max(0, offset - MAX_CONTEXT_CHARS);
        String extracted = content.substring(start, offset);
        
        // If we truncated, try to find a line boundary
        if (start > 0) 
        {
            int newlinePos = extracted.indexOf('\n');
            if (newlinePos > 0 && newlinePos < extracted.length() / 2) 
            {
                extracted = extracted.substring(newlinePos + 1);
            }
        }
        
        return extracted;
    }
    
    /**
     * Extracts code after cursor, limited to MAX_CONTEXT_CHARS.
     * Tries to break at a line boundary for cleaner context.
     */
    private String extractAfterCursor(String content, int offset) 
    {
        if (offset >= content.length()) 
        {
            return "";
        }
        
        int end = Math.min(content.length(), offset + MAX_CONTEXT_CHARS);
        String extracted = content.substring(offset, end);
        
        // If we truncated, try to find a line boundary
        if (end < content.length()) 
        {
            int lastNewline = extracted.lastIndexOf('\n');
            if (lastNewline > extracted.length() / 2) 
            {
                extracted = extracted.substring(0, lastNewline);
            }
        }
        
        return extracted;
    }
    
    /**
     * Ensures the file content is in ResourceCache for full context.
     */
    private void ensureFileInCache(IFile file) 
    {
        if (!resourceCache.get(file).isPresent()) 
        {
            ResourceToolResult result = editorService.getCurrentlyOpenedFileContentWithResource();
            if (result.isCacheable()) 
            {
                resourceCache.put(result);
            }
        }
    }
}
