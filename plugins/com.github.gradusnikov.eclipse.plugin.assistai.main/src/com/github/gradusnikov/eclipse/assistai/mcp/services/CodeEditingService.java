package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

import jakarta.inject.Inject;


@Creatable
public class CodeEditingService
{
    @Inject
    ILog logger;
    

    
	    
	/**
	 * Replaces a specific string in a file with a new string, optionally within a specified line range.
	 * 
	 * @param projectName The name of the project containing the file
	 * @param filePath The path to the file relative to the project root
	 * @param oldString The exact string to replace
	 * @param newString The new string to insert
	 * @param startLine Optional line number to start searching from (1-based, inclusive)
	 * @param endLine Optional line number to end searching at (1-based, inclusive)
	 * @return A status message indicating success or failure
	 */
	public String replaceStringInFile(String projectName, String filePath, String oldString, String newString, 
	                                 Integer startLine, Integer endLine) {
	    if (projectName == null || projectName.isEmpty()) {
	        return "Error: Project name cannot be empty.";
	    }
	    
	    if (filePath == null || filePath.isEmpty()) {
	        return "Error: File path cannot be empty.";
	    }
	    
	    if (oldString == null) {
	        return "Error: String to replace cannot be null.";
	    }
	    
	    if (newString == null) {
	        newString = ""; // Allow empty replacement
	    }
	    
	    try {
	        // Get the project and file
	        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
	        IProject project = root.getProject(projectName);
	        
	        if (!project.exists()) {
	            return "Error: Project '" + projectName + "' does not exist.";
	        }
	        
	        if (!project.isOpen()) {
	            project.open(null);
	        }
	        
	        IPath path = IPath.fromPath(Path.of(filePath));
	        IFile file = project.getFile(path);
	        
	        if (!file.exists()) {
	            return "Error: File '" + filePath + "' does not exist in project '" + projectName + "'.";
	        }
	        
	        // Read the file line by line for better range handling
	        List<String> lines = new ArrayList<>();
	        try (InputStream is = file.getContents(); 
	             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
	            String line;
	            while ((line = reader.readLine()) != null) {
	                lines.add(line);
	            }
	        }
	        
	        // Validate line range
	        int totalLines = lines.size();
	        
	        // Convert to 0-based indexing for internal use
	        int effectiveStartLine = (startLine != null && startLine > 0) ? startLine - 1 : 0;
	        int effectiveEndLine = (endLine != null && endLine > 0) ? endLine - 1 : totalLines - 1;
	        
	        // Validate range
	        if (effectiveStartLine >= totalLines) {
	            return "Error: Start line " + startLine + " is beyond the end of the file (total lines: " + totalLines + ").";
	        }
	        
	        if (effectiveEndLine >= totalLines) {
	            effectiveEndLine = totalLines - 1;
	        }
	        
	        if (effectiveStartLine > effectiveEndLine) {
	            return "Error: Start line cannot be greater than end line.";
	        }
	        
	        // Build content with range-limited replacements
	        StringBuilder modifiedContent = new StringBuilder();
	        boolean replacementMade = false;
	        
	        for (int i = 0; i < totalLines; i++) {
	            String currentLine = lines.get(i);
	            
	            // Only perform replacements within the specified range
	            if (i >= effectiveStartLine && i <= effectiveEndLine) {
	                // Check if this line or group of lines contains the target string
	                // We need to handle multi-line replacements, so we'll build a chunk of text
	                StringBuilder chunk = new StringBuilder(currentLine);
	                int linesInChunk = 1;
	                
	                // If the oldString contains newlines, we need to check across multiple lines
	                if (oldString.contains("\n")) {
	                    int potentialLines = Math.min(effectiveEndLine - i + 1, 
	                                                 oldString.split("\n", -1).length);
	                    
	                    for (int j = 1; j < potentialLines; j++) {
	                        if (i + j < totalLines) {
	                            chunk.append("\n").append(lines.get(i + j));
	                            linesInChunk++;
	                        }
	                    }
	                }
	                
	                String chunkStr = chunk.toString();
	                if (chunkStr.contains(oldString)) {
	                    String replacedChunk = chunkStr.replace(oldString, newString);
	                    replacementMade = true;
	                    
	                    // Split the replaced chunk back into lines
	                    String[] replacedLines = replacedChunk.split("\n", -1);
	                    
	                    // Add the first line
	                    modifiedContent.append(replacedLines[0]).append("\n");
	                    
	                    // Skip the lines that were part of the chunk in the original file
	                    i += linesInChunk - 1;
	                    
	                    // Add remaining lines from the replacement
	                    for (int j = 1; j < replacedLines.length; j++) {
	                        if (i + j < totalLines) {
	                            modifiedContent.append(replacedLines[j]).append("\n");
	                        } else {
	                            modifiedContent.append(replacedLines[j]);
	                            if (j < replacedLines.length - 1) {
	                                modifiedContent.append("\n");
	                            }
	                        }
	                    }
	                    
	                    continue;
	                }
	            }
	            
	            // Add the unchanged line
	            modifiedContent.append(currentLine);
	            if (i < totalLines - 1) {
	                modifiedContent.append("\n");
	            }
	        }
	        
	        if (!replacementMade) {
	            String rangeInfo = "";
	            if (startLine != null || endLine != null) {
	                rangeInfo = " within the specified range (lines " + 
	                           (startLine != null ? startLine : 1) + " to " + 
	                           (endLine != null ? endLine : totalLines) + ")";
	            }
	            return "Error: The specified string was not found in the file" + rangeInfo + ".";
	        }
	        
	        // Write back to the file
	        try (ByteArrayInputStream source = new ByteArrayInputStream(
	                modifiedContent.toString().getBytes(StandardCharsets.UTF_8))) {
	            file.setContents(source, IResource.FORCE, null);
	        }
	        
	        // Try to refresh the editor if the file is open
	        refreshOpenEditor(file);
	        
	        String rangeInfo = "";
	        if (startLine != null || endLine != null) {
	            rangeInfo = " within range (lines " + 
	                       (startLine != null ? startLine : 1) + " to " + 
	                       (endLine != null ? endLine : totalLines) + ")";
	        }
	        
	        return "Success: String replaced in file '" + filePath + "' in project '" + projectName + "'" + rangeInfo + ".";
	    } catch (CoreException | IOException e) {
	        return "Error: " + e.getMessage();
	    }
	}

    
    
