package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import org.apache.tika.Tika;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaModelMarker;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.internal.corext.callhierarchy.CallHierarchy;
import org.eclipse.jdt.internal.corext.callhierarchy.MethodWrapper;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.MyersDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleConstants;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.IConsoleView;
import org.eclipse.ui.console.IOConsole;
import org.eclipse.ui.console.IOConsoleOutputStream;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.TextConsole;
import org.eclipse.ui.editors.text.TextFileDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import com.github.gradusnikov.eclipse.assistai.mcp.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.ToolParam;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;

import jakarta.inject.Inject;


@Creatable
@McpServer(name = "eclipse-ide")
public class EclipseIntegrationsMcpServer
{
    @Inject
    private ILog logger;
    
    @Tool(name="getJavaDoc", description="Get the JavaDoc for the given compilation unit.  For example,a class B defined as a member type of a class A in package x.y should have athe fully qualified name \"x.y.A.B\".Note that in order to be found, a type name (or its top level enclosingtype name) must match its corresponding compilation unit name.", type="object")
    public String getJavaDoc(
            @ToolParam(name="fullyQualifiedName", description="A fully qualified name of the compilation unit", required=true) String fullyQualifiedClassName)
    {
        return getClassAttachedJavadoc( fullyQualifiedClassName );
    }
    @Tool(name="getSource", description="Get the source for the given class.", type="object")
    public String getSource(
            @ToolParam(name="fullyQualifiedClassName", description="A fully qualified class name of the Java class", required=true) String fullyQualifiedClassName)
    {
        return getClassAttachedSource( fullyQualifiedClassName );
    }
    
    

