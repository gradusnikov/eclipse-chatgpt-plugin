package com.github.gradusnikov.eclipse.assistai.view;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.patch.ApplyPatchOperation;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IStorage;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;


/**
 * A helper class for displaying the apply patch wizard dialog in the Eclipse IDE.
 * Allows users to apply a patch to a specified target path.
 */
@Creatable
@Singleton
public class ApplyPatchWizardHelper
{
    @Inject
    private ILog logger;
    
    /**
     * Displays the apply patch wizard dialog to the user, allowing them to apply a patch to a specified target path.
     *
     * @param patch The patch content as a string.
     * @param targetPath The target path where the patch will be applied.
     */
    public void showApplyPatchWizardDialog(String patch, String targetPath) 
    {
        showApplyPatchWizardDialog(patch, targetPath, null);
    }

    /**
     * Displays the apply patch wizard dialog to the user, allowing them to apply a patch to a specified target path.
     *
     * @param patch The patch content as a string.
     * @param targetPath The target path where the patch will be applied.
     * @param projectName The name of the project to apply the patch to, or null to use the currently open editor's project.
     */
    public void showApplyPatchWizardDialog(String patch, String targetPath, String projectName) 
    {
        // Create an IStorage object to wrap the InputStream
        var patchStorage = new PatchStorage( patch );
        var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        var part = window.getActivePage().getActivePart();
        
        IProject target = null;
        if ( projectName != null && !projectName.isEmpty() )
        {
            target = org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if ( !target.exists() || !target.isOpen() )
            {
                logger.error( "Project '" + projectName + "' not found or not open, falling back to active editor project." );
                target = null;
            }
        }
        if ( target == null )
        {
            target = getProjectOfCurrentlyOpenedEditor().orElse(null);
        }
        
        if ( Objects.isNull( target ) )
        {
            logger.error( "No project available." );
            return;
        }
        
        ApplyPatchOperation operation = new ApplyPatchOperation( part, patchStorage, target, new CompareConfiguration() );
        // Create and open the WizardDialog
        operation.openWizard();
    }
    

    /**
     * Returns the {@link IProject} of the project associated with the currently opened file in the text editor.
     *
     * @return The {@link IProject} of the project, or null if the active editor is not a text editor.
     */
    private Optional<IProject> getProjectOfCurrentlyOpenedEditor() 
    {
        return Optional.ofNullable( PlatformUI.getWorkbench() )
                       .map( IWorkbench::getActiveWorkbenchWindow )
                       .map( IWorkbenchWindow::getActivePage )
                       .map( IWorkbenchPage::getActiveEditor )
                       .map( IEditorPart::getEditorInput )
                       .map( editorInput -> editorInput.getAdapter(IFile.class))
                       .map( IFile::getProject);
    }
    
    private static class PatchStorage implements IStorage
    {
        private final String patch;
        public PatchStorage(String patch)
        {
            this.patch = patch;
        }
        @Override
        public <T> T getAdapter( Class<T> arg0 )
        {
            return null;
        }

        @Override
        public InputStream getContents() throws CoreException
        {
            return new ByteArrayInputStream(patch.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public IPath getFullPath()
        {
            return null;
        }

        @Override
        public String getName()
        {
            return "patch";
        }

        @Override
        public boolean isReadOnly()
        {
            return true;
        }
        
    }
    
}
