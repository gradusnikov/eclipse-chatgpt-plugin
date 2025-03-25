package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.MyersDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

import jakarta.inject.Inject;


@Creatable
public class CodeEditingService
{
    @Inject
    ILog logger;
    

    /**
     * Generates a diff between proposed code and an existing file in the project.
     * 
     * @param projectName The name of the project containing the file
     * @param filePath The path to the file relative to the project root
     * @param proposedCode The new/updated code being proposed
     * @param contextLines Number of context lines to include in the diff
     * @return A formatted string containing the diff and a summary of changes
     */
    public String generateCodeDiff(String projectName, String filePath, String proposedCode, Integer contextLines)
    {
        if (contextLines == null || contextLines < 0) 
        {
            contextLines = 3; // Default context lines
        }
        try 
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
            
            // Get the file
            IResource resource = project.findMember(filePath);
            if (resource == null || !resource.exists()) 
            {
                throw new RuntimeException( "Error: File '" + filePath + "' not found in project '" + projectName + "'." );
            }
            
            // Check if the resource is a file
            if (!(resource instanceof IFile)) 
            {
                throw new RuntimeException( "Error: Resource '" + filePath + "' is not a file." );
            }
            
            IFile file = (IFile) resource;
            
            // Read the original file content
            String originalContent = ResourceUtilities.readFileContent( file );
            
            // Create temporary files for diff
            Path originalFile = Files.createTempFile("original-", ".tmp");
            Path proposedFile = Files.createTempFile("proposed-", ".tmp");
            
            try 
            {
                // Write contents to temp files
                Files.writeString(originalFile, originalContent);
                Files.writeString(proposedFile, proposedCode);
                
                // Generate diff using JGit's DiffFormatter
                String diffResult = "";
                try (ByteArrayOutputStream diffOutput = new ByteArrayOutputStream();
                     DiffFormatter formatter = new DiffFormatter(diffOutput)) 
                {
                    formatter.setContext(contextLines);
                    
                    // Create a simple manual diff
                    RawText rawOriginal = new RawText(originalFile.toFile());
                    RawText rawProposed = new RawText(proposedFile.toFile());
                    
                    // Write a manual diff header
                    diffOutput.write(("--- /" + filePath + "\n").getBytes());
                    diffOutput.write(("+++ /" + filePath + "\n").getBytes());
                    
                    // Create and format the edit list
                    EditList edits = new EditList();
                    RawTextComparator comparator = RawTextComparator.DEFAULT;
                    edits.addAll(MyersDiff.INSTANCE.diff(comparator, rawOriginal, rawProposed));
                    
                    // Write the unified diff format
                    for (org.eclipse.jgit.diff.Edit edit : edits) 
                    {
                        int beginA = edit.getBeginA();
                        int endA = edit.getEndA();
                        int beginB = edit.getBeginB();
                        int endB = edit.getEndB();
                        
                        // Write the hunk header
                        diffOutput.write(("@@ -" + (beginA + 1) + "," + (endA - beginA) + 
                                         " +" + (beginB + 1) + "," + (endB - beginB) + " @@\n").getBytes());
                        
                        // Write the context and changes
                        for (int i = beginA; i < endA; i++) {
                            diffOutput.write(("-" + rawOriginal.getString(i) + "\n").getBytes());
                        }
                        for (int i = beginB; i < endB; i++) {
                            diffOutput.write(("+" + rawProposed.getString(i) + "\n").getBytes());
                        }
                    }
                    diffResult = diffOutput.toString();
                } 
                
                // If there are no changes, inform the user
                if (diffResult.trim().isEmpty() || !diffResult.contains("@@")) 
                {
                    // No changes detected. The proposed code is identical to the existing file.
                    return "";
                }
                
                StringBuilder response = new StringBuilder();
                response.append(diffResult);
                return response.toString();
                
            } 
            finally 
            {
                // Clean up temporary files
                Files.deleteIfExists(originalFile);
                Files.deleteIfExists(proposedFile);
            }
        } 
        catch (Exception e) 
        {
            logger.error(e.getMessage(), e);
            throw new RuntimeException( "Error generating diff: " + ExceptionUtils.getRootCauseMessage( e )  );
        }
    }
}
