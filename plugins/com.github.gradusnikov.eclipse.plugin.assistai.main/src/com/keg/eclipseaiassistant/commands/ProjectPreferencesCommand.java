package com.keg.eclipseaiassistant.commands;

import org.eclipse.core.resources.IProject;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;

import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.core.runtime.IPath;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.runtime.CoreException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 
 */
public class ProjectPreferencesCommand
{

    public static void getProjectPreferences( IJavaProject javaProject )
    {
        // Get Java version
        String javaVersion = javaProject.getOption( JavaCore.COMPILER_COMPLIANCE, true );
        System.out.println( "Java Version: " + javaVersion );

        // Get referenced libraries
        try
        {
            IClasspathEntry[] classpathEntries = javaProject.getRawClasspath();
            List<String> libraries = new ArrayList<>();
            for ( IClasspathEntry entry : classpathEntries )
            {
                if ( entry.getEntryKind() == IClasspathEntry.CPE_LIBRARY )
                {
                    IPath path = entry.getPath();
                    libraries.add( path.toOSString() );
                }
            }
            System.out.println( "Referenced Libraries: " + libraries );
        }
        catch ( Exception e )
        {
            throw new RuntimeException( e );
        }
    }

    public static void printProjectLayout( IProject project )
    {
        try
        {
            listResources( project, 0, "" ); // Start with the project root
        }
        catch ( CoreException e )
        {
            throw new RuntimeException( e );
        }
    }

    private static void listResources( IResource resource, int depth, String prefix ) throws CoreException
    {
        // Symbols for tree branches
        String branch = depth > 0 ? "├── " : "";
        String lastBranch = depth > 0 ? "└── " : "";
        boolean isLast = isLastResource( resource );

        // Choose the appropriate prefix for non-root elements
        String linePrefix = depth > 0 ? ( isLast ? lastBranch : branch ) : "";

        // Print the current resource
        System.out.println( prefix + linePrefix + resource.getName() );

        // If the resource is a container, list its children
        if ( resource instanceof IContainer )
        {
            IContainer container = (IContainer) resource;
            IResource[] members = container.members();
            for ( int i = 0; i < members.length; i++ )
            {
                // Prepare the new prefix for children, adding spacing if it's
                // the last resource
                String newPrefix = prefix + ( isLast ? "    " : "│   " );
                // Recursively list the members of this container
                listResources( members[i], depth + 1, newPrefix );
            }
        }
    }

    private static boolean isLastResource( IResource resource ) throws CoreException
    {
        // Check if the resource is the last in its container
        IContainer container = resource.getParent();
        if ( container != null )
        {
            IResource[] siblings = container.members();
            return siblings[siblings.length - 1].equals( resource );
        }
        // The root element doesn't have a parent container, so it's not the
        // last resource
        return false;
    }

}
