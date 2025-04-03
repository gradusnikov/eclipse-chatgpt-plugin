
package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.Optional;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.github.gradusnikov.eclipse.assistai.handlers.ResourceFormatter;
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
    
    /**
     * Gets information about the currently active file in the Eclipse editor.
     * 
     * @return A formatted string containing file information and content
     */
    public String getCurrentlyOpenedFile()
    {
        return uiSync.syncCall( () -> {
	        final StringBuilder result = new StringBuilder();
	        
	        try 
	        {
	            IEditorPart editor = getActiveEditor().orElseThrow( () ->  new Exception("No active editor found. Please open a file.") );
	            
	            // Get the editor input
	            IEditorInput editorInput = editor.getEditorInput();
	            if (editorInput == null) 
	            {
	            	throw new RuntimeException("No editor input available for the active editor.");
	            }
	            // Get file information
	            result.append("# Currently Opened File:\n\n");
	            
	            // Get the file path
	            IFile file = null;
	            if (editorInput instanceof IFileEditorInput) 
	            {
	                file = ((IFileEditorInput) editorInput).getFile();
	                ResourceFormatter resourceFormatter = new ResourceFormatter(file);
	                result.append(resourceFormatter.formatFile());
	            } 
	            else 
	            {
	                throw new RuntimeException("File information not available. The editor input is not a file.\n");
	            }
	        } 
	        catch (Exception e)
	        {
	        	throw new RuntimeException(e);
	        }
	        
	        return result.toString();
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
                if (editor instanceof ITextEditor) 
                {
                    ITextEditor textEditor = (ITextEditor) editor;
                    ISelection selection = textEditor.getSelectionProvider().getSelection();
                    
                    if (selection.isEmpty()) 
                    {
                        result.append("No text is currently selected in the editor.");
                        return result.toString();
                    }
                    
                    if (selection instanceof ITextSelection) 
                    {
                        ITextSelection textSelection = (ITextSelection) selection;
                        
                        // Get the selected text
                        String selectedText = textSelection.getText();
                        
                        result.append("# Selected Text in Editor\n\n");
                        
                        // Get file information
                        IEditorInput editorInput = editor.getEditorInput();
                        
                        // Selection details
                        int startLine = textSelection.getStartLine() + 1; // 1-based
                        int endLine   = textSelection.getEndLine() + 1; // 1-based
                        int length    = textSelection.getLength();
                        
                        if (editorInput instanceof IFileEditorInput) 
                        {
                        	IFile  file = ((IFileEditorInput) editorInput).getFile();
                        	
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
                        else
                        {
                            throw new RuntimeException("File information not available. The editor input is not a file.\n");
                        }
                    } 
                    else 
                    {
                    	throw new RuntimeException("The current selection is not a text selection.");
                    }
                } 
                else 
                {
                    throw new RuntimeException("The active editor is not a text editor.");
                }
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

}