    @Tool(name="generateCodeDiff", description="Generate a diff/patch between proposed code and an existing file in the project. Returns a diff code block that should be presented to the User, and change summary.", type="object")
    public String generateCodeDiff(
            @ToolParam(name="projectName", description="The name of the project containing the file", required=true) String projectName,
            @ToolParam(name="filePath", description="The path to the file relative to the project root", required=true) String filePath,
            @ToolParam(name="proposedCode", description="The new/updated code being proposed", required=true) String proposedCode,
            @ToolParam(name="contextLines", description="Number of context lines to include in the diff (default: 3)", required=false) Integer contextLines) {
        
        if (contextLines == null || contextLines < 0) {
            contextLines = 3; // Default context lines
        }
        
        try {
            // Get the project
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.exists()) {
                return "Error: Project '" + projectName + "' not found.";
            }
            
            if (!project.isOpen()) {
                return "Error: Project '" + projectName + "' is closed.";
            }
            
            // Get the file
            IResource resource = project.findMember(filePath);
            if (resource == null || !resource.exists()) {
                return "Error: File '" + filePath + "' not found in project '" + projectName + "'.";
            }
            
            // Check if the resource is a file
            if (!(resource instanceof IFile)) {
                return "Error: Resource '" + filePath + "' is not a file.";
            }
            
            IFile file = (IFile) resource;
            
            // Read the original file content
            String originalContent;
            try (InputStream is = file.getContents()) {
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int length;
                while ((length = is.read(buffer)) != -1) {
                    result.write(buffer, 0, length);
                }
                originalContent = result.toString(file.getCharset());
            }
            
            // Create temporary files for diff
            Path originalFile = Files.createTempFile("original-", ".tmp");
            Path proposedFile = Files.createTempFile("proposed-", ".tmp");
            
            try {
                // Write contents to temp files
                Files.writeString(originalFile, originalContent);
                Files.writeString(proposedFile, proposedCode);
                
                // Generate diff using JGit's DiffFormatter
                ByteArrayOutputStream diffOutput = new ByteArrayOutputStream();
                try (DiffFormatter formatter = new DiffFormatter(diffOutput)) {
                    formatter.setContext(contextLines);
                    
                    // Create a simple manual diff
                    RawText rawOriginal = new RawText(originalFile.toFile());
                    RawText rawProposed = new RawText(proposedFile.toFile());
                    
                    // Write a manual diff header
                    diffOutput.write(("--- a/" + filePath + "\n").getBytes());
                    diffOutput.write(("+++ b/" + filePath + "\n").getBytes());
                    
                    // Create and format the edit list
                    EditList edits = new EditList();
                    RawTextComparator comparator = RawTextComparator.DEFAULT;
                    edits.addAll(MyersDiff.INSTANCE.diff(comparator, rawOriginal, rawProposed));
                    
                    // Write the unified diff format
                    for (org.eclipse.jgit.diff.Edit edit : edits) {
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
                } catch (IOException e) {
                    logger.error( e.getMessage(), e );
                }
                
                String diffResult = diffOutput.toString();
                
                // If there are no changes, inform the user
                if (diffResult.trim().isEmpty() || !diffResult.contains("@@")) {
                    return "No changes detected. The proposed code is identical to the existing file.";
                }
                
                StringBuilder response = new StringBuilder();
                response.append("# Diff for ").append(filePath).append("\n\n");
                response.append("```diff\n");
                response.append(diffResult);
                response.append("```\n\n");
                
                // Add summary of changes
                int addedLines = countMatches(diffResult, "\n+") - countMatches(diffResult, "\n+++");
                int removedLines = countMatches(diffResult, "\n-") - countMatches(diffResult, "\n---");
                
                response.append("## Summary of Changes\n");
                response.append("- Added lines: ").append(addedLines).append("\n");
                response.append("- Removed lines: ").append(removedLines).append("\n");
                response.append("- Net change: ").append(addedLines - removedLines).append(" line(s)\n\n");
                
                return response.toString();
                
            } finally {
                // Clean up temporary files
                Files.deleteIfExists(originalFile);
                Files.deleteIfExists(proposedFile);
            }
            
        } catch (IOException | CoreException e) {
            logger.error(e.getMessage(), e);
            return "Error generating diff: " + e.getMessage();
        }
    }
    
    
    /**
     * Count occurrences of a substring in a string
     */
    private int countMatches(String str, String sub) {
        if (str == null || str.isEmpty() || sub == null || sub.isEmpty()) {
            return 0;
        }
        
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
    
    
    @Tool(name="readProjectResource", description="Read the content of a text resource from a specified project.", type="object")
    public String readProjectResource(
            @ToolParam(name="projectName", description="The name of the project containing the resource", required=true) String projectName,
            @ToolParam(name="resourcePath", description="The path to the resource relative to the project root", required=true) String resourcePath) {
        
        try {
            // Get the project
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.exists()) {
                return "Error: Project '" + projectName + "' not found.";
            }
            
            if (!project.isOpen()) {
                return "Error: Project '" + projectName + "' is closed.";
            }
            
            // Get the resource
            IResource resource = project.findMember(resourcePath);
            if (resource == null || !resource.exists()) {
                return "Error: Resource '" + resourcePath + "' not found in project '" + projectName + "'.";
            }
            
            // Check if the resource is a file
            if (!(resource instanceof IFile)) {
                return "Error: Resource '" + resourcePath + "' is not a file.";
            }
            
            IFile file = (IFile) resource;
            
            // Check file size to avoid loading extremely large files
            long fileSizeInKB = file.getLocation().toFile().length() / 1024;
            if (fileSizeInKB > 1024) { // Limit to 1MB
                return "Error: File '" + resourcePath + "' is too large (" + fileSizeInKB + " KB). Maximum size is 1024 KB.";
            }
            
            // Use Apache Tika to detect content type
            Tika tika = new Tika();
            String mimeType = tika.detect(file.getLocation().toFile());
            
            // Check if this is a text file
            if (!mimeType.startsWith("text/") && 
                !mimeType.equals("application/json") && 
                !mimeType.equals("application/xml") &&
                !mimeType.equals("application/javascript") &&
                !mimeType.contains("+xml") &&
                !mimeType.contains("+json")) {
                
                return "Error: Cannot read binary file '" + resourcePath + "' with MIME type '" + mimeType + 
                       "'. Only text files are supported.";
            }
            
            // Read the file content
            try (InputStream is = file.getContents()) {
                // Use Tika to extract text content safely
                String content = tika.parseToString(is);
                
                // If the content is empty or very short, try reading it directly with the file's encoding
                if (content == null || content.trim().length() < 10) {
                    try (InputStream directIs = file.getContents()) {
                        ByteArrayOutputStream result = new ByteArrayOutputStream();
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = directIs.read(buffer)) != -1) {
                            result.write(buffer, 0, length);
                        }
                        
                        String charset = file.getCharset();
                        content = result.toString(charset);
                    }
                }
                
                // Prepare the response
                StringBuilder response = new StringBuilder();
                response.append("# Content of ").append(resourcePath).append("\n\n");
                response.append("MIME Type: ").append(mimeType).append("\n\n");
                response.append("```");
                
                // Add language hint for syntax highlighting based on MIME type
                if (mimeType.equals("text/x-java-source") || mimeType.contains("java")) {
                    response.append("java");
                } else if (mimeType.equals("text/x-python") || mimeType.contains("python")) {
                    response.append("python");
                } else if (mimeType.equals("application/javascript") || mimeType.contains("javascript")) {
                    response.append("javascript");
                } else if (mimeType.equals("text/html") || mimeType.contains("html")) {
                    response.append("html");
                } else if (mimeType.equals("application/xml") || mimeType.contains("xml")) {
                    response.append("xml");
                } else if (mimeType.equals("application/json") || mimeType.contains("json")) {
                    response.append("json");
                } else if (mimeType.equals("text/markdown") || resourcePath.endsWith(".md")) {
                    response.append("markdown");
                } else if (mimeType.equals("text/x-c") || resourcePath.endsWith(".c") || resourcePath.endsWith(".cpp") || 
                           resourcePath.endsWith(".h") || resourcePath.endsWith(".hpp")) {
                    response.append("cpp");
                } else if (mimeType.equals("application/x-sh") || resourcePath.endsWith(".sh")) {
                    response.append("bash");
                }
                // No specific highlighting for other text types
                
                response.append("\n").append(content).append("\n```\n");
                
                return response.toString();
            }
        } catch ( Exception e) {
            logger.error(e.getMessage(), e);
            return "Error reading resource: " + e.getMessage();
        }
    }

    @Tool(name="listProjects", description="List all available projects in the workspace with their detected natures (Java, C/C++, Python, etc.).", type="object")
    public String listProjects() {
        StringBuilder result = new StringBuilder();
        result.append("# Available Projects in Workspace\n\n");
        
        try {
            // Get all projects in the workspace
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            
            if (projects.length == 0) {
                return "No projects found in the workspace.";
            }
            
            // Define common nature IDs
            final String JAVA_NATURE = JavaCore.NATURE_ID; // "org.eclipse.jdt.core.javanature"
            final String CPP_NATURE = "org.eclipse.cdt.core.cnature";
            final String CPP_CC_NATURE = "org.eclipse.cdt.core.ccnature";
            final String PYTHON_NATURE = "org.python.pydev.pythonNature";
            final String JS_NATURE = "org.eclipse.wst.jsdt.core.jsNature";
            final String PHP_NATURE = "org.eclipse.php.core.PHPNature";
            final String WEB_NATURE = "org.eclipse.wst.common.project.facet.core.nature";
            final String MAVEN_NATURE = "org.eclipse.m2e.core.maven2Nature";
            final String GRADLE_NATURE = "org.eclipse.buildship.core.gradleprojectnature";
            
            // List all projects with their status and natures
            for (IProject project : projects) {
                result.append("- **").append(project.getName()).append("**");
                
                // Add project status (open/closed)
                result.append(" (").append(project.isOpen() ? "Open" : "Closed").append(")");
                
                // Only attempt to determine natures if the project is open
                if (project.isOpen()) {
                    try {
                        List<String> detectedNatures = new ArrayList<>();
                        
                        // Check for Java nature
                        if (project.hasNature(JAVA_NATURE)) {
                            IJavaProject javaProject = JavaCore.create(project);
                            String javaVersion = javaProject.getOption(JavaCore.COMPILER_COMPLIANCE, true);
                            detectedNatures.add("Java " + javaVersion);
                        }
                        
                        // Check for C/C++ nature
                        if (project.hasNature(CPP_NATURE)) {
                            detectedNatures.add("C");
                        }
                        if (project.hasNature(CPP_CC_NATURE)) {
                            detectedNatures.add("C++");
                        }
                        
                        // Check for Python nature
                        if (project.hasNature(PYTHON_NATURE)) {
                            detectedNatures.add("Python");
                        }
                        
                        // Check for JavaScript nature
                        if (project.hasNature(JS_NATURE)) {
                            detectedNatures.add("JavaScript");
                        }
                        
                        // Check for PHP nature
                        if (project.hasNature(PHP_NATURE)) {
                            detectedNatures.add("PHP");
                        }
                        
                        // Check for Web nature
                        if (project.hasNature(WEB_NATURE)) {
                            detectedNatures.add("Web");
                        }
                        
                        // Check for build system natures
                        if (project.hasNature(MAVEN_NATURE)) {
                            detectedNatures.add("Maven");
                        }
                        if (project.hasNature(GRADLE_NATURE)) {
                            detectedNatures.add("Gradle");
                        }
                        
                        // If we detected natures, add them to the output
                        if (!detectedNatures.isEmpty()) {
                            result.append(" - Project Type: ").append(String.join(", ", detectedNatures));
                        } else {
                            // Get all natures for projects we couldn't categorize
                            String[] natures = project.getDescription().getNatureIds();
                            if (natures.length > 0) {
                                result.append(" - Other Nature IDs: ").append(String.join(", ", natures));
                            } else {
                                result.append(" - Generic Project (no specific natures)");
                            }
                        }
                    } catch (CoreException e) {
                        result.append(" - Error determining project nature");
                        logger.error(e.getMessage(), e);
                    }
                }
                
                result.append("\n");
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return "Error retrieving projects: " + e.getMessage();
        }
        
        return result.toString();
    }



    @Tool(name="getCurrentlyOpenedFile", description="Gets information about the currently active file in the Eclipse editor.", type="object")
    public String getCurrentlyOpenedFile() {
        final StringBuilder result = new StringBuilder();
        
        Display.getDefault().syncExec(() -> {
            try {
                // Get the workbench and active page
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if (window == null) {
                    result.append("No active workbench window found.");
                    return;
                }
                
                IWorkbenchPage page = window.getActivePage();
                if (page == null) {
                    result.append("No active page found in the workbench.");
                    return;
                }
                
                // Get the active editor
                IEditorPart editor = page.getActiveEditor();
                if (editor == null) {
                    result.append("No active editor found. Please open a file.");
                    return;
                }
                
                // Get the editor input
                IEditorInput editorInput = editor.getEditorInput();
                if (editorInput == null) {
                    result.append("No editor input available for the active editor.");
                    return;
                }
                
                // Get file information
                result.append("# Currently Opened File\n\n");
                
                // Get the file name
                String fileName = editorInput.getName();
                result.append("File Name: ").append(fileName).append("\n");
                
                // Get the file path
                IFile file = null;
                if (editorInput instanceof IFileEditorInput) {
                    file = ((IFileEditorInput) editorInput).getFile();
                    result.append("File Path: ").append(file.getFullPath().toString()).append("\n");
                    
                    // Get project information
                    IProject project = file.getProject();
                    result.append("Project: ").append(project.getName()).append("\n");
                    
                    // Detect file type/language
                    String extension = file.getFileExtension();
                    if (extension != null) {
                        result.append("File Type: ").append(extension).append("\n");
                    }
                    
                    // Get file content
                    result.append("\n## File Content\n\n");
                    result.append("```");
                    
                    // Add language hint for syntax highlighting based on extension
                    if ("java".equalsIgnoreCase(extension)) {
                        result.append("java");
                    } else if ("py".equalsIgnoreCase(extension)) {
                        result.append("python");
                    } else if ("js".equalsIgnoreCase(extension)) {
                        result.append("javascript");
                    } else if ("html".equalsIgnoreCase(extension)) {
                        result.append("html");
                    } else if ("xml".equalsIgnoreCase(extension)) {
                        result.append("xml");
                    } else if ("json".equalsIgnoreCase(extension)) {
                        result.append("json");
                    } else if ("md".equalsIgnoreCase(extension)) {
                        result.append("markdown");
                    } else if ("c".equalsIgnoreCase(extension) || 
                               "cpp".equalsIgnoreCase(extension) || 
                               "h".equalsIgnoreCase(extension) || 
                               "hpp".equalsIgnoreCase(extension)) {
                        result.append("cpp");
                    } else if ("sh".equalsIgnoreCase(extension)) {
                        result.append("bash");
                    }
                    
                    result.append("\n");
                    
                    // Read file content
                    try (InputStream is = file.getContents()) {
                        ByteArrayOutputStream result2 = new ByteArrayOutputStream();
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = is.read(buffer)) != -1) {
                            result2.write(buffer, 0, length);
                        }
                        result.append(result2.toString(file.getCharset()));
                    }
                    
                    result.append("\n```");
                } else {
                    result.append("File information not available. The editor input is not a file.\n");
                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                result.append("Error retrieving current file information: ").append(e.getMessage());
            }
        });
        
        return result.toString();
    }
    

    @Tool(name="getEditorSelection", description="Gets the currently selected text or lines in the active editor.", type="object")
    public String getEditorSelection() {
        final StringBuilder result = new StringBuilder();
        
        Display.getDefault().syncExec(() -> {
            try {
                // Get the workbench and active page
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if (window == null) {
                    result.append("No active workbench window found.");
                    return;
                }
                
                IWorkbenchPage page = window.getActivePage();
                if (page == null) {
                    result.append("No active page found in the workbench.");
                    return;
                }
                
                // Get the active editor
                IEditorPart editor = page.getActiveEditor();
                if (editor == null) {
                    result.append("No active editor found. Please open a file.");
                    return;
                }
                
                // Get the selection from the editor
                if (editor instanceof ITextEditor) {
                    ITextEditor textEditor = (ITextEditor) editor;
                    ISelection selection = textEditor.getSelectionProvider().getSelection();
                    
                    if (selection.isEmpty()) {
                        result.append("No text is currently selected in the editor.");
                        return;
                    }
                    
                    if (selection instanceof ITextSelection) {
                        ITextSelection textSelection = (ITextSelection) selection;
                        
                        // Get the selected text
                        String selectedText = textSelection.getText();
                        if (selectedText == null || selectedText.isEmpty()) {
                            result.append("Selection exists but contains no text.");
                            return;
                        }
                        
                        result.append("# Selected Text in Editor\n\n");
                        
                        // Get file information
                        IEditorInput editorInput = editor.getEditorInput();
                        String fileName = editorInput.getName();
                        result.append("File: ").append(fileName).append("\n");
                        
                        // Selection details
                        int startLine = textSelection.getStartLine() + 1; // 1-based line numbers for display
                        int endLine = textSelection.getEndLine() + 1;
                        int offset = textSelection.getOffset();
                        int length = textSelection.getLength();
                        
                        result.append("Selection: Lines ").append(startLine).append(" to ").append(endLine)
                              .append(" (").append(length).append(" characters)\n\n");
                        
                        // Try to determine the language for syntax highlighting
                        String language = "";
                        if (editorInput instanceof IFileEditorInput) {
                            IFile file = ((IFileEditorInput) editorInput).getFile();
                            String extension = file.getFileExtension();
                            if (extension != null) {
                                if ("java".equalsIgnoreCase(extension)) {
                                    language = "java";
                                } else if ("py".equalsIgnoreCase(extension)) {
                                    language = "python";
                                } else if ("js".equalsIgnoreCase(extension)) {
                                    language = "javascript";
                                } else if ("html".equalsIgnoreCase(extension)) {
                                    language = "html";
                                } else if ("xml".equalsIgnoreCase(extension)) {
                                    language = "xml";
                                } else if ("json".equalsIgnoreCase(extension)) {
                                    language = "json";
                                } else if ("md".equalsIgnoreCase(extension)) {
                                    language = "markdown";
                                } else if ("c".equalsIgnoreCase(extension) || 
                                          "cpp".equalsIgnoreCase(extension) || 
                                          "h".equalsIgnoreCase(extension) || 
                                          "hpp".equalsIgnoreCase(extension)) {
                                    language = "cpp";
                                } else if ("sh".equalsIgnoreCase(extension)) {
                                    language = "bash";
                                }
                            }
                        }
                        
                        // Add the selected text with syntax highlighting if possible
                        result.append("```").append(language).append("\n");
                        result.append(selectedText);
                        if (!selectedText.endsWith("\n")) {
                            result.append("\n");
                        }
                        result.append("```\n");
                    } else {
                        result.append("The current selection is not a text selection.");
                    }
                } else {
                    result.append("The active editor is not a text editor.");
                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                result.append("Error retrieving editor selection: ").append(e.getMessage());
            }
        });
        
        return result.toString();
    }


    @Tool(name="getCompilationErrors", description="Retrieves compilation errors and problems from the current workspace or a specific project.", type="object")
    public String getCompilationErrors(
            @ToolParam(name="projectName", description="The name of the specific project to check (optional, leave empty for all projects)", required=false) String projectName,
            @ToolParam(name="severity", description="Filter by severity level: 'ERROR', 'WARNING', or 'ALL' (default)", required=false) String severity,
            @ToolParam(name="maxResults", description="Maximum number of problems to return (default: 50)", required=false) Integer maxResults) {
        
        try {
            // Set default values
            if (severity == null || severity.trim().isEmpty()) {
                severity = "ALL";
            } else {
                severity = severity.toUpperCase();
            }
            
            if (maxResults == null || maxResults < 1) {
                maxResults = 50;
            }
            
            // Define severity filter
            int severityFilter;
            if ("ERROR".equals(severity)) {
                severityFilter = IMarker.SEVERITY_ERROR;
            } else if ("WARNING".equals(severity)) {
                severityFilter = IMarker.SEVERITY_WARNING;
            } else {
                severityFilter = -1; // ALL
            }
            
            StringBuilder result = new StringBuilder();
            result.append("# Compilation Problems\n\n");
            
            // Get markers from workspace or specific project
            IMarker[] markers;
            if (projectName != null && !projectName.trim().isEmpty()) {
                IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
                if (project == null || !project.exists()) {
                    return "Project '" + projectName + "' not found.";
                }
                
                if (!project.isOpen()) {
                    return "Project '" + projectName + "' is closed.";
                }
                
                result.append("Project: ").append(projectName).append("\n\n");
                markers = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
            } else {
                result.append("Scope: All Projects\n\n");
                markers = ResourcesPlugin.getWorkspace().getRoot().findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
            }
            
            // Filter and sort markers
            List<IMarker> filteredMarkers = new ArrayList<>();
            for (IMarker marker : markers) {
                Integer severityValue = (Integer) marker.getAttribute(IMarker.SEVERITY);
                if (severityFilter == -1 || (severityValue != null && severityValue.intValue() == severityFilter)) {
                    filteredMarkers.add(marker);
                }
            }
            
            // Sort by severity (errors first, then warnings)
            filteredMarkers.sort((m1, m2) -> {
                try {
                    Integer sev1 = (Integer) m1.getAttribute(IMarker.SEVERITY);
                    Integer sev2 = (Integer) m2.getAttribute(IMarker.SEVERITY);
                    return sev2.compareTo(sev1); // Higher severity first
                } catch (CoreException e) {
                    return 0;
                }
            });
            
            // Limit the number of results
            if (filteredMarkers.size() > maxResults) {
                result.append("Showing ").append(maxResults).append(" of ").append(filteredMarkers.size())
                      .append(" problems found.\n\n");
                filteredMarkers = filteredMarkers.subList(0, maxResults);
            } else {
                result.append("Found ").append(filteredMarkers.size()).append(" problems.\n\n");
            }
            
            if (filteredMarkers.isEmpty()) {
                result.append("No compilation problems found with the specified criteria.");
                return result.toString();
            }
            
            // Group by resource
            Map<String, List<IMarker>> markersByResource = new HashMap<>();
            for (IMarker marker : filteredMarkers) {
                IResource resource = marker.getResource();
                String resourcePath = resource.getFullPath().toString();
                if (!markersByResource.containsKey(resourcePath)) {
                    markersByResource.put(resourcePath, new ArrayList<>());
                }
                markersByResource.get(resourcePath).add(marker);
            }
            
            // Output problems grouped by resource
            for (Map.Entry<String, List<IMarker>> entry : markersByResource.entrySet()) {
                String resourcePath = entry.getKey();
                List<IMarker> resourceMarkers = entry.getValue();
                
                result.append("## ").append(resourcePath).append("\n\n");
                
                for (IMarker marker : resourceMarkers) {
                    Integer severityValue = (Integer) marker.getAttribute(IMarker.SEVERITY);
                    String severityText;
                    
                    if (severityValue != null) {
                        switch (severityValue.intValue()) {
                            case IMarker.SEVERITY_ERROR:
                                severityText = "ERROR";
                                break;
                            case IMarker.SEVERITY_WARNING:
                                severityText = "WARNING";
                                break;
                            case IMarker.SEVERITY_INFO:
                                severityText = "INFO";
                                break;
                            default:
                                severityText = "UNKNOWN";
                        }
                    } else {
                        severityText = "UNKNOWN";
                    }
                    
                    // Get line number
                    Integer lineNumber = (Integer) marker.getAttribute(IMarker.LINE_NUMBER);
                    String lineStr = lineNumber != null ? "Line " + lineNumber : "Unknown location";
                    
                    // Get message
                    String message = (String) marker.getAttribute(IMarker.MESSAGE);
                    if (message == null) {
                        message = "No message provided";
                    }
                    
                    result.append("- **").append(severityText).append("** at ").append(lineStr).append(": ")
                          .append(message).append("\n");
                    
                    // If this is a Java problem, try to get more context
                    if (marker.getType().equals(IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER)) {
                        String sourceId = (String) marker.getAttribute(IJavaModelMarker.ID);
                        if (sourceId != null) {
                            result.append("  - Problem ID: ").append(sourceId).append("\n");
                        }
                        
                        // Try to get source code snippet if line number is available
                        if (lineNumber != null && marker.getResource() instanceof IFile) {
                            try {
                                IFile file = (IFile) marker.getResource();
                                String fileContent = readFileContent(file);
                                String[] lines = fileContent.split("\n");
                                
                                if (lineNumber > 0 && lineNumber <= lines.length) {
                                    int startLine = Math.max(1, lineNumber - 1);
                                    int endLine = Math.min(lines.length, lineNumber + 1);
                                    
                                    result.append("  - Context:\n```java\n");
                                    for (int i = startLine - 1; i < endLine; i++) {
                                        if (i == lineNumber - 1) {
                                            result.append("> "); // Highlight the error line
                                        } else {
                                            result.append("  ");
                                        }
                                        result.append(lines[i]).append("\n");
                                    }
                                    result.append("```\n");
                                }
                            } catch (Exception e) {
                                // Skip context if we can't read the file
                            }
                        }
                    }
                }
                
                result.append("\n");
            }
            
            return result.toString();
            
        } catch (CoreException e) {
            logger.error(e.getMessage(), e);
            return "Error retrieving compilation problems: " + e.getMessage();
        }
    }
    
    /**
     * Helper method to read file content
     */
    private String readFileContent(IFile file) throws CoreException, IOException {
        try (InputStream is = file.getContents()) {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) != -1) {
                result.write(buffer, 0, length);
            }
            return result.toString(file.getCharset());
        }
    }



    @Tool(name="getConsoleOutput", description="Retrieves the recent output from Eclipse console(s).", type="object")
    public String getConsoleOutput(
            @ToolParam(name="consoleName", description="Name of the specific console to retrieve (optional, leave empty for all or most recent console)", required=false) String consoleName,
            @ToolParam(name="maxLines", description="Maximum number of lines to retrieve (default: 100)", required=false) Integer maxLines,
            @ToolParam(name="includeAllConsoles", description="Whether to include output from all available consoles (default: false)", required=false) Boolean includeAllConsoles) {
        
        // Set default values
        if (maxLines == null || maxLines < 1) {
            maxLines = 100;
        }
        
        if (includeAllConsoles == null) {
            includeAllConsoles = false;
        }
        
        final StringBuilder result = new StringBuilder();
        result.append("# Console Output\n\n");
        
        final int finalMaxLines = maxLines;
        final boolean finalIncludeAllConsoles = includeAllConsoles;
        
        Display.getDefault().syncExec(() -> {
            try {
                // Get all consoles from the console manager
                IConsoleManager consoleManager = ConsolePlugin.getDefault().getConsoleManager();
                IConsole[] consoles = consoleManager.getConsoles();
                
                if (consoles.length == 0) {
                    result.append("No consoles found in the Eclipse workspace.");
                    return;
                }
                
                // Filter consoles based on parameters
                List<IConsole> targetConsoles = new ArrayList<>();
                
                if (consoleName != null && !consoleName.trim().isEmpty()) {
                    // Find console by name
                    for (IConsole console : consoles) {
                        if (console.getName().contains(consoleName)) {
                            targetConsoles.add(console);
                        }
                    }
                    
                    if (targetConsoles.isEmpty()) {
                        result.append("No console found with name containing '").append(consoleName).append("'.");
                        return;
                    }
                } else if (finalIncludeAllConsoles) {
                    // Include all consoles
                    targetConsoles.addAll(Arrays.asList(consoles));
                } else {
                    // Get the most recently used console
                    IConsole mostRecentConsole = null;
                    
                    // First check if there's an active console page
                    IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                    if (window != null) {
                        IWorkbenchPage page = window.getActivePage();
                        if (page != null) {
                            IViewPart consoleView = page.findView(IConsoleConstants.ID_CONSOLE_VIEW);
                            if (consoleView instanceof IConsoleView) {
                                IConsole activeConsole = ((IConsoleView) consoleView).getConsole();
                                if (activeConsole != null) {
                                    mostRecentConsole = activeConsole;
                                }
                            }
                        }
                    }
                    
                    // If we couldn't find an active console, just use the first one
                    if (mostRecentConsole == null && consoles.length > 0) {
                        mostRecentConsole = consoles[0];
                    }
                    
                    if (mostRecentConsole != null) {
                        targetConsoles.add(mostRecentConsole);
                    }
                }
                
                if (targetConsoles.isEmpty()) {
                    result.append("No applicable consoles found to retrieve output from.");
                    return;
                }
                
                // Process each target console
                boolean foundContent = false;
                for (IConsole console : targetConsoles) {
                    String consoleContent = "";
                    
                    // Handle different console types
                    if (console instanceof TextConsole) {
                        TextConsole textConsole = (TextConsole) console;
                        IDocument document = textConsole.getDocument();
                        
                        if (document != null) {
                            String fullContent = document.get();
                            
                            // Get the last N lines
                            String[] lines = fullContent.split("\n");
                            int startLine = Math.max(0, lines.length - finalMaxLines);
                            
                            StringBuilder consoleText = new StringBuilder();
                            for (int i = startLine; i < lines.length; i++) {
                                consoleText.append(lines[i]).append("\n");
                            }
                            
                            consoleContent = consoleText.toString();
                        }
                    } else if (console instanceof IOConsole) {
                        IOConsole ioConsole = (IOConsole) console;
                        
                        // For IOConsole, we can only access what's currently in the buffer
                        IDocument document = ioConsole.getDocument();
                        if (document != null) {
                            String fullContent = document.get();
                            
                            // Get the last N lines
                            String[] lines = fullContent.split("\n");
                            int startLine = Math.max(0, lines.length - finalMaxLines);
                            
                            StringBuilder consoleText = new StringBuilder();
                            for (int i = startLine; i < lines.length; i++) {
                                consoleText.append(lines[i]).append("\n");
                            }
                            
                            consoleContent = consoleText.toString();
                        }
                    } else if (console instanceof MessageConsole) {
                        MessageConsole messageConsole = (MessageConsole) console;
                        
                        // For MessageConsole, access the document
                        IDocument document = messageConsole.getDocument();
                        if (document != null) {
                            String fullContent = document.get();
                            
                            // Get the last N lines
                            String[] lines = fullContent.split("\n");
                            int startLine = Math.max(0, lines.length - finalMaxLines);
                            
                            StringBuilder consoleText = new StringBuilder();
                            for (int i = startLine; i < lines.length; i++) {
                                consoleText.append(lines[i]).append("\n");
                            }
                            
                            consoleContent = consoleText.toString();
                        }
                    }
                    
                    // Add the console content to the result if not empty
                    if (consoleContent != null && !consoleContent.trim().isEmpty()) {
                        foundContent = true;
                        result.append("## Console: ").append(console.getName()).append("\n\n");
                        result.append("```\n");
                        result.append(consoleContent);
                        if (!consoleContent.endsWith("\n")) {
                            result.append("\n");
                        }
                        result.append("```\n\n");
                    }
                }
                
                if (!foundContent) {
                    result.append("No content found in the ").append(targetConsoles.size() == 1 ? "selected console." : "selected consoles.");
                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                result.append("Error retrieving console output: ").append(e.getMessage());
            }
        });
        
        return result.toString();
    }

    
    @Tool(name="getProjectLayout", description="Get the file and folder structure of a specified project in a hierarchical format suitable for LLM processing.", type="object")
    public String getProjectLayout(
            @ToolParam(name="projectName", description="The name of the project to analyze", required=true) String projectName)
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists()) {
            return "Project '" + projectName + "' not found.";
        }
        
        StringBuilder result = new StringBuilder();
        try {
            result.append("# Project Structure: ").append(projectName).append("\n\n");
            collectResourcesForLLM(project, 0, result); // Start with the project root
        }
        catch (CoreException e) {
            logger.error(e.getMessage(), e);
            return "Error retrieving project layout: " + e.getMessage();
        }
        
        return result.toString();
    }


    @Tool(name="getMethodCallHierarchy", description="Retrieves the call hierarchy (callers) for a specified method to understand how it's used in the codebase.", type="object")
    public String getMethodCallHierarchy(
            @ToolParam(name="fullyQualifiedClassName", description="The fully qualified name of the class containing the method", required=true) String fullyQualifiedClassName,
            @ToolParam(name="methodName", description="The name of the method to analyze", required=true) String methodName,
            @ToolParam(name="methodSignature", description="The signature of the method (optional, required if method is overloaded)", required=false) String methodSignature,
            @ToolParam(name="maxDepth", description="Maximum depth of the call hierarchy to retrieve (default: 3)", required=false) Integer maxDepth) {
        
        if (maxDepth == null || maxDepth < 1) {
            maxDepth = 3; // Default to 3 levels of depth
        }
        
        StringBuilder result = new StringBuilder();
        result.append("# Call Hierarchy for Method: ").append(methodName).append("\n\n");
        
        try {
            // Find the method in available Java projects
            IMethod targetMethod = null;
            
            for (IJavaProject project : getAvailableJavaProjects()) {
                IType type = project.findType(fullyQualifiedClassName);
                if (type == null) {
                    continue;
                }
                
                // If method signature is provided, use it to find the exact method
                if (methodSignature != null && !methodSignature.isEmpty()) {
                    targetMethod = type.getMethod(methodName, methodSignature.split(","));
                    if (targetMethod != null && targetMethod.exists()) {
                        break;
                    }
                } else {
                    // Try to find the method without signature
                    IMethod[] methods = type.getMethods();
                    for (IMethod method : methods) {
                        if (method.getElementName().equals(methodName)) {
                            targetMethod = method;
                            break;
                        }
                    }
                    if (targetMethod != null) {
                        break;
                    }
                }
            }
            
            if (targetMethod == null || !targetMethod.exists()) {
                return "Method '" + methodName + "' not found in class '" + fullyQualifiedClassName + "'.";
            }
            
            // Get the call hierarchy
            CallHierarchy callHierarchy = CallHierarchy.getDefault();
            MethodWrapper[] callerRoots = callHierarchy.getCallerRoots(new IMethod[] {targetMethod});
            
            if (callerRoots == null || callerRoots.length == 0) {
                result.append("No callers found for this method.\n");
            } else {
                result.append("## Callers:\n\n");
                collectCallHierarchy(callerRoots, 0, maxDepth, result);
            }
            
            // Also get callees (methods called by this method)
            MethodWrapper[] calleeRoots = callHierarchy.getCalleeRoots(new IMethod[] {targetMethod});
            
            if (calleeRoots != null && calleeRoots.length > 0) {
                result.append("\n## Methods Called By ").append(methodName).append(":\n\n");
                collectCalleeHierarchy(calleeRoots, 0, 1, result); // Only go one level deep for callees
            }
            
            return result.toString();
            
        } catch (JavaModelException e) {
            logger.error(e.getMessage(), e);
            return "Error retrieving call hierarchy: " + e.getMessage();
        }
    }
    
    private void collectCallHierarchy(MethodWrapper[] callers, int level, int maxDepth, StringBuilder result) {
        if (level >= maxDepth) {
            return;
        }
        
        for (MethodWrapper caller : callers) {
            IJavaElement member = caller.getMember();
            if (member instanceof IMethod) {
                IMethod method = (IMethod) member;
                
                // Indent based on level
                for (int i = 0; i < level; i++) {
                    result.append("  ");
                }
                
                try {
                    // Add the method with its declaring type
                    result.append("- **").append(method.getElementName()).append("**");
                    result.append(" in `").append(method.getDeclaringType().getFullyQualifiedName()).append("`");
                    
                    // Add method parameters for clarity
                    String[] parameterTypes = method.getParameterTypes();
                    if (parameterTypes.length > 0) {
                        result.append(" (");
                        for (int i = 0; i < parameterTypes.length; i++) {
                            if (i > 0) {
                                result.append(", ");
                            }
                            result.append(Signature.toString(parameterTypes[i]));
                        }
                        result.append(")");
                    }
                    
                    // Add source location information
                    ICompilationUnit cu = method.getCompilationUnit();
                    if (cu != null) {
                        result.append(" - ").append(cu.getElementName());
                    }
                    
                    result.append("\n");
                    
                    // Recurse to next level
                    MethodWrapper[] nestedCallers = caller.getCalls(new NullProgressMonitor());
                    collectCallHierarchy(nestedCallers, level + 1, maxDepth, result);
                    
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                    result.append(" [Error retrieving method details]\n");
                }
            }
        }
    }
    
    private void collectCalleeHierarchy(MethodWrapper[] callees, int level, int maxDepth, StringBuilder result) {
        if (level >= maxDepth) {
            return;
        }
        
        for (MethodWrapper callee : callees) {
            IJavaElement member = callee.getMember();
            if (member instanceof IMethod) {
                IMethod method = (IMethod) member;
                
                // Indent based on level
                for (int i = 0; i < level; i++) {
                    result.append("  ");
                }
                
                try {
                    // Add the method with its declaring type
                    result.append("- **").append(method.getElementName()).append("**");
                    result.append(" in `").append(method.getDeclaringType().getFullyQualifiedName()).append("`");
                    
                    // Add method parameters for clarity
                    String[] parameterTypes = method.getParameterTypes();
                    if (parameterTypes.length > 0) {
                        result.append(" (");
                        for (int i = 0; i < parameterTypes.length; i++) {
                            if (i > 0) {
                                result.append(", ");
                            }
                            result.append(Signature.toString(parameterTypes[i]));
                        }
                        result.append(")");
                    }
                    
                    result.append("\n");
                    
                    // Recurse to next level if needed
                    if (level + 1 < maxDepth) {
                        MethodWrapper[] nestedCallees = callee.getCalls(new NullProgressMonitor());
                        collectCalleeHierarchy(nestedCallees, level + 1, maxDepth, result);
                    }
                    
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                    result.append(" [Error retrieving method details]\n");
                }
            }
        }
    }
    
    
    private void collectResourcesForLLM(IResource resource, int depth, StringBuilder result) throws CoreException {
        // Use proper indentation with markdown list formatting
        String indent = "";
        for (int i = 0; i < depth; i++) {
            indent += "  ";
        }
        
        // Append the current resource with markdown list item
        String prefix = depth > 0 ? indent + "- " : "- ";
        result.append(prefix).append(resource.getName());
        
        // Add type indicator for better context
        if (resource.getType() == IResource.FOLDER) {
            result.append(" (Directory)");
        } else if (resource.getType() == IResource.FILE) {
            result.append(" (File)");
        } else if (resource.getType() == IResource.PROJECT) {
            result.append(" (Project)");
        }
        
        result.append("\n");

        // If the resource is a container, list its children
        if (resource instanceof IContainer) {
            IContainer container = (IContainer) resource;
            IResource[] members = container.members();
            for (IResource member : members) {
                collectResourcesForLLM(member, depth + 1, result);
            }
        }
    }
    
    /**
     * Retrieves the attached JavaDoc documentation for a given class within the available Java projects.
     * It searches all projects for the JavaDoc and if found, it returns the JavaDoc content. If no JavaDoc
     * is found, it returns a message stating that JavaDoc is not available for the specified class.
     *
     * @param fullyQualifiedClassName The fully qualified name of the class to find the JavaDoc for.
     * @return The JavaDoc string if available; otherwise, a message indicating it is not available.
     */
    public String getClassAttachedJavadoc( String fullyQualifiedClassName )
    {
        return getAvailableJavaProjects().stream()
                                          .map( project -> getAttachedJavadoc( fullyQualifiedClassName, project ) )
                                          .filter( Objects::nonNull )
                                          .filter( Predicate.not( String::isBlank ) )
                                          .findAny()
                                          .orElse( "JavaDoc is not available for " + fullyQualifiedClassName );
    }
    /**
     * Retrieves the source code attached to the specified class within the available Java projects.
     * It searches all projects for the source code and if found, returns the source content. If no source code
     * is found or an exception occurs, it returns a message indicating that the source is not available for the specified class.
     *
     * @param fullyQualifiedClassName The fully qualified name of the class for which to find the source code.
     * @return The source code string if available; otherwise, a message indicating it is not available.
     */    
    public String getClassAttachedSource( String fullyQualifiedClassName )
    {
        return getAvailableJavaProjects().stream()
                                          .map( project -> getAttachedSource( fullyQualifiedClassName, project ) )
                                          .filter( Objects::nonNull )
                                          .filter( Predicate.not( String::isBlank ) )
                                          .findAny()
                                          .orElse( "Source is not available for " + fullyQualifiedClassName );
    }
    /**
     * Retrieves a list of all available Java projects in the current workspace.
     * It filters out non-Java projects and only includes projects that are open and have the Java nature.
     *
     * @return A list of {@link IJavaProject} representing the available Java projects.
     * @throws RuntimeException if an error occurs while accessing project information.
     */
    public List<IJavaProject> getAvailableJavaProjects()
    {
        List<IJavaProject> javaProjects = new ArrayList<>();

        try
        {
            // Get all projects in the workspace
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();

            // Filter out the Java projects
            for ( IProject project : projects )
            {
                if ( project.isOpen() && project.hasNature( JavaCore.NATURE_ID ) )
                {
                    IJavaProject javaProject = JavaCore.create( project );
                    javaProjects.add( javaProject );
                }
            }
        }
        catch ( CoreException e )
        {
            throw new RuntimeException( e );
        }

        return javaProjects;
    }
    /**
     * Gathers and returns JavaDoc information for a specified class within a given Java project. It retrieves the JavaDoc
     * by looking up the type corresponding to the fully qualified class name and extracting its documentation, as well as
     * the documentation of its children elements.
     *
     * @param fullyQualifiedClassName The fully qualified name of the class for which to retrieve JavaDoc.
     * @param javaProject The Java project within which to search for the class.
     * @return A string containing the JavaDoc for the class and its children, or an empty string if not found.
     */
    public String getAttachedJavadoc( String fullyQualifiedClassName, IJavaProject javaProject )
    {
        String javaDoc = "";
        try
        {
            IType type = javaProject.findType(fullyQualifiedClassName);
            if ( Objects.nonNull( type ) )
            {
                javaDoc += getMemberJavaDoc( (IMember) type );
                
                for ( IJavaElement child : type.getChildren() )
                {
                    javaDoc += getMemberJavaDoc( (IMember) child );
                }
            }
        }
        catch ( JavaModelException e )
        {
          logger.error( e.getMessage(), e );
        }
        
        var converter = FlexmarkHtmlConverter.builder().build();
        String markdown = converter.convert( javaDoc );
        return markdown;
    }
    
    

    /**
     * Retrieves the JavaDoc documentation for a given member of a Java project.
     * This method extracts the JavaDoc directly if it is attached to the member, or from the source buffer
     * if it is available. If no JavaDoc is found, this method returns an empty string.
     *
     * @param member The member for which to retrieve the JavaDoc documentation.
     * @return A string containing the JavaDoc documentation, or an empty string if none is found.
     * @throws JavaModelException if an error occurs while retrieving the JavaDoc.
     */
    private String getMemberJavaDoc(  IMember member ) throws JavaModelException
    {
        String javaDoc = "";
        String attachedJavaDoc = member.getAttachedJavadoc( null );
        if ( attachedJavaDoc != null )
        {
            javaDoc += attachedJavaDoc;
        }
        else
        {
            ISourceRange range = member.getJavadocRange();
            if ( range != null )
            {
                ICompilationUnit unit = member.getCompilationUnit();
                if ( unit != null )
                {
                    IBuffer buffer = unit.getBuffer();
                    javaDoc += buffer.getText( range.getOffset(), range.getLength() ) + "\n";
                }
            }
        }
        javaDoc += member.toString() + "\n";
        return javaDoc;
    }
       
    /**
     * Extracts the source code for a specified class from the given Java project's associated resources.
     * If the class is found, the source code is retrieved from the corresponding file. If not found,
     * or if any errors occur during retrieval, a message is returned indicating the source is unavailable.
     *
     * @param fullyQualifiedClassName The fully qualified name of the class whose source code is to be retrieved.
     * @param javaProject The Java project to which the class belongs.
     * @return The source code of the class, or a message indicating that the source is not available.
     */
    public String getAttachedSource( String fullyQualifiedClassName, IJavaProject javaProject )
    {
        try
        {
            // Find the type for the fully qualified class name
            IType type = javaProject.findType( fullyQualifiedClassName );
            if ( type == null )
            {
                return null;      
            }
            // Get the attached Javadoc
            IResource resource = type.getCorrespondingResource();
            if ( resource == null )
            {
                resource = type.getResource();
            }
            if ( resource == null )
            {
                resource = type.getUnderlyingResource();
            }
            // Check if the resource is a file
            if (resource instanceof IFile) 
            {
                IFile file = (IFile) resource;
                TextFileDocumentProvider provider = new TextFileDocumentProvider();
                provider.connect(file);
                Document document = (Document) provider.getDocument(file);
                String content = document.get();
                provider.disconnect(file);

                return content;
            }
        }
        catch ( Exception e )
        {
            logger.error( e.getMessage(), e );
            return null;
        }
        return null;
    }    
    
}
