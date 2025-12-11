
package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.Optional;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.github.gradusnikov.eclipse.assistai.resources.ResourceToolResult;
import com.github.gradusnikov.eclipse.assistai.tools.ResourceFormatter;
import com.github.gradusnikov.eclipse.assistai.tools.UISynchronizeCallable;

import jakarta.inject.Inject;



/**
 * Service interface for editor-related operations including
 * retrieving the current file and selection.
 */
@Creatable
public class EditorService 
{
    @Inject
    ILog logger;
    
    @Inject
    UISynchronizeCallable uiSync;
    
    public Optional<IFile> getCurrentlyOpenedFile()
    {
        return getActiveEditor().map( IEditorPart::getEditorInput )
                         .filter( editorInput -> editorInput instanceof IFileEditorInput )
                         .map( IFileEditorInput.class::cast )
                         .map( IFileEditorInput::getFile );
    }
    
    
    
    /**
     * Gets information about the currently active file in the Eclipse editor.
     * 
     * @return A formatted string containing file information and content
     */
    public String getCurrentlyOpenedFileContent()
    {
        return getCurrentlyOpenedFileContentWithResource().content();
    }
    
    /**
     * Gets information about the currently active file with resource metadata for caching.
     * 
     * @return ResourceToolResult containing file content and descriptor
     */
    public ResourceToolResult getCurrentlyOpenedFileContentWithResource()
    {
        return uiSync.syncCall( () -> {
	        try 
	        {
	            IFile file = getCurrentlyOpenedFile().orElseThrow( () ->  new RuntimeException("No active editor found or editor input not available.") );
	            
	            final StringBuilder result = new StringBuilder();
	            result.append("# Currently Opened File:\n\n");
                ResourceFormatter resourceFormatter = new ResourceFormatter(file);
                result.append(resourceFormatter.formatFile());
                
                return ResourceToolResult.fromFile(file, result.toString(), "getCurrentlyOpenedFile");
	        } 
	        catch (Exception e)
	        {
	        	return ResourceToolResult.transientResult(
	        	    "Error: " + e.getMessage(), 
	        	    "getCurrentlyOpenedFile"
	        	);
	        }
        });
    }

    /**
     * Gets the currently selected text or lines in the active editor.
     * 
     * @return A formatted string containing the selected text
     */
    public  String getEditorSelection()
    {
        return uiSync.syncCall( () ->{
            final StringBuilder result = new StringBuilder();
            try 
            {
                IEditorPart editor = getActiveEditor().orElseThrow( () ->  new Exception("No active editor found. Please open a file.") );
                
                // Get the selection from the editor
                if (!(editor instanceof ITextEditor textEditor)) 
                {
                    throw new RuntimeException("The current selection is not a text selection.");
                }
                ISelection selection = textEditor.getSelectionProvider().getSelection();
                
                if (selection.isEmpty()) 
                {
                    result.append("No text is currently selected in the editor.");
                    return result.toString();
                }
                if (!(selection instanceof ITextSelection textSelection)) 
                {
                    throw new RuntimeException("The current selection is not a text selection.");
                }
                
                // Get the selected text
                String selectedText = textSelection.getText();
                
                result.append("# Selected Text in Editor\n\n");
                
                // Selection details
                int startLine = textSelection.getStartLine() + 1; // 1-based
                int endLine   = textSelection.getEndLine() + 1; // 1-based
                int length    = textSelection.getLength();

                IFile file = getCurrentlyOpenedFile().orElseThrow( () ->  new RuntimeException("No active editor found or editor input not available.") );

        		result.append( "Selection from line: " + startLine );
        		result.append(" to: " + endLine );
        		result.append(" length: " + length );
        		result.append("\n");
        		result.append( "=== BEGIN selected ===\n");
        		result.append( selectedText );
        		result.append( selectedText.endsWith("\n") ? "" : "\n" );
        		result.append( "=== END selected text ===");
        		result.append("\n");
            	ResourceFormatter resourceFormatter = new ResourceFormatter(file);
            	result.append(resourceFormatter.format(startLine, endLine));
            }
            catch (Exception e)
            {
            	throw new RuntimeException(e);
            }
            return result.toString();
        } );
    }
    
    
    public Optional<IEditorPart> getActiveEditor()
    {
        return Optional.ofNullable( PlatformUI.getWorkbench() )
                       .map( IWorkbench::getActiveWorkbenchWindow )
                       .map( IWorkbenchWindow::getActivePage)
                       .map( IWorkbenchPage::getActiveEditor);
    }
    
