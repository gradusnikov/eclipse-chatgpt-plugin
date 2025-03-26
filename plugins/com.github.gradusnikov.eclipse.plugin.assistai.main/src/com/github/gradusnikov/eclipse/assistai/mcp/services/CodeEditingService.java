package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFileState;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

import com.github.gradusnikov.eclipse.assistai.tools.ResourceUtilities;

import jakarta.inject.Inject;


@Creatable
public class CodeEditingService
{
    @Inject
    ILog logger;
    
    @Inject
    UISynchronize sync;
    
    
    
	/**
	 * Creates a directory structure (recursively) in the specified project.
	 * 
	 * @param projectName The name of the project where directories should be created
	 * @param directoryPath The path of directories to create, relative to the project root
	 * @return A status message indicating success or failure
	 */
	public String createDirectories(String projectName, String directoryPath) {
	    Objects.requireNonNull(projectName);
	    Objects.requireNonNull(directoryPath);
	    
	    if (projectName.isEmpty()) 
	    {
	        throw new IllegalArgumentException("Error: Project name cannot be empty.");
	    }
	    if (directoryPath.isEmpty()) 
	    {
	        throw new IllegalArgumentException("Error: Directory path cannot be empty.");
	    }
	    
	    try 
	    {
	        // Get the project
	        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
	        IProject project = root.getProject(projectName);
	        
	        if (!project.exists()) 
	        {
	            throw new RuntimeException("Error: Project '" + projectName + "' does not exist.");
	        }
	        if (!project.isOpen()) 
	        {
	            project.open(null);
	        }
	        
	        // Fix the path by removing any leading slash
	        String normalizedPath = directoryPath;
	        while (normalizedPath.startsWith("/") || normalizedPath.startsWith("\\")) 
	        {
	            normalizedPath = normalizedPath.substring(1);
	        }
	        
	        if (normalizedPath.isEmpty()) 
	        {
	            throw new RuntimeException("Error: Invalid directory path. Path cannot be empty after normalization.");
	        }
	        
	        // Get the folder handle
	        IFolder folder = project.getFolder(normalizedPath);
	        
	        if (folder.exists()) {
	            return "Directory '" + normalizedPath + "' already exists in project '" + projectName + "'.";
	        }
	        
	        // Create the folder hierarchy
	        ResourceUtilities.createFolderHierarchy(folder);
	        
	        return "Success: Directory structure '" + normalizedPath + "' created in project '" + projectName + "'.";
	    } 
	    catch (CoreException e) 
	    {
	        throw new RuntimeException(e);
	    }
	}

