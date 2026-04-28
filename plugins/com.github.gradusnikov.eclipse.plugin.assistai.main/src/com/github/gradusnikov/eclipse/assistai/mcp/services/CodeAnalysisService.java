
package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaModelMarker;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.internal.corext.callhierarchy.CallHierarchy;
import org.eclipse.jdt.internal.corext.callhierarchy.MethodWrapper;

import com.github.gradusnikov.eclipse.assistai.services.AiIgnoreService;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Service interface for code analysis operations including
 * method call hierarchy and compilation errors.
 */
@Creatable
@Singleton
public class CodeAnalysisService 
{
    
    @Inject
    ILog logger;

    @Inject
    AiIgnoreService aiIgnoreService;
    
    /**
     * Retrieves the call hierarchy for a specified method.
     * 
     * @param fullyQualifiedClassName The fully qualified name of the class containing the method
     * @param methodName The name of the method to analyze
     * @param methodSignature The signature of the method (optional)
     * @param maxDepth Maximum depth of the call hierarchy to retrieve
     * @return A formatted string containing the call hierarchy
     */
    public String getMethodCallHierarchy(String fullyQualifiedClassName, 
                                  String methodName, 
                                  String methodSignature, 
                                  Integer maxDepth)
    {
        if (maxDepth == null || maxDepth < 1) 
        {
            maxDepth = 3; // Default to 3 levels of depth
        }
        
        StringBuilder result = new StringBuilder();
        result.append("# Call Hierarchy for Method: ").append(methodName).append("\n\n");
        
        try 
        {
            // Find the method in available Java projects
            IMethod targetMethod = null;
            
            for (IJavaProject project : getAvailableJavaProjects()) 
            {
                IType type = project.findType(fullyQualifiedClassName);
                if (type == null) 
                {
                    continue;
                }
                
                // If method signature is provided, use it to find the exact method
                if (methodSignature != null && !methodSignature.isEmpty()) 
                {
                    targetMethod = type.getMethod(methodName, methodSignature.split(","));
                    if (targetMethod != null && targetMethod.exists()) 
                    {
                        break;
                    }
                } 
                else 
                {
                    // Try to find the method without signature
                    IMethod[] methods = type.getMethods();
                    for (IMethod method : methods) {
                        if (method.getElementName().equals(methodName)) 
                        {
                            targetMethod = method;
                            break;
                        }
                    }
                    if (targetMethod != null) 
                    {
                        break;
                    }
                }
            }
            
            if (targetMethod == null || !targetMethod.exists()) 
            {
                return "Method '" + methodName + "' not found in class '" + fullyQualifiedClassName + "'.";
            }
            
            // Get the call hierarchy
            CallHierarchy callHierarchy = CallHierarchy.getDefault();
            MethodWrapper[] callerRoots = callHierarchy.getCallerRoots(new IMethod[] {targetMethod});
            
            if (callerRoots == null || callerRoots.length == 0) 
            {
                result.append("No callers found for this method.\n");
            }
            else 
            {
                result.append("## Callers:\n\n");
                collectCallHierarchy(callerRoots, 0, maxDepth, result);
            }
            
            // Also get callees (methods called by this method)
            MethodWrapper[] calleeRoots = callHierarchy.getCalleeRoots(new IMethod[] {targetMethod});
            
            if (calleeRoots != null && calleeRoots.length > 0) 
            {
                result.append("\n## Methods Called By ").append(methodName).append(":\n\n");
                collectCalleeHierarchy(calleeRoots, 0, 1, result); // Only go one level deep for callees
            }
            
            return result.toString();
            
        }
        catch (JavaModelException e) 
        {
            logger.error(e.getMessage(), e);
            throw new RuntimeException( "Error retrieving call hierarchy: " + ExceptionUtils.getRootCauseMessage( e ) );
        }

    }
    