    /**
     * Gets the code before the cursor in the currently active editor.
     * 
     * @return The code before the cursor, or empty string if not available
     */
    public String getCodeBeforeCursor()
    {
        return getActiveTextEditor()
                .map(editor -> {
                    IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
                    ISelection selection = editor.getSelectionProvider().getSelection();
                    
                    if (document != null && selection instanceof ITextSelection)
                    {
                        ITextSelection textSelection = (ITextSelection) selection;
                        int offset = textSelection.getOffset();
                        
                        try
                        {
                            return document.get(0, offset);
                        }
                        catch (Exception e)
                        {
                            logger.error("Error getting code before cursor", e);
                        }
                    }
                    return "";
                })
                .orElse("");
    }
    
    /**
     * Gets the code after the cursor in the currently active editor.
     * 
     * @return The code after the cursor, or empty string if not available
     */
    public String getCodeAfterCursor()
    {
        return getActiveTextEditor()
                .map(editor -> {
                    IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
                    ISelection selection = editor.getSelectionProvider().getSelection();
                    
                    if (document != null && selection instanceof ITextSelection)
                    {
                        ITextSelection textSelection = (ITextSelection) selection;
                        int offset = textSelection.getOffset();
                        int length = document.getLength();
                        
                        try
                        {
                            return document.get(offset, length - offset);
                        }
                        catch (Exception e)
                        {
                            logger.error("Error getting code after cursor", e);
                        }
                    }
                    return "";
                })
                .orElse("");
    }
    
    /**
     * Gets the current cursor line number (1-based).
     * 
     * @return The line number as a string, or empty string if not available
     */
    public String getCursorLine()
    {
        return getActiveTextEditor()
                .map(editor -> {
                    IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
                    ISelection selection = editor.getSelectionProvider().getSelection();
                    
                    if (document != null && selection instanceof ITextSelection)
                    {
                        ITextSelection textSelection = (ITextSelection) selection;
                        try
                        {
                            // getLine() returns 0-based line number, so add 1
                            int line = document.getLineOfOffset(textSelection.getOffset()) + 1;
                            return String.valueOf(line);
                        }
                        catch (Exception e)
                        {
                            logger.error("Error getting cursor line", e);
                        }
                    }
                    return "";
                })
                .orElse("");
    }
    
    /**
     * Gets the current cursor column number (1-based).
     * 
     * @return The column number as a string, or empty string if not available
     */
    public String getCursorColumn()
    {
        return getActiveTextEditor()
                .map(editor -> {
                    IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
                    ISelection selection = editor.getSelectionProvider().getSelection();
                    
                    if (document != null && selection instanceof ITextSelection)
                    {
                        ITextSelection textSelection = (ITextSelection) selection;
                        try
                        {
                            int offset = textSelection.getOffset();
                            int lineOffset = document.getLineInformationOfOffset(offset).getOffset();
                            // Column is 1-based
                            int column = offset - lineOffset + 1;
                            return String.valueOf(column);
                        }
                        catch (Exception e)
                        {
                            logger.error("Error getting cursor column", e);
                        }
                    }
                    return "";
                })
                .orElse("");
    }
    
    /**
     * Gets the active text editor.
     * 
     * @return Optional containing the active ITextEditor, or empty if not available
     */
    private Optional<ITextEditor> getActiveTextEditor()
    {
        return getActiveEditor()
                .filter(editor -> editor instanceof ITextEditor)
                .map(editor -> (ITextEditor) editor);
    }

}
