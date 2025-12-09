package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
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
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.FileEditorInput;
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
    
    @Inject
    CodeAnalysisService codeAnalysisService;
    
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
	        
	        if (folder.exists()) 
	        {
	            return "Directory '" + normalizedPath + "' already exists in project '" + projectName + "'.";
	        }
	        
	        // Create the folder hierarchy
	        ResourceUtilities.createFolderHierarchy(folder);
	        
	        // Add this line to refresh the parent container (or project)
	        folder.getParent().refreshLocal(IResource.DEPTH_INFINITE, null);

	        
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
	        // Try to refresh the editor if the file is open
	        sync.syncExec(() -> 
	        {
	            safeOpenEditor(file);
	            refreshEditor(file);
	        });
	        
	        // Use Eclipse's built-in local history to undo changes
	        IFileState[] history = file.getHistory(null);
	        if (history == null || history.length == 0) 
	        {
	            throw new RuntimeException("Error: No edit history found for file '" + filePath + "'.");
	        }
	        
	        // Get the most recent history state
	        IFileState previousState = history[0];
	        
	        var previousContentString = new String( ResourceUtilities.readInputStream(previousState.getContents()), Charset.forName( file.getCharset() ));
	        String diff = generateCodeDiff(projectName, filePath, previousContentString, 3 );
	        
	        // Restore the file from the previous state
	        try (ByteArrayInputStream source = new ByteArrayInputStream(previousContentString.getBytes(Charset.forName( file.getCharset() )))) 
	        {
	            file.setContents(source, IResource.FORCE, null);
	        }
	        
	        // Add this line to refresh the parent container (or project)
	        file.getParent().refreshLocal(IResource.DEPTH_ONE, null);

	        // Try to refresh the editor if the file is open
	        sync.asyncExec(() -> 
	        {
	            refreshEditor(file);
	        });
	        
	        return "Success: Undid last edit in file '" + filePath + "' in project '" + projectName + "'." +
	        	   "Updated file content:\n```" + ResourceUtilities.readFileContent(file) + "\n```";
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
	        	throw new RuntimeException("Error: Project '" + projectName + "' is closed.");
	        }
	        
	        IPath path = IPath.fromPath(Path.of(filePath));
	        IFile file = project.getFile(path);
	        
	        if (!file.exists()) 
	        {
	            throw new RuntimeException("Error: File '" + filePath + "' does not exist in project '" + projectName + "'.");
	        }
	        // Try to refresh the editor if the file is open
	        sync.syncExec(() -> 
	        {
	            safeOpenEditor(file);
	            refreshEditor(file);
	        });
	        
	        // Read the file line by line for better range handling
	        List<String> lines = ResourceUtilities.readFileLines(file);
	        
	        // Validate line range
	        int totalLines = lines.size();
	        
	        // Convert to 0-based indexing for internal use
	        int effectiveStartLine = (startLine != null) ? Math.max(0, startLine - 1) : 0;
	        int effectiveEndLine = (endLine != null) ? Math.min(totalLines - 1, endLine - 1) : totalLines - 1;
	        
	        // Validate range
	        if (effectiveStartLine >= totalLines) 
	        {
	            throw new RuntimeException("Error: Start line " + startLine + " is beyond the end of the file (total lines: " + totalLines + ").");
	        }
	        effectiveEndLine = Math.min(effectiveEndLine, totalLines - 1);
	        
	        if (effectiveStartLine > effectiveEndLine) 
	        {
	            throw new RuntimeException("Error: Start line cannot be greater than end line.");
	        }
	        
	        // Store the content as a single string for the range we're working with
	        StringBuilder rangeContent = new StringBuilder();
	        for (int i = effectiveStartLine; i <= effectiveEndLine; i++) 
	        {
	            rangeContent.append(lines.get(i));
	            if (i < effectiveEndLine) 
	            {
	                rangeContent.append("\n");
	            }
	        }
	        
	        String rangeText = rangeContent.toString();
	        
	        // Check if the range contains the target string
	        if (!rangeText.contains(oldString)) 
	        {
	            String rangeInfo = "";
	            if (startLine != null || endLine != null) 
	            {
	                rangeInfo = " within range (lines " + (startLine != null ? startLine : 1) + " to " + (endLine != null ? endLine : totalLines) + ")";
	            }
	            throw new RuntimeException("Error: The specified string was not found in the file" + rangeInfo + ".");
	        }
	        
	        // Replace the string in the range
	        String replacedRangeText = rangeText.replace(oldString, newString);
	        
	        // Build the new content
	        StringBuilder modifiedContent = new StringBuilder();
	        
	        // Add lines before the range
	        for (int i = 0; i < effectiveStartLine; i++) 
	        {
	            modifiedContent.append(lines.get(i)).append("\n");
	        }
	        
	        // Add the modified range content
	        modifiedContent.append(replacedRangeText);
	        
	        // Add a newline if we're not at the end of the file
	        if (effectiveEndLine < totalLines - 1) 
	        {
	            modifiedContent.append("\n");
	        }
	        
	        // Add lines after the range
	        for (int i = effectiveEndLine + 1; i < totalLines; i++) 
	        {
	            modifiedContent.append(lines.get(i));
                modifiedContent.append("\n");
	        }
	        // Add new line at the end of the last line
	        if ( !modifiedContent.toString().endsWith("\n") )
	        {
	        	modifiedContent.append("\n");
	        }
	        
	        var modifiedContentString = modifiedContent.toString();
	        String diff = generateCodeDiff(projectName, filePath, modifiedContentString, 3);
	        
	        // Write back to the file
	        try (ByteArrayInputStream source = new ByteArrayInputStream(modifiedContentString.getBytes(Charset.forName( file.getCharset() )))) 
	        {
	            file.setContents(source, IResource.FORCE, null);
	        }
	        
	        // Try to open the file in the editor, but don't fail if we can't
	        // Try to refresh the editor if the file is open
	        sync.asyncExec(() -> {
	        	refreshEditor(file);
	        });
	
	        
	        return "Success: String replaced in file '" + filePath + "' in project '" + projectName + "'. "  + "'.\n" +
	        	   "Changes:\n```diff\n" + diff + "\n```";
	        
	    } 
	    catch (CoreException | IOException e) 
	    {
	        throw new RuntimeException(e);
	    }
	}

    
    
	/**
	 * Inserts content before a specific line in an existing file.
	 * The new content will be inserted BEFORE the specified line, and existing content 
	 * at that line and below will be shifted down.
	 * 
	 * @param projectName The name of the project containing the file
	 * @param filePath The path to the file relative to the project root
	 * @param content The content to insert into the file
	 * @param atLine The line number before which to insert the text (1-based index, 0 or 1 for beginning of file)
	 * @return A status message indicating success or failure
	 */
	public String insertIntoFile(String projectName, String filePath, String content, int atLine) 
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
	        	throw new RuntimeException("Error: Project '" + projectName + "' is closed.");
	        }
	        IPath path = IPath.fromPath(Path.of(filePath));
	        IFile file = project.getFile(path);
	        
	        if (!file.exists()) 
	        {
	            throw new RuntimeException( "Error: File '" + filePath + "' does not exist in project '" + projectName + "'.");
	        }
	        // Try to refresh the editor if the file is open
	        sync.syncExec(() -> 
	        {
	        	safeOpenEditor(file);
	        	refreshEditor(file);
	        });
	        List<String> lines = ResourceUtilities.readFileLines(file);
	        
	        // convert to 0-based indexing
	        var effectiveAtLine = atLine - 1;
	        // Validate line number
	        if (effectiveAtLine < 0 || effectiveAtLine > lines.size() ) 
	        {
	            throw new RuntimeException("Error: Invalid line number " + atLine + ". File has " + lines.size() + " lines.");
	        }
	        
	        // Build the new content
	        StringBuilder modifiedContent = new StringBuilder();
	        
	        // Add lines before insertion point
	        for (int i = 0; i < effectiveAtLine; i++) 
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
	        for (int i = effectiveAtLine; i < lines.size(); i++) 
	        {
	            modifiedContent.append(lines.get(i) );
	            if (i < lines.size() - 1) 
	            {
	                modifiedContent.append("\n");
	            }
	        }
	        
	        var modifiedContentString = modifiedContent.toString();
	        String diff = generateCodeDiff(projectName, filePath, modifiedContentString, 3);

	        // Write back to the file
	        try (ByteArrayInputStream source = new ByteArrayInputStream(modifiedContentString.getBytes(Charset.forName( file.getCharset() )))) 
	        {
	            file.setContents(source, IResource.FORCE, null);
	        }
	        
	        // Add this line to refresh the parent container (or project)
	        file.getParent().refreshLocal(IResource.DEPTH_ONE, null);

	        // Try to open the file in the editor, but don't fail if we can't
	        // Try to refresh the editor if the file is open
	        sync.syncExec( () -> {
	        	refreshEditor(file);	
	        });
	        
	        return "Success: file '" + filePath + "' in project '" + projectName + "' was updated.\n" +
	        	   "Changes:\n```diff\n" + diff + "\n```";

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
	    	file.getParent().refreshLocal(IResource.DEPTH_ONE, null);
	        
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
	                        	if (editor instanceof ITextEditor) 
	                        	{
	                        		((ITextEditor) editor).getDocumentProvider().resetDocument(input);
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
            
	        // Try to refresh the editor if the file is open
	        sync.syncExec(() -> 
	        {
	            safeOpenEditor(file);
	            refreshEditor(file);
	        });

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
	        	throw new RuntimeException("Error: Project '" + projectName + "' is closed.");
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
	        ByteArrayInputStream source = new ByteArrayInputStream(content.getBytes(Charset.forName( project.getDefaultCharset() )));
			file.create(source, true, null);
	        // Add this line to refresh the parent container (or project)
	        file.getParent().refreshLocal(IResource.DEPTH_ONE, null);
			
	        // Try to open the file in the editor, but don't fail if we can't
	        sync.syncExec(() -> {
	        	safeOpenEditor(file);
	        	refreshEditor(file);
	        });
	        
	        return "Success: File '" + normalizedPath + "' created in project '" + projectName + "'.";
	    } 
	    catch ( CoreException e) 
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

	


	/**
	 * Replaces specified lines in a file with new content.
	 * 
	 * @param projectName The name of the project containing the file
	 * @param filePath The path to the file relative to the project root
	 * @param startLine The line number to start replacement from (1-based index)
	 * @param endLine The line number to end replacement at (inclusive, 1-based index)
	 * @param replacementContent The new content to insert in place of the deleted lines
	 * @return A status message indicating success or failure
	 */
	public String replaceLines(String projectName, String filePath,  String replacementContent, int startLine, int endLine) 
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
	    
	    if (replacementContent == null)
	    {
	        replacementContent = ""; // Allow empty replacement content
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
	        // Try to refresh the editor if the file is open
	        sync.syncExec(() -> 
	        {
	        	safeOpenEditor(file);
	        	refreshEditor(file);
	        });
	        
	        // Read file content
	        List<String> lines = ResourceUtilities.readFileLines(file);
	        
	        // Validate line numbers
	        int totalLines = lines.size();
	        int startLine0 = startLine -1;
	        int endLine0   = endLine - 1;
	        if (startLine0 < 0 || endLine0 < startLine0 ||  startLine0 >= totalLines ) 
	        {
	            throw new IllegalArgumentException("Error: Invalid line range specified.");
	        }
	        
	        // Ensure endLine is within bounds
	        endLine0 = Math.max( Math.min( endLine0, totalLines - 1), 0 );
	        
	        StringBuilder modifiedContent = new StringBuilder();
	        
	        // Store lines before startLine
	        for (int i = 0; i < startLine0; i++) 
	        {
	            modifiedContent.append( lines.get(i) );
                modifiedContent.append("\n");
	        }
	        // Add the replacement content
	        modifiedContent.append(replacementContent);
	        if (!replacementContent.isEmpty() && !replacementContent.endsWith("\n")) 
	        {
	            modifiedContent.append("\n");
	        }
	        // Add lines after replacement
	        for (int i = endLine0 + 1; i < totalLines; i++) 
	        {
	            modifiedContent.append(lines.get(i));
                modifiedContent.append("\n");
	        }
	        
	        var modifiedContentString = modifiedContent.toString();
	        // Generate diff between old and new versions
	        String diff = generateCodeDiff(projectName, filePath, modifiedContentString, 3);

	        
			// Write back to the file
	        try (ByteArrayInputStream source = new ByteArrayInputStream(
	        		modifiedContentString.getBytes( Charset.forName(file.getCharset())))) 
	        {
	            file.setContents(source, IResource.FORCE, null);
	        }
	        
	        // Add this line to refresh the parent container (or project)
	        file.getParent().refreshLocal(IResource.DEPTH_ONE, null);

	        // Try to open the file in the editor and refresh it
	        sync.syncExec(() -> {
	            refreshEditor(file);
	        });
	        
	        
	        return "Success: file '" + filePath + "' in project '" + projectName + "' was updated.\n" +
	        	   "Changes:\n```diff\n" + diff + "\n```";
	    } 
	    catch (CoreException | IOException e) 
	    {
	        throw new RuntimeException(e);
	    }
	}

    public String renameFile( String projectName, String filePath, String newFileName )
    {
        Objects.requireNonNull(projectName);
        Objects.requireNonNull(filePath);
        Objects.requireNonNull(newFileName);
        
        if (projectName.isEmpty()) 
        {
            throw new IllegalArgumentException("Error: Project name cannot be empty.");
        }
        if (filePath.isEmpty()) 
        {
            throw new IllegalArgumentException("Error: File path cannot be empty.");
        }
        if (newFileName.isEmpty()) 
        {
            throw new IllegalArgumentException("Error: New file name cannot be empty.");
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
                throw new RuntimeException("Error: Project '" + projectName + "' is closed.");
            }
            
            IPath path = IPath.fromPath(Path.of(filePath));
            IFile file = project.getFile(path);
            
            if (!file.exists()) 
            {
                throw new RuntimeException("Error: File '" + filePath + "' does not exist in project '" + projectName + "'.");
            }
            
            // Try to refresh the editor if the file is open
            sync.syncExec(() -> 
            {
                safeOpenEditor(file);
                refreshEditor(file);
            });
            
            // Get the parent folder and construct the new file path
            IContainer parent = file.getParent();
            IPath newPath = parent.getFullPath().append(newFileName);
            
            // Check if a file with the new name already exists
            IFile newFile = root.getFile(newPath);
            if (newFile.exists()) 
            {
                throw new RuntimeException("Error: A file named '" + newFileName + "' already exists in the same directory.");
            }
            
            // Perform the rename operation
            file.move(newPath, IResource.FORCE, null);
            
            // Refresh the parent container
            parent.refreshLocal(IResource.DEPTH_ONE, null);
            
            // Try to open the renamed file in the editor
            sync.asyncExec(() -> {
                safeOpenEditor(newFile);
            });
            
            return "Success: File '" + filePath + "' renamed to '" + newFileName + "' in project '" + projectName + "'.";
        } 
        catch (CoreException e) 
        {
            throw new RuntimeException(e);
        }
    }

    public String deleteFile(String projectName, String filePath)
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
                throw new RuntimeException("Error: Project '" + projectName + "' is closed.");
            }
            
            IPath path = IPath.fromPath(Path.of(filePath));
            IFile file = project.getFile(path);
            
            if (!file.exists()) 
            {
                throw new RuntimeException("Error: File '" + filePath + "' does not exist in project '" + projectName + "'.");
            }
            
            // Close the editor if the file is open
            sync.syncExec(() -> 
            {
                IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                if (page != null) 
                {
                    IEditorPart editor = page.findEditor(new FileEditorInput(file));
                    if (editor != null) 
                    {
                        page.closeEditor(editor, false);
                    }
                }
            });
            
            // Delete the file
            file.delete(true, null);
            
            // Refresh the parent container
            IContainer parent = file.getParent();
            parent.refreshLocal(IResource.DEPTH_ONE, null);
            
            return "Success: File '" + filePath + "' deleted from project '" + projectName + "'.";
        } 
        catch (CoreException e) 
        {
            throw new RuntimeException(e);
        }
    }

    public String replaceFileContent(String projectName, String filePath, String content)
    {
        Objects.requireNonNull(projectName);
        Objects.requireNonNull(filePath);
        Objects.requireNonNull(content);
        
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
                throw new RuntimeException("Error: Project '" + projectName + "' is closed.");
            }
            
            IPath path = IPath.fromPath(Path.of(filePath));
            IFile file = project.getFile(path);
            
            if (!file.exists()) 
            {
                throw new RuntimeException("Error: File '" + filePath + "' does not exist in project '" + projectName + "'.");
            }
            
            // Backup the file before modification
            backupFile(file);
            
            // Replace the file content
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
            file.setContents(inputStream, IResource.FORCE, null);
            
            // Refresh the file
            file.refreshLocal(IResource.DEPTH_ZERO, null);
            
            // Refresh the editor if the file is open
            sync.asyncExec(() -> 
            {
                safeOpenEditor(file);
                refreshEditor(file);
            });
            
            return "Success: Content of file '" + filePath + "' replaced in project '" + projectName + "'.";
        } 
        catch (CoreException e) 
        {
            throw new RuntimeException(e);
        }
    }

    public String deleteLinesInFile(String projectName, String filePath, int startLine, int endLine)
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
        if (startLine < 1) 
        {
            throw new IllegalArgumentException("Error: Start line must be at least 1.");
        }
        if (endLine < startLine) 
        {
            throw new IllegalArgumentException("Error: End line must be greater than or equal to start line.");
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
                throw new RuntimeException("Error: Project '" + projectName + "' is closed.");
            }
            
            IPath path = IPath.fromPath(Path.of(filePath));
            IFile file = project.getFile(path);
            
            if (!file.exists()) 
            {
                throw new RuntimeException("Error: File '" + filePath + "' does not exist in project '" + projectName + "'.");
            }
            
            // Backup the file before modification
            backupFile(file);
            
            // Read the file content
            String fileContent = new String(file.getContents().readAllBytes(), StandardCharsets.UTF_8);
            String[] lines = fileContent.split("\r?\n", -1);
            
            // Validate line numbers
            if (startLine > lines.length) 
            {
                throw new IllegalArgumentException("Error: Start line " + startLine + " is beyond the file length (" + lines.length + " lines).");
            }
            if (endLine > lines.length) 
            {
                throw new IllegalArgumentException("Error: End line " + endLine + " is beyond the file length (" + lines.length + " lines).");
            }
            
            // Build new content without the deleted lines
            StringBuilder newContent = new StringBuilder();
            for (int i = 0; i < lines.length; i++) 
            {
                int lineNum = i + 1; // Convert to 1-based
                if (lineNum < startLine || lineNum > endLine) 
                {
                    newContent.append(lines[i]);
                    if (i < lines.length - 1) 
                    {
                        newContent.append("\n");
                    }
                }
            }
            
            // Write the new content back to the file
            byte[] bytes = newContent.toString().getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
            file.setContents(inputStream, IResource.FORCE, null);
            
            // Refresh the file
            file.refreshLocal(IResource.DEPTH_ZERO, null);
            
            // Refresh the editor if the file is open
            sync.asyncExec(() -> 
            {
                safeOpenEditor(file);
                refreshEditor(file);
            });
            
            int deletedCount = endLine - startLine + 1;
            return "Success: Deleted " + deletedCount + " line(s) (lines " + startLine + " to " + endLine + ") from file '" + filePath + "' in project '" + projectName + "'.";
        } 
        catch (CoreException | IOException e) 
        {
            throw new RuntimeException(e);
        }
    }

	/**
	 * Creates a backup of the file by triggering Eclipse's local history mechanism.
	 * Eclipse automatically maintains file history when content changes occur.
	 * 
	 * @param file The file to backup
	 * @throws CoreException if backup operation fails
	 */
	private void backupFile(IFile file) throws CoreException 
	{
	    // Eclipse automatically maintains local history when file.setContents() is called
	    // We just need to ensure the file is synchronized
	    file.refreshLocal(IResource.DEPTH_ZERO, null);
	}
}
