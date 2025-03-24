package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.mcp.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.ToolParam;
import com.github.gradusnikov.eclipse.assistai.mcp.services.CodeAnalysisService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.CodeEditingService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.ConsoleService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.EditorService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.JavaDocService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.ProjectService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.ResourceService;

import jakarta.inject.Inject;


@Creatable
@McpServer(name = "eclipse-ide")
public class EclipseIntegrationsMcpServer
{
    @Inject
    private JavaDocService javaDocService;
    
    @Inject
    private ProjectService projectService;
    
    @Inject
    private CodeAnalysisService codeAnalysisService;
    
    @Inject
    private ResourceService resourceService; 
    
    @Inject
    private EditorService editorService;
    
    @Inject
    private ConsoleService consoleService;
    
    @Inject
    private CodeEditingService codeEditingService;
    
    
    @Tool(name="getJavaDoc", description="Get the JavaDoc for the given compilation unit.  For example,a class B defined as a member type of a class A in package x.y should have athe fully qualified name \"x.y.A.B\".Note that in order to be found, a type name (or its top level enclosingtype name) must match its corresponding compilation unit name.", type="object")
    public String getJavaDoc(
            @ToolParam(name="fullyQualifiedName", description="A fully qualified name of the compilation unit", required=true) String fullyQualifiedClassName)
    {
        return javaDocService.getJavaDoc(fullyQualifiedClassName);
    }
    @Tool(name="getSource", description="Get the source for the given class.", type="object")
    public String getSource(
            @ToolParam(name="fullyQualifiedClassName", description="A fully qualified class name of the Java class", required=true) String fullyQualifiedClassName)
    {
        return javaDocService.getSource(fullyQualifiedClassName);
    }

    @Tool(name="getProjectProperties", description="Retrieves the properties and configuration of a specified project.", type="object")
    public String getProjectProperties(
            @ToolParam(name="projectName", description="The name of the project to analyze", required=true) String projectName) 
    {
        return projectService.getProjectProperties( projectName );
    }
    
    @Tool(name="getProjectLayout", description="Get the file and folder structure of a specified project in a hierarchical format suitable for LLM processing.", type="object")
    public String getProjectLayout(
            @ToolParam(name="projectName", description="The name of the project to analyze", required=true) String projectName)
    {
        return projectService.getProjectLayout( projectName );
    }
    @Tool(name="getMethodCallHierarchy", description="Retrieves the call hierarchy (callers) for a specified method to understand how it's used in the codebase.", type="object")
    public String getMethodCallHierarchy(
            @ToolParam(name="fullyQualifiedClassName", description="The fully qualified name of the class containing the method", required=true) String fullyQualifiedClassName,
            @ToolParam(name="methodName", description="The name of the method to analyze", required=true) String methodName,
            @ToolParam(name="methodSignature", description="The signature of the method (optional, required if method is overloaded)", required=false) String methodSignature,
            @ToolParam(name="maxDepth", description="Maximum depth of the call hierarchy to retrieve (default: 3)", required=false) Integer maxDepth) 
    {
        return codeAnalysisService.getMethodCallHierarchy( fullyQualifiedClassName, methodName, methodSignature, maxDepth );
    }
    @Tool(name="getCompilationErrors", description="Retrieves compilation errors and problems from the current workspace or a specific project.", type="object")
    public String getCompilationErrors(
            @ToolParam(name="projectName", description="The name of the specific project to check (optional, leave empty for all projects)", required=false) String projectName,
            @ToolParam(name="severity", description="Filter by severity level: 'ERROR', 'WARNING', or 'ALL' (default)", required=false) String severity,
            @ToolParam(name="maxResults", description="Maximum number of problems to return (default: 50)", required=false) Integer maxResults) 
    {
        return codeAnalysisService.getCompilationErrors( projectName, severity, maxResults );
    }
    @Tool(name="generateCodeDiff", description="Generate a diff/patch between proposed code and an existing file in the project. Returns a diff code block that should be presented to the User, and change summary.", type="object")
    public String generateCodeDiff(
            @ToolParam(name="projectName", description="The name of the project containing the file", required=true) String projectName,
            @ToolParam(name="filePath", description="The path to the file relative to the project root", required=true) String filePath,
            @ToolParam(name="proposedCode", description="The new/updated code being proposed", required=true) String proposedCode,
            @ToolParam(name="contextLines", description="Number of context lines to include in the diff (default: 3)", required=false) Integer contextLines) 
    {
        return codeEditingService.generateCodeDiff( projectName, filePath, proposedCode, contextLines ); 
    }
    
    @Tool(name="readProjectResource", description="Read the content of a text resource from a specified project.", type="object")
    public String readProjectResource(
            @ToolParam(name="projectName", description="The name of the project containing the resource", required=true) String projectName,
            @ToolParam(name="resourcePath", description="The path to the resource relative to the project root", required=true) String resourcePath) 
    {
        return resourceService.readProjectResource( projectName, resourcePath );
    }

    @Tool(name="listProjects", description="List all available projects in the workspace with their detected natures (Java, C/C++, Python, etc.).", type="object")
    public String listProjects() 
    {
        return projectService.listProjects();
    }
    @Tool(name="getCurrentlyOpenedFile", description="Gets information about the currently active file in the Eclipse editor.", type="object")
    public String getCurrentlyOpenedFile() 
    {
        return editorService.getCurrentlyOpenedFile();
    }
    @Tool(name="getEditorSelection", description="Gets the currently selected text or lines in the active editor.", type="object")
    public String getEditorSelection() 
    {
        return editorService.getEditorSelection();
    }
    @Tool(name="getConsoleOutput", description="Retrieves the recent output from Eclipse console(s).", type="object")
    public String getConsoleOutput(
            @ToolParam(name="consoleName", description="Name of the specific console to retrieve (optional, leave empty for all or most recent console)", required=false) String consoleName,
            @ToolParam(name="maxLines", description="Maximum number of lines to retrieve (default: 100)", required=false) Integer maxLines,
            @ToolParam(name="includeAllConsoles", description="Whether to include output from all available consoles (default: false)", required=false) Boolean includeAllConsoles) 
    {
        return consoleService.getConsoleOutput( consoleName, maxLines, includeAllConsoles );
    }
}
