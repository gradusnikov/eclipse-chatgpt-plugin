package com.github.gradusnikov.eclipse.assistai.testsupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourceAttributes;
import org.eclipse.core.resources.team.FileModificationValidationContext;
import org.eclipse.core.resources.team.FileModificationValidator;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.team.core.RepositoryProvider;

/**
 * A team provider that behaves like a pessimistic VCS: a file that is not checked out
 * is read-only, and {@code validateEdit} "checks it out" by making it writable.
 * <p>
 * Registered in {@code fragment.xml}; map it with
 * {@code RepositoryProvider.map(project, FakeCheckoutRepositoryProvider.ID)}. The
 * calls and the files passed are recorded in static fields, because the team
 * framework creates the provider instance itself.
 */
public class FakeCheckoutRepositoryProvider extends RepositoryProvider
{
    public static final String ID = "com.github.gradusnikov.eclipse.assistai.testsupport.fakeCheckoutProvider";

    /** Every validateEdit call, each as the files it was given. */
    private static final List<List<IFile>> CALLS = new ArrayList<>();

    public static synchronized void reset()
    {
        CALLS.clear();
    }

    public static synchronized int validateEditCalls()
    {
        return CALLS.size();
    }

    public static synchronized List<List<IFile>> validatedFiles()
    {
        return List.copyOf( CALLS );
    }

    private static synchronized void record( IFile[] files )
    {
        CALLS.add( List.copyOf( Arrays.asList( files ) ) );
    }

    private final FileModificationValidator validator = new FileModificationValidator()
    {
        @Override
        public IStatus validateEdit( IFile[] files, FileModificationValidationContext context )
        {
            record( files );
            for ( IFile file : files )
            {
                try
                {
                    ResourceAttributes attrs = file.getResourceAttributes();
                    attrs.setReadOnly( false );
                    file.setResourceAttributes( attrs );
                }
                catch ( CoreException e )
                {
                    return e.getStatus();
                }
            }
            return Status.OK_STATUS;
        }

        /** Saves are not what is under test; the default would count them as edits. */
        @Override
        public IStatus validateSave( IFile file )
        {
            return Status.OK_STATUS;
        }
    };

    @Override
    public String getID()
    {
        return ID;
    }

    @Override
    public FileModificationValidator getFileModificationValidator2()
    {
        return validator;
    }

    @Override
    public void configureProject() throws CoreException
    {
    }

    @Override
    public void deconfigure() throws CoreException
    {
    }
}
