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
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation.IChooseImportQuery;
import org.eclipse.jdt.core.refactoring.IJavaRefactorings;
import org.eclipse.jdt.core.refactoring.descriptors.MoveDescriptor;
import org.eclipse.jdt.core.refactoring.descriptors.RenameJavaElementDescriptor;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringContribution;
import org.eclipse.ltk.core.refactoring.RefactoringCore;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
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

import com.github.gradusnikov.eclipse.assistai.completion.CompletionContext;
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
	 * Inserts content after a specific line in an existing file.
	 * 
	 * @param projectName The name of the project containing the file
	 * @param filePath The path to the file relative to the project root
	 * @param content The content to insert into the file
	 * @param atLine The line number after which to insert the text (0 for beginning of file)
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

    /**
     * Renames a Java compilation unit (class/interface/enum) using Eclipse's refactoring mechanism.
     * This updates the class name, file name, and all references throughout the project.
     * 
     * @param projectName The name of the project containing the Java file
     * @param filePath The path to the Java file relative to the project root
     * @param newTypeName The new name for the type (without .java extension)
     * @return A status message indicating success or failure
     */
    public String refactorRenameJavaType(String projectName, String filePath, String newTypeName)
    {
        Objects.requireNonNull(projectName);
        Objects.requireNonNull(filePath);
        Objects.requireNonNull(newTypeName);
        
        if (projectName.isEmpty()) 
        {
            throw new IllegalArgumentException("Error: Project name cannot be empty.");
        }
        if (filePath.isEmpty()) 
        {
            throw new IllegalArgumentException("Error: File path cannot be empty.");
        }
        if (newTypeName.isEmpty()) 
        {
            throw new IllegalArgumentException("Error: New type name cannot be empty.");
        }
        
        // Remove .java extension if provided
        if (newTypeName.endsWith(".java"))
        {
            newTypeName = newTypeName.substring(0, newTypeName.length() - 5);
        }
        
        final String finalNewTypeName = newTypeName;
        
        try 
        {
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
            
            // Get the Java project
            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null || !javaProject.exists())
            {
                throw new RuntimeException("Error: Project '" + projectName + "' is not a Java project.");
            }
            
            IPath path = IPath.fromPath(Path.of(filePath));
            IFile file = project.getFile(path);
            
            if (!file.exists()) 
            {
                throw new RuntimeException("Error: File '" + filePath + "' does not exist in project '" + projectName + "'.");
            }
            
            if (!filePath.endsWith(".java"))
            {
                throw new RuntimeException("Error: File '" + filePath + "' is not a Java file. Use renameFile for non-Java files.");
            }
            
            // Get the compilation unit
            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit))
            {
                throw new RuntimeException("Error: Could not resolve Java compilation unit for file '" + filePath + "'.");
            }
            
            ICompilationUnit compilationUnit = (ICompilationUnit) javaElement;
            
            // Get the primary type
            IType primaryType = compilationUnit.findPrimaryType();
            if (primaryType == null)
            {
                throw new RuntimeException("Error: Could not find primary type in file '" + filePath + "'.");
            }
            
            String oldTypeName = primaryType.getElementName();
            
            // Close the editor if the file is open (to avoid conflicts)
            sync.syncExec(() -> 
            {
                IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                if (page != null) 
                {
                    IEditorPart editor = page.findEditor(new FileEditorInput(file));
                    if (editor != null) 
                    {
                        page.closeEditor(editor, true); // save before closing
                    }
                }
            });
            
            // Create the rename refactoring descriptor
            RefactoringContribution contribution = RefactoringCore.getRefactoringContribution(IJavaRefactorings.RENAME_TYPE);
            RenameJavaElementDescriptor descriptor = (RenameJavaElementDescriptor) contribution.createDescriptor();
            
            descriptor.setJavaElement(primaryType);
            descriptor.setNewName(finalNewTypeName);
            descriptor.setUpdateReferences(true);
            descriptor.setUpdateSimilarDeclarations(false);
            descriptor.setUpdateTextualOccurrences(false);
            
            // Create and validate the refactoring
            RefactoringStatus status = new RefactoringStatus();
            Refactoring refactoring = descriptor.createRefactoring(status);
            
            if (status.hasFatalError())
            {
                throw new RuntimeException("Error creating refactoring: " + status.getMessageMatchingSeverity(RefactoringStatus.FATAL));
            }
            
            // Check initial conditions
            IProgressMonitor monitor = new NullProgressMonitor();
            RefactoringStatus checkStatus = refactoring.checkInitialConditions(monitor);
            if (checkStatus.hasFatalError())
            {
                throw new RuntimeException("Error in initial conditions: " + checkStatus.getMessageMatchingSeverity(RefactoringStatus.FATAL));
            }
            
            // Check final conditions
            checkStatus = refactoring.checkFinalConditions(monitor);
            if (checkStatus.hasFatalError())
            {
                throw new RuntimeException("Error in final conditions: " + checkStatus.getMessageMatchingSeverity(RefactoringStatus.FATAL));
            }
            
            // Perform the refactoring
            Change change = refactoring.createChange(monitor);
            change.perform(monitor);
            
            // Refresh the project
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
            
            // Build the new file path to open
            String newFilePath = filePath.replace(oldTypeName + ".java", finalNewTypeName + ".java");
            IFile newFile = project.getFile(IPath.fromPath(Path.of(newFilePath)));
            
            // Open the renamed file in the editor
            sync.asyncExec(() -> {
                if (newFile.exists())
                {
                    safeOpenEditor(newFile);
                }
            });
            
            StringBuilder result = new StringBuilder();
            result.append("Success: Java type '").append(oldTypeName).append("' renamed to '").append(finalNewTypeName).append("'.\n");
            result.append("File renamed from '").append(filePath).append("' to '").append(newFilePath).append("'.\n");
            result.append("All references have been updated.");
            
            return result.toString();
        } 
        catch (CoreException e) 
        {
            throw new RuntimeException("Error during refactoring: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    /**
     * Moves a Java compilation unit to a different package using Eclipse's refactoring mechanism.
     * This updates the package declaration and all references throughout the workspace.
     * 
     * @param projectName The name of the project containing the Java file
     * @param filePath The path to the Java file relative to the project root
     * @param targetPackage The fully qualified name of the target package (e.g., "com.example.newpackage")
     * @return A status message indicating success or failure
     */
    public String refactorMoveJavaType(String projectName, String filePath, String targetPackage)
    {
        Objects.requireNonNull(projectName);
        Objects.requireNonNull(filePath);
        Objects.requireNonNull(targetPackage);
        
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
            
            // Get the Java project
            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null || !javaProject.exists())
            {
                throw new RuntimeException("Error: Project '" + projectName + "' is not a Java project.");
            }
            
            IPath path = IPath.fromPath(Path.of(filePath));
            IFile file = project.getFile(path);
            
            if (!file.exists()) 
            {
                throw new RuntimeException("Error: File '" + filePath + "' does not exist in project '" + projectName + "'.");
            }
            
            if (!filePath.endsWith(".java"))
            {
                throw new RuntimeException("Error: File '" + filePath + "' is not a Java file.");
            }
            
            // Get the compilation unit
            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit))
            {
                throw new RuntimeException("Error: Could not resolve Java compilation unit for file '" + filePath + "'.");
            }
            
            ICompilationUnit compilationUnit = (ICompilationUnit) javaElement;
            IType primaryType = compilationUnit.findPrimaryType();
            
            if (primaryType == null)
            {
                throw new RuntimeException("Error: Could not find primary type in file '" + filePath + "'.");
            }
            
            String typeName = primaryType.getElementName();
            String oldPackageName = primaryType.getPackageFragment().getElementName();
            
            // Find or create the target package
            IPackageFragment targetPackageFragment = findOrCreatePackage(javaProject, targetPackage);
            
            // Close the editor if the file is open
            sync.syncExec(() -> 
            {
                IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                if (page != null) 
                {
                    IEditorPart editor = page.findEditor(new FileEditorInput(file));
                    if (editor != null) 
                    {
                        page.closeEditor(editor, true);
                    }
                }
            });
            
            // Create the move refactoring descriptor
            RefactoringContribution contribution = RefactoringCore.getRefactoringContribution(IJavaRefactorings.MOVE);
            MoveDescriptor descriptor = (MoveDescriptor) contribution.createDescriptor();
            
            descriptor.setDestination(targetPackageFragment);
            descriptor.setMoveResources(new IFile[0], new IFolder[0], new ICompilationUnit[] { compilationUnit });
            descriptor.setUpdateReferences(true);
            descriptor.setUpdateQualifiedNames(false);
            
            // Create and validate the refactoring
            RefactoringStatus status = new RefactoringStatus();
            Refactoring refactoring = descriptor.createRefactoring(status);
            
            if (status.hasFatalError())
            {
                throw new RuntimeException("Error creating refactoring: " + status.getMessageMatchingSeverity(RefactoringStatus.FATAL));
            }
            
            // Check initial conditions
            IProgressMonitor monitor = new NullProgressMonitor();
            RefactoringStatus checkStatus = refactoring.checkInitialConditions(monitor);
            if (checkStatus.hasFatalError())
            {
                throw new RuntimeException("Error in initial conditions: " + checkStatus.getMessageMatchingSeverity(RefactoringStatus.FATAL));
            }
            
            // Check final conditions
            checkStatus = refactoring.checkFinalConditions(monitor);
            if (checkStatus.hasFatalError())
            {
                throw new RuntimeException("Error in final conditions: " + checkStatus.getMessageMatchingSeverity(RefactoringStatus.FATAL));
            }
            
            // Perform the refactoring
            Change change = refactoring.createChange(monitor);
            change.perform(monitor);
            
            // Refresh the project
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
            
            // Build the new file path
            String packagePath = targetPackage.replace('.', '/');
            IPackageFragmentRoot sourceRoot = (IPackageFragmentRoot) compilationUnit.getParent().getParent();
            String sourceRootPath = sourceRoot.getResource().getProjectRelativePath().toString();
            String newFilePath = sourceRootPath + "/" + packagePath + "/" + typeName + ".java";
            
            IFile newFile = project.getFile(IPath.fromPath(Path.of(newFilePath)));
            
            // Open the moved file in the editor
            sync.asyncExec(() -> {
                if (newFile.exists())
                {
                    safeOpenEditor(newFile);
                }
            });
            
            StringBuilder result = new StringBuilder();
            result.append("Success: Java type '").append(typeName).append("' moved from package '").append(oldPackageName);
            result.append("' to '").append(targetPackage).append("'.\n");
            result.append("New file location: '").append(newFilePath).append("'.\n");
            result.append("All references have been updated.");
            
            return result.toString();
        } 
        catch (CoreException e) 
        {
            throw new RuntimeException("Error during refactoring: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    /**
     * Renames a Java package using Eclipse's refactoring mechanism.
     * This renames the package directory, updates all package declarations in contained files,
     * and updates all references throughout the workspace.
     * 
     * @param projectName The name of the project containing the package
     * @param packageName The current fully qualified package name (e.g., "com.example.oldpackage")
     * @param newPackageName The new package name (can be just the last segment or full path)
     * @return A status message indicating success or failure
     */
    public String refactorRenamePackage(String projectName, String packageName, String newPackageName)
    {
        Objects.requireNonNull(projectName);
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(newPackageName);
        
        if (projectName.isEmpty()) 
        {
            throw new IllegalArgumentException("Error: Project name cannot be empty.");
        }
        if (packageName.isEmpty()) 
        {
            throw new IllegalArgumentException("Error: Package name cannot be empty.");
        }
        if (newPackageName.isEmpty()) 
        {
            throw new IllegalArgumentException("Error: New package name cannot be empty.");
        }
        
        try 
        {
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
            
            // Get the Java project
            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null || !javaProject.exists())
            {
                throw new RuntimeException("Error: Project '" + projectName + "' is not a Java project.");
            }
            
            // Find the package
            IPackageFragment packageFragment = findPackage(javaProject, packageName);
            if (packageFragment == null)
            {
                throw new RuntimeException("Error: Package '" + packageName + "' not found in project '" + projectName + "'.");
            }
            
            // Close all editors for files in this package
            sync.syncExec(() -> 
            {
                try 
                {
                    IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                    if (page != null) 
                    {
                        for (ICompilationUnit cu : packageFragment.getCompilationUnits())
                        {
                            IFile file = (IFile) cu.getResource();
                            IEditorPart editor = page.findEditor(new FileEditorInput(file));
                            if (editor != null) 
                            {
                                page.closeEditor(editor, true);
                            }
                        }
                    }
                }
                catch (JavaModelException e)
                {
                    logger.error("Error closing editors: " + e.getMessage());
                }
            });
            
            // Create the rename refactoring descriptor
            RefactoringContribution contribution = RefactoringCore.getRefactoringContribution(IJavaRefactorings.RENAME_PACKAGE);
            RenameJavaElementDescriptor descriptor = (RenameJavaElementDescriptor) contribution.createDescriptor();
            
            descriptor.setJavaElement(packageFragment);
            descriptor.setNewName(newPackageName);
            descriptor.setUpdateReferences(true);
            descriptor.setUpdateTextualOccurrences(false);
            descriptor.setUpdateHierarchy(true);
            
            // Create and validate the refactoring
            RefactoringStatus status = new RefactoringStatus();
            Refactoring refactoring = descriptor.createRefactoring(status);
            
            if (status.hasFatalError())
            {
                throw new RuntimeException("Error creating refactoring: " + status.getMessageMatchingSeverity(RefactoringStatus.FATAL));
            }
            
            // Check initial conditions
            IProgressMonitor monitor = new NullProgressMonitor();
            RefactoringStatus checkStatus = refactoring.checkInitialConditions(monitor);
            if (checkStatus.hasFatalError())
            {
                throw new RuntimeException("Error in initial conditions: " + checkStatus.getMessageMatchingSeverity(RefactoringStatus.FATAL));
            }
            
            // Check final conditions
            checkStatus = refactoring.checkFinalConditions(monitor);
            if (checkStatus.hasFatalError())
            {
                throw new RuntimeException("Error in final conditions: " + checkStatus.getMessageMatchingSeverity(RefactoringStatus.FATAL));
            }
            
            // Perform the refactoring
            Change change = refactoring.createChange(monitor);
            change.perform(monitor);
            
            // Refresh the project
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
            
            StringBuilder result = new StringBuilder();
            result.append("Success: Package '").append(packageName).append("' renamed to '").append(newPackageName).append("'.\n");
            result.append("All package declarations and references have been updated.");
            
            return result.toString();
        } 
        catch (CoreException e) 
        {
            throw new RuntimeException("Error during refactoring: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    /**
     * Organizes imports in a Java file using Eclipse's organize imports mechanism.
     * This removes unused imports, adds missing imports, and sorts them according to project settings.
     * 
     * @param projectName The name of the project containing the Java file
     * @param filePath The path to the Java file relative to the project root
     * @return A status message indicating success or failure with details of changes made
     */
    public String organizeImports(String projectName, String filePath)
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
            
            // Get the Java project
            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null || !javaProject.exists())
            {
                throw new RuntimeException("Error: Project '" + projectName + "' is not a Java project.");
            }
            
            IPath path = IPath.fromPath(Path.of(filePath));
            IFile file = project.getFile(path);
            
            if (!file.exists()) 
            {
                throw new RuntimeException("Error: File '" + filePath + "' does not exist in project '" + projectName + "'.");
            }
            
            if (!filePath.endsWith(".java"))
            {
                throw new RuntimeException("Error: File '" + filePath + "' is not a Java file.");
            }
            
            // Get the compilation unit
            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit))
            {
                throw new RuntimeException("Error: Could not resolve Java compilation unit for file '" + filePath + "'.");
            }
            
            ICompilationUnit compilationUnit = (ICompilationUnit) javaElement;
            
            // Refresh the editor if the file is open
            sync.syncExec(() -> 
            {
                safeOpenEditor(file);
                refreshEditor(file);
            });
            
            // Get the original imports for comparison
            String originalSource = compilationUnit.getSource();
            String originalImports = extractImportSection(originalSource);
            
            // Create a choose import query that automatically selects the first option
            // This handles cases where there are multiple types with the same simple name
            IChooseImportQuery chooseImportQuery = new IChooseImportQuery() {
                @Override
                public TypeNameMatch[] chooseImports(TypeNameMatch[][] openChoices, ISourceRange[] ranges) {
                    // Automatically choose the first option for each ambiguous import
                    TypeNameMatch[] result = new TypeNameMatch[openChoices.length];
                    for (int i = 0; i < openChoices.length; i++) {
                        if (openChoices[i].length > 0) {
                            result[i] = openChoices[i][0];
                        }
                    }
                    return result;
                }
            };
            
            // Create and run the organize imports operation
            IProgressMonitor monitor = new NullProgressMonitor();
            OrganizeImportsOperation operation = new OrganizeImportsOperation(
                compilationUnit,
                null, // astRoot - will be created automatically
                true, // ignoreLowerCaseNames
                true, // save
                true, // allowSyntaxErrors
                chooseImportQuery
            );
            
            // Execute the operation
            operation.run(monitor);
            
            // Refresh the compilation unit to get the updated source
            compilationUnit.getResource().refreshLocal(IResource.DEPTH_ZERO, monitor);
            
            // Get the new imports for comparison
            String newSource = compilationUnit.getSource();
            String newImports = extractImportSection(newSource);
            
            // Refresh the editor
            sync.asyncExec(() -> {
                refreshEditor(file);
            });
            
            // Build the result message
            StringBuilder result = new StringBuilder();
            result.append("Success: Imports organized in file '").append(filePath).append("'.\n");
            
            if (originalImports.equals(newImports)) {
                result.append("No changes were necessary - imports were already organized.");
            } else {
                result.append("\nUpdated imports:\n```java\n").append(newImports).append("\n```");
            }
            
            return result.toString();
        } 
        catch (CoreException e) 
        {
            throw new RuntimeException("Error during organize imports: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    /**
     * Organizes imports in all Java files within a package.
     * 
     * @param projectName The name of the project containing the package
     * @param packageName The fully qualified package name (e.g., "com.example.mypackage")
     * @return A status message indicating success or failure
     */
    public String organizeImportsInPackage(String projectName, String packageName)
    {
        Objects.requireNonNull(projectName);
        Objects.requireNonNull(packageName);
        
        if (projectName.isEmpty()) 
        {
            throw new IllegalArgumentException("Error: Project name cannot be empty.");
        }
        if (packageName.isEmpty()) 
        {
            throw new IllegalArgumentException("Error: Package name cannot be empty.");
        }
        
        try 
        {
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
            
            // Get the Java project
            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null || !javaProject.exists())
            {
                throw new RuntimeException("Error: Project '" + projectName + "' is not a Java project.");
            }
            
            // Find the package
            IPackageFragment packageFragment = findPackage(javaProject, packageName);
            if (packageFragment == null)
            {
                throw new RuntimeException("Error: Package '" + packageName + "' not found in project '" + projectName + "'.");
            }
            
            // Get all compilation units in the package
            ICompilationUnit[] compilationUnits = packageFragment.getCompilationUnits();
            
            if (compilationUnits.length == 0)
            {
                return "No Java files found in package '" + packageName + "'.";
            }
            
            IProgressMonitor monitor = new NullProgressMonitor();
            int processedCount = 0;
            int changedCount = 0;
            
            // Create a choose import query
            IChooseImportQuery chooseImportQuery = new IChooseImportQuery() {
                @Override
                public TypeNameMatch[] chooseImports(TypeNameMatch[][] openChoices, ISourceRange[] ranges) {
                    TypeNameMatch[] result = new TypeNameMatch[openChoices.length];
                    for (int i = 0; i < openChoices.length; i++) {
                        if (openChoices[i].length > 0) {
                            result[i] = openChoices[i][0];
                        }
                    }
                    return result;
                }
            };
            
            for (ICompilationUnit cu : compilationUnits)
            {
                try
                {
                    String originalSource = cu.getSource();
                    
                    OrganizeImportsOperation operation = new OrganizeImportsOperation(
                        cu,
                        null,
                        true,
                        true,
                        true,
                        chooseImportQuery
                    );
                    
                    operation.run(monitor);
                    cu.getResource().refreshLocal(IResource.DEPTH_ZERO, monitor);
                    
                    String newSource = cu.getSource();
                    if (!originalSource.equals(newSource))
                    {
                        changedCount++;
                    }
                    processedCount++;
                }
                catch (Exception e)
                {
                    logger.warn("Failed to organize imports in " + cu.getElementName() + ": " + e.getMessage());
                }
            }
            
            StringBuilder result = new StringBuilder();
            result.append("Success: Organized imports in package '").append(packageName).append("'.\n");
            result.append("Processed ").append(processedCount).append(" file(s), ");
            result.append(changedCount).append(" file(s) were modified.");
            
            return result.toString();
        } 
        catch (CoreException e) 
        {
            throw new RuntimeException("Error during organize imports: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    /**
     * Extracts the import section from Java source code.
     */
    private String extractImportSection(String source)
    {
        StringBuilder imports = new StringBuilder();
        String[] lines = source.split("\n");
        boolean inImports = false;
        
        for (String line : lines)
        {
            String trimmed = line.trim();
            if (trimmed.startsWith("import "))
            {
                inImports = true;
                imports.append(line).append("\n");
            }
            else if (inImports && !trimmed.isEmpty() && !trimmed.startsWith("import "))
            {
                // End of imports section
                break;
            }
        }
        
        return imports.toString().trim();
    }

    /**
     * Moves a resource (file or folder) to a different location within the project.
     * For Java files, use refactorMoveJavaType instead for proper reference updating.
     * 
     * @param projectName The name of the project containing the resource
     * @param sourcePath The path to the resource relative to the project root
     * @param targetPath The target directory path relative to the project root
     * @return A status message indicating success or failure
     */
    public String moveResource(String projectName, String sourcePath, String targetPath)
    {
        Objects.requireNonNull(projectName);
        Objects.requireNonNull(sourcePath);
        Objects.requireNonNull(targetPath);
        
        if (projectName.isEmpty()) 
        {
            throw new IllegalArgumentException("Error: Project name cannot be empty.");
        }
        if (sourcePath.isEmpty()) 
        {
            throw new IllegalArgumentException("Error: Source path cannot be empty.");
        }
        if (targetPath.isEmpty()) 
        {
            throw new IllegalArgumentException("Error: Target path cannot be empty.");
        }
        
        try 
        {
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
            
            // Normalize paths
            String normalizedSource = sourcePath;
            while (normalizedSource.startsWith("/") || normalizedSource.startsWith("\\")) 
            {
                normalizedSource = normalizedSource.substring(1);
            }
            
            String normalizedTarget = targetPath;
            while (normalizedTarget.startsWith("/") || normalizedTarget.startsWith("\\")) 
            {
                normalizedTarget = normalizedTarget.substring(1);
            }
            
            // Get the source resource
            IResource sourceResource = project.findMember(normalizedSource);
            if (sourceResource == null || !sourceResource.exists())
            {
                throw new RuntimeException("Error: Resource '" + sourcePath + "' does not exist in project '" + projectName + "'.");
            }
            
            // Warn about Java files
            if (sourceResource instanceof IFile && sourcePath.endsWith(".java"))
            {
                logger.warn("Moving Java file without refactoring - references will not be updated. Consider using refactorMoveJavaType instead.");
            }
            
            // Get or create the target folder
            IFolder targetFolder = project.getFolder(normalizedTarget);
            if (!targetFolder.exists())
            {
                ResourceUtilities.createFolderHierarchy(targetFolder);
            }
            
            // Close the editor if moving a file that is open
            if (sourceResource instanceof IFile)
            {
                IFile sourceFile = (IFile) sourceResource;
                sync.syncExec(() -> 
                {
                    IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                    if (page != null) 
                    {
                        IEditorPart editor = page.findEditor(new FileEditorInput(sourceFile));
                        if (editor != null) 
                        {
                            page.closeEditor(editor, true);
                        }
                    }
                });
            }
            
            // Build the destination path
            String resourceName = sourceResource.getName();
            IPath destinationPath = targetFolder.getFullPath().append(resourceName);
            
            // Check if destination already exists
            IResource existingResource = root.findMember(destinationPath);
            if (existingResource != null && existingResource.exists())
            {
                throw new RuntimeException("Error: A resource named '" + resourceName + "' already exists at the destination.");
            }
            
            // Perform the move
            sourceResource.move(destinationPath, IResource.FORCE, new NullProgressMonitor());
            
            // Refresh the affected containers
            sourceResource.getParent().refreshLocal(IResource.DEPTH_ONE, null);
            targetFolder.refreshLocal(IResource.DEPTH_ONE, null);
            
            // If moved a file, open it in the editor
            if (sourceResource instanceof IFile)
            {
                IFile newFile = root.getFile(destinationPath);
                sync.asyncExec(() -> {
                    if (newFile.exists())
                    {
                        safeOpenEditor(newFile);
                    }
                });
            }
            
            String newPath = normalizedTarget + "/" + resourceName;
            return "Success: Resource '" + sourcePath + "' moved to '" + newPath + "' in project '" + projectName + "'.";
        } 
        catch (CoreException e) 
        {
            throw new RuntimeException("Error during move: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    /**
     * Finds a package fragment in the Java project.
     */
    private IPackageFragment findPackage(IJavaProject javaProject, String packageName) throws JavaModelException
    {
        for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots())
        {
            if (root.getKind() == IPackageFragmentRoot.K_SOURCE)
            {
                IPackageFragment fragment = root.getPackageFragment(packageName);
                if (fragment != null && fragment.exists())
                {
                    return fragment;
                }
            }
        }
        return null;
    }

    /**
     * Finds or creates a package fragment in the Java project.
     */
    private IPackageFragment findOrCreatePackage(IJavaProject javaProject, String packageName) throws CoreException
    {
        // First try to find existing package
        IPackageFragment existing = findPackage(javaProject, packageName);
        if (existing != null)
        {
            return existing;
        }
        
        // Find the first source folder and create the package there
        for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots())
        {
            if (root.getKind() == IPackageFragmentRoot.K_SOURCE)
            {
                return root.createPackageFragment(packageName, true, new NullProgressMonitor());
            }
        }
        
        throw new RuntimeException("Error: No source folder found in project to create package '" + packageName + "'.");
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
     * Formats a code completion snippet using Eclipse's code formatter.
     * The completion is formatted in context by combining it with the code before the cursor,
     * formatting the combined code, and then extracting the formatted completion.
     * 
     * @param completion The raw completion text from the LLM
     * @param ctx The completion context containing code before/after cursor
     * @param editor The text editor (used to get project-specific formatter settings)
     * @return The formatted completion, or the original if formatting fails
     */
    public String formatCompletion(String completion, CompletionContext ctx, ITextEditor editor) {
        if (completion == null || completion.isEmpty()) {
            return completion;
        }
        
        // Only format Java files
        if (!"java".equalsIgnoreCase(ctx.fileExtension())) {
            return completion;
        }
        
        try {
            // Get the project for formatter settings
            Map<String, String> options = getFormatterOptionsForEditor(editor);
            
            if (options == null) {
                return completion;
            }
            
            // Create the formatter
            CodeFormatter formatter = ToolFactory.createCodeFormatter(options);
            
            // Combine code before cursor with the completion to format in context
            String codeBefore = ctx.codeBeforeCursor();
            String combinedCode = codeBefore + completion;
            
            // Format just the completion part (from offset = codeBefore.length())
            int completionOffset = codeBefore.length();
            int completionLength = completion.length();
            
            // Format as statements (K_STATEMENTS works better for code fragments)
            TextEdit textEdit = formatter.format(
                CodeFormatter.K_STATEMENTS | CodeFormatter.F_INCLUDE_COMMENTS,
                combinedCode,
                completionOffset,
                completionLength,
                getIndentationLevel(codeBefore),
                null
            );
            
            if (textEdit == null) {
                // Try formatting as unknown kind
                textEdit = formatter.format(
                    CodeFormatter.K_UNKNOWN,
                    combinedCode,
                    completionOffset,
                    completionLength,
                    getIndentationLevel(codeBefore),
                    null
                );
            }
            
            if (textEdit == null) {
                // Formatting failed, return original
                return completion;
            }
            
            // Apply the formatting to get the result
            IDocument document = new Document(combinedCode);
            textEdit.apply(document);
            
            // Extract the formatted completion (everything after the original code before cursor)
            String formattedCombined = document.get();
            
            // The formatted code might have different length, so we need to extract the completion part
            // by removing the (possibly reformatted) prefix
            if (formattedCombined.length() > codeBefore.length()) {
                // Find where the completion starts - look for the completion in the formatted result
                String formattedCompletion = formattedCombined.substring(codeBefore.length());
                return formattedCompletion;
            }
            
            return completion;
            
        } catch (Exception e) {
            logger.warn("Failed to format completion: " + e.getMessage());
            return completion;
        }
    }
    
    /**
     * Gets the formatter options for the given editor's project.
     */
    private Map<String, String> getFormatterOptionsForEditor(ITextEditor editor) {
        try {
            // Try to get project from editor
            if (editor.getEditorInput() instanceof IFileEditorInput) {
                IFile file = ((IFileEditorInput) editor.getEditorInput()).getFile();
                if (file != null && file.getProject() != null) {
                    IJavaProject javaProject = JavaCore.create(file.getProject());
                    if (javaProject != null && javaProject.exists()) {
                        return javaProject.getOptions(true);
                    }
                }
            }
            
            // Fall back to workspace defaults
            return JavaCore.getOptions();
        } catch (Exception e) {
            return JavaCore.getOptions();
        }
    }
    
    /**
     * Calculates the indentation level based on the code before cursor.
     */
    private int getIndentationLevel(String codeBefore) {
        if (codeBefore == null || codeBefore.isEmpty()) {
            return 0;
        }
        
        // Find the last line
        int lastNewline = codeBefore.lastIndexOf('\n');
        String lastLine = (lastNewline >= 0) ? codeBefore.substring(lastNewline + 1) : codeBefore;
        
        // Count leading tabs/spaces
        int indent = 0;
        for (char c : lastLine.toCharArray()) {
            if (c == '\t') {
                indent++;
            } else if (c == ' ') {
                // Assuming 4 spaces = 1 indent level (common default)
                // This will be adjusted by the formatter anyway
            } else {
                break;
            }
        }
        
        return indent;
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
