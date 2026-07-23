package com.github.gradusnikov.eclipse.assistai.mcp.services;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;

/**
 * Shared utility methods for Eclipse Java Model operations used across MCP services.
 */
public final class JavaModelUtils
{
    private JavaModelUtils()
    {
    }

    /**
     * Resolves and validates a Java project by name.
     *
     * @throws RuntimeException if the project does not exist, is closed, or is not a Java project
     */
    public static IJavaProject getJavaProject( String projectName ) throws CoreException
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject( projectName );
        if ( !project.exists() )
        {
            throw new RuntimeException( "Project '" + projectName + "' does not exist." );
        }
        if ( !project.isOpen() )
        {
            throw new RuntimeException( "Project '" + projectName + "' is closed." );
        }
        if ( !project.hasNature( JavaCore.NATURE_ID ) )
        {
            throw new RuntimeException( "Project '" + projectName + "' is not a Java project." );
        }
        return JavaCore.create( project );
    }

    /**
     * Finds a source package fragment by name within a Java project.
     *
     * @return the matching {@link IPackageFragment}, or {@code null} if not found
     */
    public static IPackageFragment findPackage( IJavaProject javaProject,
                                                String packageName ) throws JavaModelException
    {
        for ( IPackageFragmentRoot root : javaProject.getPackageFragmentRoots() )
        {
            if ( root.getKind() == IPackageFragmentRoot.K_SOURCE )
            {
                IPackageFragment pkg = root.getPackageFragment( packageName );
                if ( pkg.exists() )
                {
                    return pkg;
                }
            }
        }
        return null;
    }
}
