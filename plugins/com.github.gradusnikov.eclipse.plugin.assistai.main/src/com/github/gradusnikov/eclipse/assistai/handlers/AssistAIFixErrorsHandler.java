package com.github.gradusnikov.eclipse.assistai.handlers;

import javax.inject.Inject;
import javax.inject.Named;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.jdt.core.IJavaModelMarker;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.github.gradusnikov.eclipse.assistai.prompt.JobFactory;
import com.github.gradusnikov.eclipse.assistai.prompt.JobFactory.JobType;

public class AssistAIFixErrorsHandler
{
    
    @Inject
    private JobFactory jobFactory;
    
    @Inject
    private ILog logger;
    
    @Execute
    public void execute( @Named( IServiceConstants.ACTIVE_SHELL ) Shell s )
    {
        String activeFile = null;
        String ext = "";
        String fileContents = "";
        String errorMessages = "";
        
        IWorkspaceRoot workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
        IProject[] projects = workspaceRoot.getProjects();

        // Get the active workbench window
        IWorkbenchWindow workbenchWindow = PlatformUI.getWorkbench().getActiveWorkbenchWindow();

        // Get the active editor's input file
        IEditorPart activeEditor = workbenchWindow.getActivePage().getActiveEditor();
        
        if (activeEditor instanceof ITextEditor)
        {
            ITextEditor textEditor = (ITextEditor) activeEditor;
            activeFile = textEditor.getEditorInput().getName();
            IDocument      document  = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
            fileContents = document.get();
            ext = activeFile.substring( activeFile.lastIndexOf( "." )+1 );

        }
        for ( IProject project : projects )
        {
            try
            {
                // Check if the project is open and has a Java nature
                if ( project.isOpen() && project.hasNature( JavaCore.NATURE_ID ) )
                {
                    // Get the markers for the project
                    IMarker[] markers = project.findMarkers( IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER, true, IResource.DEPTH_INFINITE );

                    // Iterate through the markers and access the compile errors
                    for ( IMarker marker : markers )
                    {
                        int severity = marker.getAttribute( IMarker.SEVERITY, -1 );

                        if ( severity == IMarker.SEVERITY_ERROR )
                        {
                            // This marker represents a compile error
                            String errorMessage = marker.getAttribute( IMarker.MESSAGE, "" );
                            int lineNumber = marker.getAttribute( IMarker.LINE_NUMBER, -1 );
                            String fileName = marker.getResource().getName();
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
            Context context = new Context( activeFile, fileContents, errorMessages, "", "", ext );
            Job job = jobFactory.createJob( JobType.FIX_ERRORS,  context );
            job.schedule();
        }
    }
}
