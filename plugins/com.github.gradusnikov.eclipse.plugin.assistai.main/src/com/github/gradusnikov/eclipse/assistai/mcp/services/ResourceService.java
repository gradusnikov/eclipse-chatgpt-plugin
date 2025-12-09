
package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.IOException;
import java.nio.file.Path;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.resources.ResourceToolResult;
import com.github.gradusnikov.eclipse.assistai.tools.ResourceUtilities;

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
     * @param filePath The path to the resource file relative to the project root
     * @return The content of the resource as a formatted string
     */
    public String readProjectResource(String projectName, String filePath)
    {
            // Get the project
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.exists()) 
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' not found." );
            }
            
            if (!project.isOpen()) 
            {
            	throw new RuntimeException( "Error: Project '" + projectName + "' is closed." );
            }
            
            // Get the resource
	        IPath path = IPath.fromPath(Path.of(filePath));
	        IFile file = project.getFile(path);
	        
	        if (!file.exists()) 
	        {
	            throw new RuntimeException("Error: File '" + filePath + "' does not exist in project '" + projectName + "'.");
	        }
            try
            {
                String lang = ResourceUtilities.getResourceFileType( file );
                // Prepare the response
                StringBuilder response = new StringBuilder();
                response.append("# Content of ").append(filePath).append(" in project ").append(projectName).append("\n\n");
                response.append("```");
                response.append( lang);
                response.append( ResourceUtilities.readFileContent( file ) );
                response.append("\n```\n");
                return response.toString();
            	
            }
            catch (IOException | CoreException e )
            {
            	throw new RuntimeException( e );
            }
            
        }        
    
    /**
     * Reads the content of a text resource with resource metadata for caching.
     * 
     * @param projectName The name of the project containing the resource
     * @param filePath The path to the resource file relative to the project root
     * @return ResourceToolResult with content and cacheable descriptor,
     *         or a transient result if there was an error
     */
    public ResourceToolResult readProjectResourceWithResource(String projectName, String filePath) {
        final String toolName = "readProjectResource";
        
        // Get the project
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists()) {
            return ResourceToolResult.transientResult(
                "Error: Project '" + projectName + "' not found.", 
                toolName
            );
        }
        
        if (!project.isOpen()) {
            return ResourceToolResult.transientResult(
                "Error: Project '" + projectName + "' is closed.", 
                toolName
            );
        }
        
        // Get the resource
        IPath path = IPath.fromPath(Path.of(filePath));
        IFile file = project.getFile(path);
        
        if (!file.exists()) {
            return ResourceToolResult.transientResult(
                "Error: File '" + filePath + "' does not exist in project '" + projectName + "'.", 
                toolName
            );
        }
        
        try {
            String lang = ResourceUtilities.getResourceFileType(file);
            
            // Prepare the response
            StringBuilder content = new StringBuilder();
            content.append("# Content of ").append(filePath).append(" in project ").append(projectName).append("\n\n");
            content.append("```").append(lang).append("\n");
            content.append(ResourceUtilities.readFileContent(file));
            content.append("\n```\n");
            
            // Return cacheable result with IFile reference
            return ResourceToolResult.fromFile(file, content.toString(), toolName);
            
        } catch (IOException | CoreException e) {
            logger.error("Error reading resource: " + e.getMessage(), e);
            return ResourceToolResult.transientResult(
                "Error reading file: " + e.getMessage(), 
                toolName
            );
        }
    }
}