	/**
	 * Inserts content after a specific line in an existing file.
	 * 
	 * @param projectName The name of the project containing the file
	 * @param filePath The path to the file relative to the project root
	 * @param content The content to insert into the file
	 * @param afterLine The line number after which to insert the text (0 for beginning of file)
	 * @return A status message indicating success or failure
	 */
	public String insertIntoFile(String projectName, String filePath, String content, int afterLine) {
	    if (projectName == null || projectName.isEmpty()) {
	        return "Error: Project name cannot be empty.";
	    }
	    
	    if (filePath == null || filePath.isEmpty()) {
	        return "Error: File path cannot be empty.";
	    }
	    
	    if (content == null) {
	        content = ""; // Allow empty content
	    }
	    
	    try {
	        // Get the project and file
	        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
	        IProject project = root.getProject(projectName);
	        
	        if (!project.exists()) {
	            return "Error: Project '" + projectName + "' does not exist.";
	        }
	        
	        if (!project.isOpen()) {
	            project.open(null);
	        }
	        IPath path = IPath.fromPath(Path.of(filePath));
	        IFile file = project.getFile(path);
	        
	        if (!file.exists()) {
	            return "Error: File '" + filePath + "' does not exist in project '" + projectName + "'.";
	        }
	        
	        // Read the current file content
	        String currentContent;
	        try (InputStream is = file.getContents(); 
	             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
	            StringBuilder sb = new StringBuilder();
	            String line;
	            while ((line = reader.readLine()) != null) {
	                sb.append(line).append("\n");
	            }
	            currentContent = sb.toString();
	        }
	        
	        // Split content into lines
	        String[] lines = currentContent.split("\n", -1);
	        
	        // Validate line number
	        if (afterLine < 0 || afterLine > lines.length) {
	            return "Error: Invalid line number " + afterLine + ". File has " + lines.length + " lines.";
	        }
	        
	        // Build the new content
	        StringBuilder modifiedContent = new StringBuilder();
	        
	        // Add lines before insertion point
	        for (int i = 0; i < afterLine; i++) {
	            modifiedContent.append(lines[i]).append("\n");
	        }
	        
	        // Add the new content
	        modifiedContent.append(content);
	        if (!content.endsWith("\n")) {
	            modifiedContent.append("\n");
	        }
	        
	        // Add the remaining lines
	        for (int i = afterLine; i < lines.length; i++) {
	            modifiedContent.append(lines[i]);
	            if (i < lines.length - 1) {
	                modifiedContent.append("\n");
	            }
	        }
	        
	        // Write back to the file
	        try (ByteArrayInputStream source = new ByteArrayInputStream(
	                modifiedContent.toString().getBytes(StandardCharsets.UTF_8))) {
	            file.setContents(source, IResource.FORCE, null);
	        }
	        
	        // Try to refresh the editor if the file is open
	        refreshOpenEditor(file);
	        
	        return "Success: Content inserted after line " + afterLine + " in file '" + filePath + "' in project '" + projectName + "'.";
	    } catch (CoreException | IOException e) {
	        return "Error: " + e.getMessage();
	    }
	}

	
	/** 
	 * Refreshes the editor if the file is currently open. 
	 */
	private void refreshOpenEditor(IFile file) {
	    if (Display.getCurrent() != null) {
	        // If we're on the UI thread, refresh directly
	        doRefreshEditor(file);
	    } else {
	        // Otherwise, queue it on the UI thread
	        Display.getDefault().asyncExec(() -> doRefreshEditor(file));
	    }
	}
	

