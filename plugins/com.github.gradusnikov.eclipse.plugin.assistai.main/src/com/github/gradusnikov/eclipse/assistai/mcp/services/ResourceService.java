
package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.IOException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.mcp.services.ResourceUtilities.FileInfo;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Service interface for resource-related operations including
 * reading project resources and generating code diffs.
 */
@Creatable
public class ResourceService {
    
    @Inject
    ILog logger;
    
    /**
     * Reads the content of a text resource from a specified project.
     * 
     * @param projectName The name of the project containing the resource
     * @param resourcePath The path to the resource relative to the project root
     * @return The content of the resource as a formatted string
     */
    public String readProjectResource(String projectName, String resourcePath)
    {
            // Get the project
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.exists()) 
            {
                return "Error: Project '" + projectName + "' not found.";
            }
            
            if (!project.isOpen()) 
            {
                return "Error: Project '" + projectName + "' is closed.";
            }
            
            // Get the resource
            IResource resource = project.findMember(resourcePath);
            if (resource == null || !resource.exists()) 
            {
                return "Error: Resource '" + resourcePath + "' not found in project '" + projectName + "'.";
            }
            
            // Check if the resource is a file
            if (!(resource instanceof IFile)) 
            {
                return "Error: Resource '" + resourcePath + "' is not a file.";
            }
            
            IFile file = (IFile) resource;
            FileInfo fileInfo = ResourceUtilities.readFileInfo( file );
            
            if ( fileInfo.supported() )
            {
                try
                {
                    // Prepare the response
                    StringBuilder response = new StringBuilder();
                    response.append("# Content of ").append(resourcePath).append("\n\n");
                    response.append("```");
                    response.append( fileInfo.lang() );
                    response.append( ResourceUtilities.readFileContent( file ) );
                    response.append("\n```\n");
                    return response.toString();
                }
                catch ( IOException | CoreException e )
                {
                    return "Error reading resource: " + e.getMessage();
                }
            }
            else
            {
                return "Error reading resource: " + fileInfo.error();
            }
        }        
    
}
