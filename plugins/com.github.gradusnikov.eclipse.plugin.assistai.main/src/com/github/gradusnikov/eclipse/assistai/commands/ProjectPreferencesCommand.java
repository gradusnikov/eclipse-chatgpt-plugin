package com.github.gradusnikov.eclipse.assistai.commands;

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
            e.printStackTrace();
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
            e.printStackTrace();
        }
    }

    private static void listResources( IResource resource, int depth, String prefix ) throws CoreException
    {
        // Symbols for tree branches
        String branch = depth > 0 ? "├── " : "";
        String indent = depth > 0 ? String.join( "", Collections.nCopies( depth - 1, "│   " ) ) : "";

        // Print the resource name with the tree branch symbols
        System.out.println( indent + branch + resource.getName() );

        // If the resource is a container, it can have children
        if ( resource instanceof IContainer )
        {
            IContainer container = (IContainer) resource;
            IResource[] members = container.members();
            for ( int i = 0; i < members.length; i++ )
            {
                // Check if this is the last member in the list
                boolean isLastMember = i == members.length - 1;
                String newPrefix = isLastMember ? "    " : "│   ";
                // Recursively list the members of this container
                listResources( members[i], depth + 1, indent + newPrefix );
            }
        }
    }

}
