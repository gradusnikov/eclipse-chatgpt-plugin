package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

import com.github.gradusnikov.eclipse.assistai.Activator;

/**
 * Gives the workspace's team provider a chance to make files writable before an
 * editing tool writes them.
 * <p>
 * Under a pessimistic version control system - Perforce, ClearCase, SVN with
 * needs-lock - a file that is not checked out is read-only, and the provider only
 * checks it out when asked through {@link IWorkspace#validateEdit}. Writing with
 * {@link org.eclipse.core.resources.IResource#FORCE} without asking first simply
 * fails on such a file.
 * <p>
 * Every file an operation writes is validated in one call, so a provider that
 * prompts asks once, not once per file. Files that are already writable never reach
 * the provider.
 */
public final class WriteAccessValidator
{
    private WriteAccessValidator()
    {
    }

    /**
     * Asks the workspace to make whichever of the files are read-only writable.
     *
     * @return {@link Status#OK_STATUS} when nothing is read-only, otherwise the status
     *         {@link IWorkspace#validateEdit} returned
     */
    public static IStatus ensureWritable( IFile... files )
    {
        List<IFile> readOnlyFiles = new ArrayList<>();
        for ( IFile file : files )
        {
            if ( file != null && file.isReadOnly() )
            {
                readOnlyFiles.add( file );
            }
        }
        if ( readOnlyFiles.isEmpty() )
        {
            return Status.OK_STATUS;
        }

        IStatus status = ResourcesPlugin.getWorkspace().validateEdit(
                readOnlyFiles.toArray( IFile[]::new ), IWorkspace.VALIDATE_PROMPT );
        Activator.getDefault().getLog().info( "validateEdit on " + readOnlyFiles.size()
                + " read-only file(s): " + ( status.isOK() ? "OK" : status.getMessage() ) );
        return status;
    }

    /** {@link #ensureWritable(IFile...)} for a collected set of files. */
    public static IStatus ensureWritable( Collection<IFile> files )
    {
        return ensureWritable( files.toArray( IFile[]::new ) );
    }
}
