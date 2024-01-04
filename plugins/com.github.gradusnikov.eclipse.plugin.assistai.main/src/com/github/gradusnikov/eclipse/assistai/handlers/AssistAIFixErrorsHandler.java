package com.github.gradusnikov.eclipse.assistai.handlers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.jdt.core.IJavaModelMarker;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.github.gradusnikov.eclipse.assistai.model.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.part.ChatGPTPresenter;
import com.github.gradusnikov.eclipse.assistai.prompt.ChatMessageFactory;
import com.github.gradusnikov.eclipse.assistai.prompt.Prompts;

public class AssistAIFixErrorsHandler
{
    @Inject
    private ILog logger;
    @Inject
    private ChatMessageFactory chatMessageFactory;
    @Inject
    private ChatGPTPresenter viewPresenter;
    
    @Execute
    public void execute( @Named( IServiceConstants.ACTIVE_SHELL ) Shell s )
    {
        var activeFile = "";
        var filePath = "";
        var ext = "";
        var fileContents = "";
        var errorMessages = "";
        
        var workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
        var projects = workspaceRoot.getProjects();

        // Get the active workbench window
        var workbenchWindow = PlatformUI.getWorkbench().getActiveWorkbenchWindow();

        // Get the active editor's input file
        var activeEditor = workbenchWindow.getActivePage().getActiveEditor();
        
        if (activeEditor instanceof ITextEditor)
        {
            ITextEditor textEditor = (ITextEditor) activeEditor;
            activeFile = textEditor.getEditorInput().getName();
            // Read the content from the file
            // this fixes skipped empty lines issue
            IFile file = (IFile) textEditor.getEditorInput().getAdapter(IFile.class);
            try  
            {
                fileContents = new String( Files.readAllBytes( file.getLocation().toFile().toPath() ), StandardCharsets.UTF_8 );
            } 
            catch (IOException e) 
            {
                throw new RuntimeException(e);
            }
            filePath     = file.getProjectRelativePath().toString(); // use project relative path
            ext          = activeFile.substring( activeFile.lastIndexOf( "." )+1 );
        }
        for ( IProject project : projects )
        {
            try
            {
                // Check if the project is open and has a Java nature
                if ( project.isOpen() && project.hasNature( JavaCore.NATURE_ID ) )
                {
                    // Get the markers for the project
                    var markers = project.findMarkers( IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER, true, IResource.DEPTH_INFINITE );

                    // Iterate through the markers and access the compile errors
                    for ( var marker : markers )
                    {
                        int severity = marker.getAttribute( IMarker.SEVERITY, -1 );

                        if ( severity == IMarker.SEVERITY_ERROR )
                        {
                            // This marker represents a compile error
                            var errorMessage = marker.getAttribute( IMarker.MESSAGE, "" );
                            var lineNumber = marker.getAttribute( IMarker.LINE_NUMBER, -1 );
                            var fileName = marker.getResource().getName();
                            // Check if the error is related to the active workbench
                            if (activeFile != null && activeFile.equals(fileName)) 
                            {
                                // Process the compile error (e.g., display it or store it for further analysis)
                                errorMessages += "Error: " + errorMessage + " at line " + lineNumber + " in file " + fileName;
                            }
                        }
                    }
                }
            }
            catch ( CoreException e )
            {
                logger.error( e.getMessage(), e );
            }
        }
        if ( !errorMessages.isEmpty() )
        {
            var context = new Context( filePath, fileContents, errorMessages, "", "", ext );
            var message = chatMessageFactory.createUserChatMessage( Prompts.FIX_ERRORS, context );
            viewPresenter.onSendPredefinedPrompt( Prompts.FIX_ERRORS, message );
        }
    }
}
