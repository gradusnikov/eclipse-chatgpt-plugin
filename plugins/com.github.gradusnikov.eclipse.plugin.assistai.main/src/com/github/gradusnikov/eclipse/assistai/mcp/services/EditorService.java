
package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.Optional;
import java.util.concurrent.Callable;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Service interface for editor-related operations including
 * retrieving the current file and selection.
 */
@Creatable
public class EditorService 
{
    @Inject
    ILog logger;
    
    /**
     * Gets information about the currently active file in the Eclipse editor.
     * 
     * @return A formatted string containing file information and content
     */
    public String getCurrentlyOpenedFile()
    {
        return syncExec( this::_getCurrentlyOpenedFile );
    }
    
    
    private String _getCurrentlyOpenedFile()
    {
        final StringBuilder result = new StringBuilder();
        
        try 
        {
            IEditorPart editor = getActiveEditor().orElseThrow( () ->  new Exception("No active editor found. Please open a file.") );
            
            // Get the editor input
            IEditorInput editorInput = editor.getEditorInput();
            if (editorInput == null) 
            {
                result.append("No editor input available for the active editor.");
                return result.toString();
            }
            
            // Get file information
            result.append("# Currently Opened File\n\n");
            
            // Get the file name
            String fileName = editorInput.getName();
            result.append("File Name: ").append(fileName).append("\n");
            
            // Get the file path
            IFile file = null;
            if (editorInput instanceof IFileEditorInput) 
            {
                file = ((IFileEditorInput) editorInput).getFile();
                result.append("File Path: ").append(file.getFullPath().toString()).append("\n");
                
                // Get project information
                IProject project = file.getProject();
                result.append("Project: ").append(project.getName()).append("\n");
                
                // Detect file type/language
                String extension = file.getFileExtension();
                if (extension != null) {
                    result.append("File Type: ").append(extension).append("\n");
                }
                
                // Get file content
                result.append("\n## File Content\n\n");
                result.append("```");
                // Add language hint for syntax highlighting based on extension
                result.append( ResourceUtilities.getLanguageForExtension( extension ) );
                result.append("\n");
                result.append( ResourceUtilities.readFileContent( file ) );
                result.append("\n```");
            } else {
                result.append("File information not available. The editor input is not a file.\n");
            }
        } 
        catch (Exception e)
        {
            logger.error(e.getMessage(), e);
            result.append("Error retrieving current file information: ")
                  .append(e.getMessage());
        }
        
        return result.toString();

    }

    /**
     * Gets the currently selected text or lines in the active editor.
     * 
     * @return A formatted string containing the selected text
     */
    public  String getEditorSelection()
    {
        return syncExec( this::_getEditorSelection );
    }
    
    
    private String _getEditorSelection()
    {
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
                    if (selectedText == null || selectedText.isEmpty()) 
                    {
                        result.append("Selection exists but contains no text.");
                        return result.toString();
                    }
                    
                    result.append("# Selected Text in Editor\n\n");
                    
                    // Get file information
                    IEditorInput editorInput = editor.getEditorInput();
                    String fileName = editorInput.getName();
                    result.append("File: ").append(fileName).append("\n");
                    
                    // Selection details
                    int startLine = textSelection.getStartLine() + 1; // 1-based line numbers for display
                    int endLine = textSelection.getEndLine() + 1;
                    int offset = textSelection.getOffset();
                    int length = textSelection.getLength();
                    
                    result.append("Selection: Lines ").append(startLine).append(" to ").append(endLine)
                          .append(" (").append(length).append(" characters)\n\n");
                    
                    // Try to determine the language for syntax highlighting
                    String language = "";
                    if (editorInput instanceof IFileEditorInput) 
                    {
                        IFile file = ((IFileEditorInput) editorInput).getFile();
                        language = ResourceUtilities.getLanguageForFile( file );
                    }
                    
                    // Add the selected text with syntax highlighting if possible
                    result.append("```").append(language).append("\n");
                    result.append(selectedText);
                    if (!selectedText.endsWith("\n")) {
                        result.append("\n");
                    }
                    result.append("```\n");
                } 
                else 
                {
                    result.append("The current selection is not a text selection.");
                }
            } 
            else 
            {
                result.append("The active editor is not a text editor.");
            }
        }
        catch (Exception e)
        {
            logger.error(e.getMessage(), e);
            result.append("Error retrieving editor selection: ")
                  .append(e.getMessage());
        }
        return result.toString();
        
    }
    
    public Optional<IEditorPart> getActiveEditor()
    {
        return Optional.ofNullable( PlatformUI.getWorkbench() )
                       .map( IWorkbench::getActiveWorkbenchWindow )
                       .map( IWorkbenchWindow::getActivePage)
                       .map( IWorkbenchPage::getActiveEditor);
    }
    
    /**
     * Executes a task in the UI thread synchronously.
     * 
     * @param <T> The return type of the task
     * @param callable The task to execute
     * @return The result of the task
     */
    public <T> T syncExec(Callable<T> callable) {
        final Object[] result = new Object[1];
        final Exception[] exception = new Exception[1];
        
        Display.getDefault().syncExec(() -> {
            try {
                result[0] = callable.call();
            } catch (Exception e) {
                exception[0] = e;
                logger.error(e.getMessage(), e);
            }
        });
        
        if (exception[0] != null) {
            throw new RuntimeException("Error in UI thread execution", exception[0]);
        }
        
        @SuppressWarnings("unchecked")
        T typedResult = (T) result[0];
        return typedResult;
    }

}