    /**
     * Retrieves compilation errors and problems from the workspace or a specific project.
     * 
     * @param projectName The name of the project to check (optional)
     * @param severity Filter by severity level: 'ERROR', 'WARNING', or 'ALL'
     * @param maxResults Maximum number of problems to return
     * @return A formatted string containing compilation errors
     */
    public String getCompilationErrors(String projectName, String severity, Integer maxResults)
    {
        try 
        {
            // Set default values
            if (severity == null || severity.isBlank())
            {
                severity = "ALL";
            }
            else 
            {
                severity = severity.toUpperCase();
            }
            
            if (maxResults == null || maxResults < 1) 
            {
                maxResults = 50;
            }
            
            // Define severity filter
            int severityFilter = switch ( severity.toUpperCase() ) {
                case "ERROR" -> IMarker.SEVERITY_ERROR;
                case "WARNING" -> IMarker.SEVERITY_WARNING;
                default -> -1;
            };
            
            StringBuilder result = new StringBuilder();
            result.append("# Compilation Problems\n\n");
            
            // Get markers from workspace or specific project
            IMarker[] markers;
            if (projectName != null && !projectName.isBlank() ) 
            {
                IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
                if (project == null || !project.exists()) 
                {
                    throw new RuntimeException( "Project '" + projectName + "' not found." );
                }
                
                if (!project.isOpen()) 
                {
                    throw new RuntimeException( "Project '" + projectName + "' is closed." );
                }
                
                result.append("Project: ").append(projectName).append("\n\n");
                markers = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
            } 
            else 
            {
                result.append("Scope: All Projects\n\n");
                markers = ResourcesPlugin.getWorkspace().getRoot().findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
            }
            
            // Filter and sort markers
            List<IMarker> filteredMarkers = new ArrayList<>();
            for (IMarker marker : markers) 
            {
                Integer severityValue = (Integer) marker.getAttribute(IMarker.SEVERITY);
                if (severityFilter == -1 || (severityValue != null && severityValue.intValue() == severityFilter)) 
                {
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
            if (filteredMarkers.size() > maxResults) 
            {
                result.append("Showing ").append(maxResults).append(" of ").append(filteredMarkers.size())
                      .append(" problems found.\n\n");
                filteredMarkers = filteredMarkers.subList(0, maxResults);
            }
            else 
            {
                result.append("Found ").append(filteredMarkers.size()).append(" problems.\n\n");
            }
            
            if (filteredMarkers.isEmpty()) 
            {
                result.append("No compilation problems found with the specified criteria.");
                return result.toString();
            }
            
            // Group by resource
            Map<String, List<IMarker>> markersByResource = new HashMap<>();
            for (IMarker marker : filteredMarkers) 
            {
                IResource resource = marker.getResource();
                String resourcePath = resource.getFullPath().toString();
                if (!markersByResource.containsKey(resourcePath)) 
                {
                    markersByResource.put(resourcePath, new ArrayList<>());
                }
                markersByResource.get(resourcePath).add(marker);
            }
            
            // Output problems grouped by resource
            for (Map.Entry<String, List<IMarker>> entry : markersByResource.entrySet()) 
            {
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
                    if (marker.getType().equals(IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER)) 
                    {
                        var sourceId = marker.getAttribute(IJavaModelMarker.ID);
                        if (sourceId != null) 
                        {
                            result.append("  - Problem ID: ").append(sourceId).append("\n");
                        }
                        
                        // Try to get source code snippet if line number is available
                        if (lineNumber != null && marker.getResource() instanceof IFile) 
                        {
                            try 
                            {
                                IFile file = (IFile) marker.getResource();
                                String fileContent = readFileContent(file);
                                String[] lines = fileContent.split("\n");
                                
                                if (lineNumber > 0 && lineNumber <= lines.length) 
                                {
                                    int startLine = Math.max(1, lineNumber - 1);
                                    int endLine = Math.min(lines.length, lineNumber + 1);
                                    
                                    result.append("  - Context:\n```java\n");
                                    for (int i = startLine - 1; i < endLine; i++) 
                                    {
                                        if (i == lineNumber - 1) 
                                        {
                                            result.append("> "); // Highlight the error line
                                        }
                                        else 
                                        {
                                            result.append("  ");
                                        }
                                        result.append(lines[i]).append("\n");
                                    }
                                    result.append("```\n");
                                }
                            } 
                            catch (Exception e) 
                            {
                                // Skip context if we can't read the file
                            }
                        }
                    }
                }
                
                result.append("\n");
            }
            
            return result.toString();
            
        }
        catch (CoreException e) 
        {
            logger.error(e.getMessage(), e);
            throw new RuntimeException( "Error retrieving compilation problems: " + ExceptionUtils.getStackTrace(  e )  );
        }

    }
    
    /**
     * Helper method to read file content
     */
    private String readFileContent(IFile file) throws CoreException, IOException {
        aiIgnoreService.assertAccessAllowed(file);

        try (InputStream is = file.getContents()) 
        {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) != -1) 
            {
                result.write(buffer, 0, length);
            }
            return result.toString(file.getCharset());
        }
    }    
    
    private void collectCallHierarchy(MethodWrapper[] callers, int level, int maxDepth, StringBuilder result) {
       
        if (level >= maxDepth) 
        {
            return;
        }
        
        for (MethodWrapper caller : callers) 
        {
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
                    
                } 
                catch (Exception e) 
                {
                    logger.error(e.getMessage(), e);
                    result.append(" [Error retrieving method details]\n");
                }
            }
        }
    }
    
    private void collectCalleeHierarchy(MethodWrapper[] callees, int level, int maxDepth, StringBuilder result) {
        if (level >= maxDepth) 
        {
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
    
    /**
     * Retrieves a list of all available Java projects in the current workspace.
     * It filters out non-Java projects and only includes projects that are open and have the Java nature.
     *
     * @return A list of {@link IJavaProject} representing the available Java projects.
     * @throws RuntimeException if an error occurs while accessing project information.
     */
    /**
     * Retrieves the type hierarchy (supertypes and subtypes) for a given type.
     *
     * @param fullyQualifiedClassName The fully qualified name of the class
     * @return A formatted string showing the type hierarchy
     */
    public String getTypeHierarchy(String fullyQualifiedClassName)
    {
        try
        {
            IType targetType = null;
            for (IJavaProject project : getAvailableJavaProjects())
            {
                targetType = project.findType(fullyQualifiedClassName);
                if (targetType != null) break;
            }
            if (targetType == null)
            {
                return "Type '" + fullyQualifiedClassName + "' not found.";
            }

            var result = new StringBuilder();
            result.append("# Type Hierarchy for ").append(fullyQualifiedClassName).append("\n\n");

            // Supertypes
            result.append("## Supertypes:\n");
            var hierarchy = targetType.newTypeHierarchy(new NullProgressMonitor());
            IType[] supertypes = hierarchy.getAllSuperclasses(targetType);
            for (int i = 0; i < supertypes.length; i++)
            {
                for (int j = 0; j < i + 1; j++) result.append("  ");
                result.append("- ").append(supertypes[i].getFullyQualifiedName()).append("\n");
            }

            // Interfaces
            IType[] superInterfaces = hierarchy.getAllSuperInterfaces(targetType);
            if (superInterfaces.length > 0)
            {
                result.append("\n## Implemented Interfaces:\n");
                for (IType iface : superInterfaces)
                {
                    result.append("- ").append(iface.getFullyQualifiedName()).append("\n");
                }
            }

            // Subtypes
            IType[] subtypes = hierarchy.getAllSubtypes(targetType);
            if (subtypes.length > 0)
            {
                result.append("\n## Subtypes:\n");
                for (IType subtype : subtypes)
                {
                    result.append("- ").append(subtype.getFullyQualifiedName()).append("\n");
                }
            }
            else
            {
                result.append("\nNo subtypes found.\n");
            }

            return result.toString();
        }
        catch (Exception e)
        {
            logger.error(e.getMessage(), e);
            return "Error retrieving type hierarchy: " + e.getMessage();
        }
    }

    /**
     * Finds all references to a Java element (type, method, or field) across the workspace.
     *
     * @param fullyQualifiedClassName The fully qualified name of the class
     * @param elementName Optional method or field name within the class (null to search for the class itself)
     * @return A formatted string listing all references
     */
    public String findReferences(String fullyQualifiedClassName, String elementName)
    {
        try
        {
            IType targetType = null;
            for (IJavaProject project : getAvailableJavaProjects())
            {
                targetType = project.findType(fullyQualifiedClassName);
                if (targetType != null) break;
            }
            if (targetType == null)
            {
                return "Type '" + fullyQualifiedClassName + "' not found.";
            }

            IJavaElement searchElement;
            if (elementName != null && !elementName.isBlank())
            {
                // Try to find as method first
                IJavaElement found = null;
                for (IMethod method : targetType.getMethods())
                {
                    if (method.getElementName().equals(elementName))
                    {
                        found = method;
                        break;
                    }
                }
                // Then try as field
                if (found == null)
                {
                    var field = targetType.getField(elementName);
                    if (field != null && field.exists())
                    {
                        found = field;
                    }
                }
                if (found == null)
                {
                    return "Element '" + elementName + "' not found in '" + fullyQualifiedClassName + "'.";
                }
                searchElement = found;
            }
            else
            {
                searchElement = targetType;
            }

            // Use Eclipse's search engine
            var searchEngine = new org.eclipse.jdt.core.search.SearchEngine();
            var pattern = org.eclipse.jdt.core.search.SearchPattern.createPattern(
                    searchElement,
                    org.eclipse.jdt.core.search.IJavaSearchConstants.REFERENCES);
            var scope = org.eclipse.jdt.core.search.SearchEngine.createWorkspaceScope();

            var references = new ArrayList<String>();
            var requestor = new org.eclipse.jdt.core.search.SearchRequestor()
            {
                @Override
                public void acceptSearchMatch(org.eclipse.jdt.core.search.SearchMatch match)
                {
                    var element = match.getElement();
                    if (element instanceof IJavaElement)
                    {
                        var javaElement = (IJavaElement) element;
                        var resource = match.getResource();
                        String location = resource != null ? resource.getFullPath().toString() : "unknown";
                        int line = -1;
                        try
                        {
                            if (resource instanceof IFile)
                            {
                                var file = (IFile) resource;
                                String content = readFileContent(file);
                                int offset = match.getOffset();
                                line = content.substring(0, Math.min(offset, content.length())).split("\n").length;
                            }
                        }
                        catch (Exception e) { /* ignore */ }

                        String ref = location + (line > 0 ? ":" + line : "") +
                                " in " + javaElement.getElementName();
                        references.add(ref);
                    }
                }
            };

            searchEngine.search(pattern, 
                    new org.eclipse.jdt.core.search.SearchParticipant[] { org.eclipse.jdt.core.search.SearchEngine.getDefaultSearchParticipant() },
                    scope, requestor, new NullProgressMonitor());

            var result = new StringBuilder();
            String label = elementName != null ? fullyQualifiedClassName + "." + elementName : fullyQualifiedClassName;
            result.append("# References to ").append(label).append("\n\n");
            result.append("Found ").append(references.size()).append(" reference(s).\n\n");

            for (String ref : references)
            {
                result.append("- ").append(ref).append("\n");
            }

            return result.toString();
        }
        catch (Exception e)
        {
            logger.error(e.getMessage(), e);
            return "Error finding references: " + e.getMessage();
        }
    }

    /**
     * Gets available quick fixes for compilation errors in a file.
     *
     * @param projectName The project name
     * @param filePath The file path relative to the project
     * @param lineNumber Optional line number to filter fixes for
     * @return A formatted string listing available quick fixes
     */
    public String getQuickFixes(String projectName, String filePath, Integer lineNumber)
    {
        try
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (!project.exists() || !project.isOpen())
            {
                return "Error: Project '" + projectName + "' not found or not open.";
            }

            IFile file = project.getFile(filePath);
            if (!file.exists())
            {
                return "Error: File '" + filePath + "' not found.";
            }

            // Get problem markers for the file
            IMarker[] markers = file.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO);

            var result = new StringBuilder();
            result.append("# Quick Fixes for ").append(filePath).append("\n\n");

            int fixCount = 0;
            for (IMarker marker : markers)
            {
                Integer markerLine = (Integer) marker.getAttribute(IMarker.LINE_NUMBER);
                if (lineNumber != null && markerLine != null && !lineNumber.equals(markerLine))
                {
                    continue;
                }

                Integer severity = (Integer) marker.getAttribute(IMarker.SEVERITY);
                String sevText = severity != null && severity == IMarker.SEVERITY_ERROR ? "ERROR" : "WARNING";
                String message = (String) marker.getAttribute(IMarker.MESSAGE);

                result.append("## ").append(sevText).append(" at line ").append(markerLine).append("\n");
                result.append("Message: ").append(message).append("\n");

                // Try to get quick fix proposals via JDT
                try
                {
                    IJavaProject javaProject = JavaCore.create(project);
                    ICompilationUnit cu = JavaCore.createCompilationUnitFrom(file);
                    if (cu != null)
                    {
                        // Get the problem ID from the marker
                        var problemId = marker.getAttribute(IJavaModelMarker.ID);
                        if (problemId != null)
                        {
                            result.append("Problem ID: ").append(problemId).append("\n");
                        }

                        // List corrections using marker attributes
                        var args = (String[]) marker.getAttribute("arguments");
                        if (args != null && args.length > 0)
                        {
                            result.append("Suggestions:\n");
                            for (String arg : args)
                            {
                                result.append("  - ").append(arg).append("\n");
                            }
                        }
                    }
                }
                catch (Exception e)
                {
                    // Quick fix retrieval is best-effort
                }

                result.append("\n");
                fixCount++;
            }

            if (fixCount == 0)
            {
                result.append("No problems found");
                if (lineNumber != null) result.append(" at line ").append(lineNumber);
                result.append(".\n");
            }

            return result.toString();
        }
        catch (Exception e)
        {
            logger.error(e.getMessage(), e);
            return "Error getting quick fixes: " + e.getMessage();
        }
    }

    /**
     * Gets import suggestions for unresolved types in a compilation unit.
     *
     * @param projectName The project name
     * @param filePath The file path relative to the project
     * @return A formatted string listing import suggestions
     */
    public String getImportSuggestions(String projectName, String filePath)
    {
        try
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (!project.exists() || !project.isOpen())
            {
                return "Error: Project '" + projectName + "' not found or not open.";
            }

            IFile file = project.getFile(filePath);
            if (!file.exists())
            {
                return "Error: File '" + filePath + "' not found.";
            }

            // Get problem markers that indicate unresolved types
            IMarker[] markers = file.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO);

            var result = new StringBuilder();
            result.append("# Import Suggestions for ").append(filePath).append("\n\n");

            IJavaProject javaProject = JavaCore.create(project);
            int suggestionCount = 0;

            for (IMarker marker : markers)
            {
                String message = (String) marker.getAttribute(IMarker.MESSAGE);
                Integer severity = (Integer) marker.getAttribute(IMarker.SEVERITY);

                if (message == null || severity == null || severity != IMarker.SEVERITY_ERROR)
                {
                    continue;
                }

                // Check if this is an unresolved type error
                if (message.contains("cannot be resolved to a type") || message.contains("cannot be resolved"))
                {
                    // Extract the unresolved type name from the error message
                    String unresolvedType = message.split(" ")[0].replace("\"", "");
                    Integer line = (Integer) marker.getAttribute(IMarker.LINE_NUMBER);

                    result.append("## Unresolved: `").append(unresolvedType).append("`");
                    if (line != null) result.append(" (line ").append(line).append(")");
                    result.append("\n");

                    // Search for matching types in the workspace
                    var searchEngine = new org.eclipse.jdt.core.search.SearchEngine();
                    var matches = new ArrayList<String>();

                    try
                    {
                        searchEngine.searchAllTypeNames(
                                null, // any package
                                org.eclipse.jdt.core.search.SearchPattern.R_EXACT_MATCH,
                                unresolvedType.toCharArray(),
                                org.eclipse.jdt.core.search.SearchPattern.R_EXACT_MATCH,
                                org.eclipse.jdt.core.search.IJavaSearchConstants.TYPE,
                                org.eclipse.jdt.core.search.SearchEngine.createWorkspaceScope(),
                                new org.eclipse.jdt.core.search.TypeNameRequestor()
                                {
                                    @Override
                                    public void acceptType(int modifiers, char[] packageName, char[] simpleTypeName,
                                            char[][] enclosingTypeNames, String path)
                                    {
                                        String fqn = new String(packageName) + "." + new String(simpleTypeName);
                                        matches.add(fqn);
                                    }
                                },
                                org.eclipse.jdt.core.search.IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
                                new NullProgressMonitor());
                    }
                    catch (Exception e) { /* best effort */ }

                    if (!matches.isEmpty())
                    {
                        result.append("Candidates:\n");
                        for (String match : matches)
                        {
                            result.append("  - `import ").append(match).append(";`\n");
                        }
                    }
                    else
                    {
                        result.append("No matching types found in workspace.\n");
                    }
                    result.append("\n");
                    suggestionCount++;
                }
            }

            if (suggestionCount == 0)
            {
                result.append("No unresolved type errors found. Consider using `organizeImports` to clean up imports.\n");
            }

            return result.toString();
        }
        catch (Exception e)
        {
            logger.error(e.getMessage(), e);
            return "Error getting import suggestions: " + e.getMessage();
        }
    }

    private List<IJavaProject> getAvailableJavaProjects()
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
    
}