	/**
	 * Does the actual work of refreshing an editor.
	 */
	private void doRefreshEditor(IFile file) {
	    try 
	    {
	        Optional.ofNullable(PlatformUI.getWorkbench())
	            .map(IWorkbench::getActiveWorkbenchWindow)
	            .map(IWorkbenchWindow::getActivePage)
	            .ifPresent(page -> {
	                // Try to find an editor for this file
	                Arrays.stream(page.getEditorReferences())
	                    .map(ref -> ref.getEditor(false))
	                    .filter(Objects::nonNull)
	                    .filter(editor -> {
	                        IEditorInput input = editor.getEditorInput();
	                        return input instanceof IFileEditorInput && 
	                               file.equals(((IFileEditorInput) input).getFile());
	                    })
	                    .findFirst()
	                    .ifPresent(editor -> {
	                        try
	                        {
	                        	// Found the editor, now refresh it
	                        	IEditorInput input = editor.getEditorInput();
	                        	if (editor instanceof ITextEditor) {
	                        		((ITextEditor) editor).getDocumentProvider().resetDocument(input);
	                        	} 
	                        	else 
	                        	{
	                        		// Try generic refresh
	                        		editor.doSave(null);
	                        	}
	                        }
	                        catch ( Exception e )
	                        {
	                        	throw new RuntimeException( e );
	                        }
	                    });
	            });
	    } 
	    catch (Exception e) 
	    {
	        // Log but don't propagate
	        System.err.println("Error refreshing editor: " + e.getMessage());
	    }
	}

	

    
	/**
	 * * Generates a diff between proposed code and an existing file in the project.
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
                throw new RuntimeException("Error: Project '" + projectName + "' not found.");
            }
            
            if (!project.isOpen()) 
            {
                throw new RuntimeException("Error: Project '" + projectName + "' is closed.");
            }
            
            // Get the file
            IResource resource = project.findMember(filePath);
            if (resource == null || !resource.exists()) 
            {
                throw new RuntimeException("Error: File '" + filePath + "' not found in project '" + projectName + "'.");
            }
            
            // Check if the resource is a file
            if (!(resource instanceof IFile)) 
            {
                throw new RuntimeException("Error: Resource '" + filePath + "' is not a file.");
            }
            
            IFile file = (IFile) resource;
            
            // Read the original file content
            String originalContent = ResourceUtilities.readFileContent(file);
            
            // Create temporary files for diff
            Path originalFile = Files.createTempFile("original-", ".tmp");
            Path proposedFile = Files.createTempFile("proposed-", ".tmp");
            
            try 
            {
                // Write contents to temp files
                Files.writeString(originalFile, originalContent);
                Files.writeString(proposedFile, proposedCode);
                
                // Generate diff using JGit
                ByteArrayOutputStream diffOutput = new ByteArrayOutputStream();
                DiffFormatter formatter = new DiffFormatter(diffOutput);
                formatter.setContext(contextLines);
                formatter.setDiffComparator(RawTextComparator.DEFAULT);
                
                RawText rawOriginal = new RawText(originalFile.toFile());
                RawText rawProposed = new RawText(proposedFile.toFile());
                
                // Write a diff header
                diffOutput.write(("--- /" + filePath + "\n").getBytes());
                diffOutput.write(("+++ /" + filePath + "\n").getBytes());
                
                // Generate edit list
                EditList edits = new HistogramDiff().diff(RawTextComparator.DEFAULT, rawOriginal, rawProposed);
                
                // Format the edits with proper context
                formatter.format(edits, rawOriginal, rawProposed);
                
                String diffResult = diffOutput.toString();
                formatter.close();
                
                // If there are no changes, inform the user
                if (diffResult.trim().isEmpty() || !diffResult.contains("@@")) 
                {
                    // No changes detected. The proposed code is identical to the existing file.
                    return "";
                }
                
                return diffResult;
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
            throw new RuntimeException("Error generating diff: " + ExceptionUtils.getRootCauseMessage(e));
        }
    }




	public String createFileAndOpen(String projectName, String filePath, String content) {
	    if (projectName == null || projectName.isEmpty()) {
	        return "Error: Project name cannot be empty.";
	    }
	    
	    if (filePath == null || filePath.isEmpty()) {
	        return "Error: File path cannot be empty.";
	    }
	    
	    if (content == null) {
	        content = ""; // Allow empty content
	    }
	    
	    try {
	        // Get the project
	        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
	        IProject project = root.getProject(projectName);
	        
	        if (!project.exists()) {
	            return "Error: Project '" + projectName + "' does not exist.";
	        }
	        
	        if (!project.isOpen()) {
	            project.open(null);
	        }
	        
	        // Fix the path by removing any leading slash
	        String normalizedPath = filePath;
	        while (normalizedPath.startsWith("/") || normalizedPath.startsWith("\\")) {
	            normalizedPath = normalizedPath.substring(1);
	        }
	        
	        if (normalizedPath.isEmpty()) {
	            return "Error: Invalid file path. Path cannot be empty after normalization.";
	        }
	        
	        // Create the file path
	        final IFile file = project.getFile(normalizedPath);
	        
	        if (file.exists()) {
	            return "Error: File '" + normalizedPath + "' already exists in project '" + projectName + "'.";
	        }
	        
	        // Create parent folders if they don't exist
	        IContainer parent = file.getParent();
	        if (parent instanceof IFolder && !parent.exists()) {
	            createFolderHierarchy((IFolder) parent);
	        }
	        
	        // Create the file with content
	        ByteArrayInputStream source = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
	        file.create(source, true, null);
	        
	        // Try to open the file in the editor, but don't fail if we can't
	        tryOpenEditorSafely(file);
	        
	        return "Success: File '" + normalizedPath + "' created in project '" + projectName + "'. " +
	               "The file may have been opened in the editor if a UI session is available.";
	    } catch (CoreException e) {
	        return "Error: " + e.getMessage();
	    }
	}
	
	/** * Tries to open a file in the editor, handling potential UI thread issues safely. * This method will not throw exceptions if it fails to open the editor. * * @param file The file to open */
	private void tryOpenEditorSafely(final IFile file) {
	    try {
	        // If we're not on the UI thread, use asyncExec
	        if (Display.getCurrent() == null) {
	            Display.getDefault().asyncExec(() -> safeOpenEditor(file));
	        } else {
	            // We're on the UI thread, try to open directly
	            safeOpenEditor(file);
	        }
	    } catch (Exception e) {
	        // Log but don't propagate
	        System.err.println("Warning: Could not schedule editor open: " + e.getMessage());
	    }
	}
	