	/**
	 * Undoes the last edit operation by restoring a file from its backup.
	 * 
	 * @param projectName The name of the project containing the file
	 * @param filePath The path to the file relative to the project root
	 * @return A status message indicating success or failure
	 */
	public String undoEdit(String projectName, String filePath) 
	{
	    Objects.requireNonNull(projectName);
	    Objects.requireNonNull(filePath);
	    
	    if (projectName.isEmpty()) 
	    {
	        throw new IllegalArgumentException("Error: Project name cannot be empty.");
	    }
	    if (filePath.isEmpty()) 
	    {
	        throw new IllegalArgumentException("Error: File path cannot be empty.");
	    }
	    
	    try 
	    {
	        // Get the project and file
	        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
	        IProject project = root.getProject(projectName);
	        
	        if (!project.exists()) 
	        {
	            throw new RuntimeException("Error: Project '" + projectName + "' does not exist.");
	        }
	        if (!project.isOpen()) 
	        {
	            project.open(null);
	        }
	        
	        IPath path = IPath.fromPath(Path.of(filePath));
	        IFile file = project.getFile(path);
	        
	        if (!file.exists()) 
	        {
	            throw new RuntimeException("Error: File '" + filePath + "' does not exist in project '" + projectName + "'.");
	        }
	        
	        // Use Eclipse's built-in local history to undo changes
	        IFileState[] history = file.getHistory(null);
	        if (history == null || history.length == 0) 
	        {
	            throw new RuntimeException("Error: No edit history found for file '" + filePath + "'.");
	        }
	        
	        // Get the most recent history state
	        IFileState previousState = history[0];
	        
	        // Restore the file from the previous state
	        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(
	                ResourceUtilities.readInputStream(previousState.getContents()))) {
	            file.setContents(inputStream, IResource.FORCE, null);
	        }
	        
	        // Try to refresh the editor if the file is open
	        sync.asyncExec(() -> 
	        {
	            safeOpenEditor(file);
	            refreshEditor(file);
	        });
	        
	        return "Success: Undid last edit in file '" + filePath + "' in project '" + projectName + "'.";
	    } 
	    catch (CoreException | IOException e) 
	    {
	        throw new RuntimeException(e);
	    }
	}
	
	/**
	 * Replaces a specific string in a file with a new string, optionally within a specified line range.
	 * 
	 * @param projectName The name of the project containing the file
	 * @param filePath The path to the file relative to the project root
	 * @param oldString The exact string to replace
	 * @param newString The new string to insert
	 * @param startLine Optional line number to start searching from (0 for beginning of file)
	 * @param endLine Optional line number to start searching from (0 for beginning of file)
	 * @return A status message indicating success or failure
	 */
	public String replaceStringInFile(String projectName, String filePath, String oldString, String newString, 
	                                 Integer startLine, Integer endLine) {
	    
		Objects.requireNonNull(projectName);
		Objects.requireNonNull(filePath);
		Objects.requireNonNull(oldString);
		
		if (projectName.isEmpty()) 
	    {
	        throw new IllegalArgumentException("Error: Project name cannot be empty.");
	    }
	    
	    if (filePath.isEmpty()) 
	    {
	    	throw new IllegalArgumentException( "Error: File path cannot be empty.");
	    }
	    if (newString == null) 
	    {
	        newString = ""; // Allow empty replacement
	    }
	    
	    try 
	    {
	        // Get the project and file
	        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
	        IProject project = root.getProject(projectName);
	        
	        if (!project.exists()) 
	        {
	            throw new RuntimeException("Error: Project '" + projectName + "' does not exist.");
	        }
	        if (!project.isOpen()) 
	        {
	            project.open(null);
	        }
	        
	        IPath path = IPath.fromPath(Path.of(filePath));
	        IFile file = project.getFile(path);
	        
	        if (!file.exists()) 
	        {
	        	throw new RuntimeException("Error: File '" + filePath + "' does not exist in project '" + projectName + "'.");
	        }
	        
	        // Read the file line by line for better range handling
	        List<String> lines = ResourceUtilities.readFileLines( file );
	        
	        // Validate line range
	        int totalLines = lines.size();
	        
	        // Convert to 0-based indexing for internal use
	        int effectiveStartLine = (startLine != null) ? Math.max(0, startLine - 1) : 0;
	        int effectiveEndLine = (endLine != null) ? Math.min(totalLines - 1, endLine - 1) : totalLines - 1;
	        
	        // Validate range
	        if (effectiveStartLine >= totalLines) 
	        {
	            throw new RuntimeException( "Error: Start line " + startLine + " is beyond the end of the file (total lines: " + totalLines + ")." );
	        }
	        effectiveEndLine = Math.min( effectiveEndLine, totalLines -1 );
	        
	        if (effectiveStartLine > effectiveEndLine) 
	        {
	        	throw new RuntimeException( "Error: Start line cannot be greater than end line." );
	        }
	        
	        // Build content with range-limited replacements
	        StringBuilder modifiedContent = new StringBuilder();
	        boolean replacementMade = false;
	        
	        for (int i = 0; i < totalLines; i++) 
	        {
	            String currentLine = lines.get(i);
	            
	            // Only perform replacements within the specified range
	            if (i >= effectiveStartLine && i <= effectiveEndLine) 
	            {
	                // Check if this line or group of lines contains the target string
	                // We need to handle multi-line replacements, so we'll build a chunk of text
	                StringBuilder chunk = new StringBuilder(currentLine);
	                int linesInChunk = 1;
	                
	                // If the oldString contains newlines, we need to check across multiple lines
	                if (oldString.contains("\n")) {
	                    int potentialLines = Math.min(effectiveEndLine - i + 1, 
	                                                 oldString.split("\n", -1).length);
	                    
	                    for (int j = 1; j < potentialLines; j++) 
	                    {
	                        if (i + j < totalLines) 
	                        {
	                            chunk.append("\n").append(lines.get(i + j));
	                            linesInChunk++;
	                        }
	                    }
	                }
	                
	                String chunkStr = chunk.toString();
	                if (chunkStr.contains(oldString)) 
	                {
	                    String replacedChunk = chunkStr.replace(oldString, newString);
	                    replacementMade = true;
	                    
	                    // Split the replaced chunk back into lines
	                    String[] replacedLines = replacedChunk.split("\n", -1);
	                    
	                    // Add the first line
	                    modifiedContent.append(replacedLines[0]).append("\n");
	                    
	                    // Skip the lines that were part of the chunk in the original file
	                    i += linesInChunk - 1;
	                    
	                    // Add remaining lines from the replacement
	                    for (int j = 1; j < replacedLines.length; j++) 
	                    {
	                        if (i + j < totalLines) 
	                        {
	                            modifiedContent.append(replacedLines[j]).append("\n");
	                        } 
	                        else 
	                        {
	                            modifiedContent.append(replacedLines[j]);
	                            if (j < replacedLines.length - 1) 
	                            {
	                                modifiedContent.append("\n");
	                            }
	                        }
	                    }
	                    continue;
	                }
	            }
	            
	            // Add the unchanged line
	            modifiedContent.append(currentLine);
	            if (i < totalLines - 1) 
	            {
	                modifiedContent.append("\n");
	            }
	        }
	        
	        if (!replacementMade) 
	        {
	            String rangeInfo = "";
	            if (startLine != null || endLine != null) 
	            {
	            	rangeInfo = " within range (lines " + (startLine != null ? startLine : 1) + " to " + (endLine != null ? endLine : totalLines) + ")";
	            }
	            throw new RuntimeException( "Error: The specified string was not found in the file" + rangeInfo + "." );
	        }
	        
	        // Write back to the file
	        try (ByteArrayInputStream source = new ByteArrayInputStream(modifiedContent.toString().getBytes(StandardCharsets.UTF_8))) 
	        {
	            file.setContents(source, IResource.FORCE, null);
	        }
	        
	        // Try to open the file in the editor, but don't fail if we can't
	        sync.asyncExec(() -> safeOpenEditor(file) );

	        // Try to refresh the editor if the file is open
	        sync.asyncExec( () -> refreshEditor(file) );
	        
	        String rangeInfo = "";
	        if (startLine != null || endLine != null) 
	        {
	            rangeInfo = " within range (lines " + 
	                       (startLine != null ? startLine : 1) + " to " + 
	                       (endLine != null ? endLine : totalLines) + ")";
	        }
	        
	        return "Success: String replaced in file '" + filePath + "' in project '" + projectName + "'" + rangeInfo + ".";
	    } 
	    catch (CoreException | IOException e) 
	    {
	        throw new RuntimeException( e );
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
	public String insertIntoFile(String projectName, String filePath, String content, int afterLine) 
	{
		Objects.requireNonNull(projectName);
		Objects.requireNonNull(filePath);
		
		if (projectName.isEmpty()) 
	    {
	        throw new IllegalArgumentException("Error: Project name cannot be empty.");
	    }
	    if (filePath.isEmpty()) 
	    {
	    	throw new IllegalArgumentException( "Error: File path cannot be empty.");
	    }
	    if ( Objects.isNull(content) )  
	    {
	        content = ""; // Allow empty content
	    }
	    
	    try 
	    {
	        // Get the project and file
	        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
	        IProject project = root.getProject(projectName);
	        
	        if (!project.exists()) 
	        {
	            throw new RuntimeException("Error: Project '" + projectName + "' does not exist.");
	        }
	        if (!project.isOpen()) 
	        {
	            project.open(null);
	        }
	        IPath path = IPath.fromPath(Path.of(filePath));
	        IFile file = project.getFile(path);
	        
	        if (!file.exists()) 
	        {
	            throw new RuntimeException( "Error: File '" + filePath + "' does not exist in project '" + projectName + "'.");
	        }
	        
	        List<String> lines = ResourceUtilities.readFileLines(file);
	        
	        // Validate line number
	        if (afterLine < 0 || afterLine > lines.size() ) 
	        {
	            return "Error: Invalid line number " + afterLine + ". File has " + lines.size() + " lines.";
	        }
	        
	        // Build the new content
	        StringBuilder modifiedContent = new StringBuilder();
	        
	        // Add lines before insertion point
	        for (int i = 0; i < afterLine; i++) 
	        {
	            modifiedContent.append(lines.get(i)).append("\n");
	        }
	        
	        // Add the new content
	        modifiedContent.append(content);
	        if (!content.endsWith("\n")) 
	        {
	            modifiedContent.append("\n");
	        }
	        
	        // Add the remaining lines
	        for (int i = afterLine; i < lines.size(); i++) 
	        {
	            modifiedContent.append(lines.get(i) );
	            if (i < lines.size() - 1) 
	            {
	                modifiedContent.append("\n");
	            }
	        }
	        
	        // Write back to the file
	        try (ByteArrayInputStream source = new ByteArrayInputStream(
	                modifiedContent.toString().getBytes(StandardCharsets.UTF_8))) 
	        {
	            file.setContents(source, IResource.FORCE, null);
	        }
	        // Try to open the file in the editor, but don't fail if we can't
	        // Try to refresh the editor if the file is open
	        sync.asyncExec( () -> {
	        	safeOpenEditor(file);
	        	refreshEditor(file);	
	        });
	        
	        return "Success: Content inserted after line " + afterLine + " in file '" + filePath + "' in project '" + projectName + "'.";
	    } 
	    catch (CoreException | IOException e) 
	    {
	        throw new RuntimeException(e); 
	    }
	}

	/**
	 * Does the actual work of refreshing an editor.
	 */
	private void refreshEditor(IFile file) 
	{
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
	        logger.error("Error refreshing editor: " + e.getMessage());
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
		Objects.requireNonNull(projectName);
		Objects.requireNonNull(filePath);
		
		if (projectName.isEmpty()) 
	    {
	        throw new IllegalArgumentException("Error: Project name cannot be empty.");
	    }
	    if (filePath.isEmpty()) 
	    {
	    	throw new IllegalArgumentException( "Error: File path cannot be empty.");
	    }

    	
        if (contextLines == null || contextLines < 0) 
        {
            contextLines = 3; // Default context lines
        }
        try 
        {
            // Get the project
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (!project.exists()) 
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

    /**
     * Formats the given code string according to the current Eclipse formatter settings.
     * This is equivalent to pressing Ctrl+Shift+F in the Eclipse editor.
     * 
     * @param code The unformatted code string
     * @param projectName Optional project name to use project-specific formatter settings
     * @return The formatted code string
     */
	public String formatCode(String code, String projectName) {
		Objects.requireNonNull(code, "Code cannot be null");
		try
		{
			// Get formatting options - first try project-specific settings if project is
			// provided
			Map<String, String> options;

			if (projectName != null && !projectName.isEmpty()) 
			{
				IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
				if (project.exists() && project.isOpen()) 
				{
					IJavaProject javaProject = JavaCore.create(project);
					options = javaProject.getOptions(true);
				}
				else 
				{
					// Fall back to workspace defaults if project doesn't exist or is closed
					options = JavaCore.getOptions();
				}
			} 
			else 
			{
				// Use workspace defaults
				options = JavaCore.getOptions();
			}

			// Create formatter with the options
			CodeFormatter formatter = ToolFactory.createCodeFormatter(options);

			// Format the code
			TextEdit textEdit = formatter.format(CodeFormatter.K_COMPILATION_UNIT | CodeFormatter.F_INCLUDE_COMMENTS,
					code, 0, code.length(), 0, null);

			if (textEdit == null) 
			{
				// If formatting failed, return the original code
				logger.warn("Code formatting failed - returning unformatted code");
				return code;
			}

			// Apply the formatting changes
			IDocument document = new Document(code);
			textEdit.apply(document);

			// Return the formatted code
			return document.get();
		} 
		catch (MalformedTreeException | BadLocationException e)
		{
			logger.error("Error during code formatting: " + e.getMessage(), e);
			throw new RuntimeException("Error formatting code: " + e.getMessage(), e);
		}
	}


	public String createFileAndOpen(String projectName, String filePath, String content) 
	{
		Objects.requireNonNull(projectName);
		Objects.requireNonNull(filePath);
		
		if (projectName.isEmpty()) 
	    {
	        throw new IllegalArgumentException("Error: Project name cannot be empty.");
	    }
	    if (filePath.isEmpty()) 
	    {
	    	throw new IllegalArgumentException( "Error: File path cannot be empty.");
	    }

	    if (content == null) 
	    {
	        content = ""; // Allow empty content
	    }
	    
	    try
	    {
	        // Get the project
	        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
	        IProject project = root.getProject(projectName);
	        
	        if (!project.exists()) 
	        {
	            throw new RuntimeException( "Error: Project '" + projectName + "' does not exist.");
	        }
	        
	        if (!project.isOpen()) 
	        {
	            project.open(null);
	        }
	        
	        // Fix the path by removing any leading slash
	        String normalizedPath = filePath;
	        while (normalizedPath.startsWith("/") || normalizedPath.startsWith("\\")) 
	        {
	            normalizedPath = normalizedPath.substring(1);
	        }
	        
	        if (normalizedPath.isEmpty()) 
	        {
	        	throw new RuntimeException("Error: Invalid file path. Path cannot be empty after normalization.");
	        }
	        
	        // Create the file path
	        final IFile file = project.getFile(normalizedPath);
	        
	        if (file.exists()) 
	        {
	        	throw new RuntimeException("Error: File '" + normalizedPath + "' already exists in project '" + projectName + "'.");
	        }
	        
	        // Create parent folders if they don't exist
	        IContainer parent = file.getParent();
	        if (parent instanceof IFolder && !parent.exists()) 
	        {
	            ResourceUtilities.createFolderHierarchy((IFolder) parent);
	        }
	        
	        // Create the file with content
	        ByteArrayInputStream source = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
	        file.create(source, true, null);
	        
	        // Try to open the file in the editor, but don't fail if we can't
	        sync.asyncExec(() -> safeOpenEditor(file) );
	        
	        return "Success: File '" + normalizedPath + "' created in project '" + projectName + "'. " +
	               "The file may have been opened in the editor if a UI session is available.";
	    } catch (CoreException e) 
	    {
	        throw new RuntimeException( e );
	    }
	}
	
	/** 
	 * Safely opens a file in the editor, handling null cases,
	 * and brings the editor into focus.
	 * @param file The file to open 
	 */
	private void safeOpenEditor(IFile file) 
	{
        Optional.ofNullable( PlatformUI.getWorkbench() )
                .map( IWorkbench::getActiveWorkbenchWindow )
                .map(IWorkbenchWindow::getActivePage)
                .ifPresent( page -> {
					try 
					{
						// Open the editor and get the editor reference
						var editor = IDE.openEditor(page, file);
						// Set focus to the editor
						if (editor != null) 
						{
							editor.setFocus();
						}
					} 
					catch (PartInitException e) 
					{
				        // Log but don't propagate
				        logger.error(e.getMessage(), e);
					}
				});
	}
}