	/** * Safely opens a file in the editor, handling null cases. * * @param file The file to open */
	private void safeOpenEditor(IFile file) {
	    try {
	        IWorkbench workbench = PlatformUI.getWorkbench();
	        if (workbench == null) return;
	        
	        IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
	        if (window == null) {
	            // Try to get any window if active window is null
	            IWorkbenchWindow[] windows = workbench.getWorkbenchWindows();
	            if (windows != null && windows.length > 0) {
	                window = windows[0];
	            }
	        }
	        
	        if (window == null) return;
	        
	        IWorkbenchPage page = window.getActivePage();
	        if (page == null) return;
	        
	        IDE.openEditor(page, file);
	    } catch (Exception e) {
	        // Log but don't propagate
	        System.err.println("Warning: Could not open editor: " + e.getMessage());
	    }
	}
	
	/** * Recursively creates a folder hierarchy. * * @param folder The folder to create * @throws CoreException If there is an error creating the folder */
	private void createFolderHierarchy(IFolder folder) throws CoreException {
	    if (!folder.exists()) {
	        IContainer parent = folder.getParent();
	        if (parent instanceof IFolder && !parent.exists()) {
	            createFolderHierarchy((IFolder) parent);
	        }
	        folder.create(true, true, null);
	    }
	}

}
