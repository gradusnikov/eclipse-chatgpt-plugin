
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
import org.eclipse.jdt.ui.text.java.IJavaCompletionProposal;

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
                    
                    // Emit the marker's unique ID so it can be referenced by executeQuickFix
                    result.append("  - Marker ID: ").append(marker.getId()).append("\n");

                    // Java-specific: emit internal problem ID
                    if (marker.getType().equals(IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER)) 
                    {
                        var sourceId = marker.getAttribute(IJavaModelMarker.ID);
                        if (sourceId != null) 
                        {
                            result.append("  - Problem ID: ").append(sourceId).append("\n");
                        }
                    }

                    // Context snippet â any marker attached to an IFile with a line number
                    if (lineNumber != null && marker.getResource() instanceof IFile ifile)
                    {
                        try 
                        {
                            String fileContent = readFileContent(ifile);
                            String[] lines = fileContent.split("\n");
                            
                            if (lineNumber > 0 && lineNumber <= lines.length) 
                            {
                                int startLine = Math.max(1, lineNumber - 1);
                                int endLine = Math.min(lines.length, lineNumber + 1);
                                // Use a neutral fence (no language tag) for non-Java files
                                String ext = ifile.getFileExtension();
                                String lang = ext != null ? ext : "";
                                result.append("  - Context:\n```").append(lang).append("\n");
                                for (int i = startLine - 1; i < endLine; i++) 
                                {
                                    result.append(i == lineNumber - 1 ? "> " : "  ");
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

                    appendQuickFixBlock(marker, result, "  ");
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
     * Appends a quick-fix block for {@code marker} to {@code sb}.
     * Produces numbered proposals with descriptions and a hint to the LLM to
     * call {@code executeQuickFix} with the chosen index.
     * Best-effort â any exception is silently swallowed.
     *
     * @param marker the problem marker
     * @param sb     the builder to append to
     * @param indent line prefix (e.g. {@code "  "} for two-space indent)
     */
    private void appendQuickFixBlock(IMarker marker, StringBuilder sb, String indent)
    {
        try
        {
            List<QuickFix> fixes = collectQuickFixes(marker);
            if (fixes.isEmpty())
            {
                sb.append(indent).append("- Quick fixes: none available\n");
            }
            else
            {
                sb.append(indent).append("- Quick fixes (pass index to executeQuickFix):\n");
                for (int i = 0; i < fixes.size(); i++)
                {
                    QuickFix fix = fixes.get(i);
                    sb.append(indent).append("    - [").append(i).append("] ").append(fix.label());
                    if (fix.description() != null && !fix.description().isBlank())
                    {
                        sb.append(" \u2013 ").append(fix.description());
                    }
                    sb.append("\n");
                }
            }
        }
        catch (Exception e)
        {
            // quick fix collection is best-effort; skip silently
        }
    }


    /**
     * Unified quick fix descriptor â wraps either a JDT IJavaCompletionProposal
     * or a platform IMarkerResolution so both can be presented and applied uniformly.
     */
    private record QuickFix(String label, String description, java.util.function.Consumer<IMarker> applyFn)
    {
        void apply(IMarker marker) { applyFn.accept(marker); }
    }

    /**
     * Executes a specific quick fix proposal for a problem marker.
     *
     * @param markerId      The marker ID as returned by getCompilationErrors or getQuickFixes
     * @param proposalIndex The index of the proposal to apply (0-based, from the quick fixes list)
     * @return Result message indicating success or failure
     */
    public String executeQuickFix(long markerId, int proposalIndex)
    {
        try
        {
            IMarker marker = findMarkerById(markerId);
            if (marker == null)
            {
                return "Error: Marker with ID " + markerId + " not found. It may have been resolved already.";
            }

            List<QuickFix> fixes = collectQuickFixes(marker);
            if (fixes.isEmpty())
            {
                return "Error: No quick fix proposals available for marker " + markerId + ".";
            }
            if (proposalIndex < 0 || proposalIndex >= fixes.size())
            {
                return "Error: Proposal index " + proposalIndex + " is out of range (0-" + (fixes.size() - 1) + ").";
            }

            QuickFix fix = fixes.get(proposalIndex);
            fix.apply(marker);
            return "Quick fix applied: \"" + fix.label() + "\" on marker " + markerId + ".";
        }
        catch (Exception e)
        {
            logger.error(e.getMessage(), e);
            return "Error applying quick fix: " + e.getMessage();
        }
    }

    /**
     * Collects quick fix proposals for any problem marker using two mechanisms:
     * 1. Platform IMarkerHelpRegistry (works for all marker types: JDT, PDE, m2e, build path, â¦)
     * 2. JDT JavaCorrectionProcessor (Java-only, richer proposals; deduped against registry results)
     */
    private List<QuickFix> collectQuickFixes(IMarker marker)
    {
        List<QuickFix> fixes = new ArrayList<>();

        // --- Mechanism 1: Platform IMarkerHelpRegistry (generic, all marker types) ---
        try
        {
            org.eclipse.ui.ide.IDE.getMarkerHelpRegistry();  // touch to ensure registry is initialized
            org.eclipse.ui.IMarkerHelpRegistry registry = org.eclipse.ui.ide.IDE.getMarkerHelpRegistry();
            if (registry.hasResolutions(marker))
            {
                for (org.eclipse.ui.IMarkerResolution r : registry.getResolutions(marker))
                {
                    String label = r.getLabel();
                    String desc = (r instanceof org.eclipse.ui.IMarkerResolution2 r2) ? r2.getDescription() : null;
                    fixes.add(new QuickFix(label, desc, m -> r.run(m)));
                }
            }
        }
        catch (Exception e)
        {
            // registry lookup is best-effort
        }

        // --- Mechanism 2: JDT JavaCorrectionProcessor (Java markers only, supplements registry) ---
        try
        {
            if (marker.getType().equals(IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER)
                    && marker.getResource() instanceof IFile file)
            {
                ICompilationUnit cu = (ICompilationUnit) JavaCore.create(file);
                if (cu != null && cu.exists())
                {
                    int id      = marker.getAttribute(IJavaModelMarker.ID, -1);
                    int start   = marker.getAttribute(IMarker.CHAR_START, -1);
                    int end     = marker.getAttribute(IMarker.CHAR_END, -1);
                    boolean isError = marker.getAttribute(IMarker.SEVERITY, 0) == IMarker.SEVERITY_ERROR;
                    String[] args = readMarkerArguments(marker);
                    String markerType = marker.getType();

                    if (start >= 0 && end >= 0)
                    {
                        org.eclipse.jdt.internal.ui.text.correction.ProblemLocation location =
                            new org.eclipse.jdt.internal.ui.text.correction.ProblemLocation(
                                start, end - start, id, args, isError, markerType);

                        org.eclipse.jdt.internal.ui.text.correction.AssistContext context =
                            new org.eclipse.jdt.internal.ui.text.correction.AssistContext(cu, start, end - start);

                        List<IJavaCompletionProposal> jdtProposals = new ArrayList<>();
                        org.eclipse.jdt.internal.ui.text.correction.JavaCorrectionProcessor.collectCorrections(
                            context, new org.eclipse.jdt.ui.text.java.IProblemLocation[]{ location }, jdtProposals);

                        jdtProposals.sort((a, b) -> Integer.compare(b.getRelevance(), a.getRelevance()));

                        // Add JDT proposals that aren't already covered by the registry (dedupe by label)
                        java.util.Set<String> existingLabels = new java.util.HashSet<>();
                        fixes.forEach(f -> existingLabels.add(f.label()));

                        for (IJavaCompletionProposal p : jdtProposals)
                        {
                            String label = p.getDisplayString();
                            if (existingLabels.add(label))  // true if newly added
                            {
                                fixes.add(new QuickFix(label, null, m -> applyJdtProposal(p, m)));
                            }
                        }
                    }
                }
            }
        }
        catch (Exception e)
        {
            // JDT supplement is best-effort
        }

        return fixes;
    }

    /** Applies a JDT IJavaCompletionProposal headlessly. */
    private void applyJdtProposal(IJavaCompletionProposal proposal, IMarker marker)
    {
        try
        {
            if (proposal instanceof org.eclipse.jdt.core.manipulation.ICUCorrectionProposal icp)
            {
                icp.getTextChange().perform(new NullProgressMonitor());
            }
            else
            {
                // Fallback via TextFileDocumentProvider
                IFile file = (IFile) marker.getResource();
                org.eclipse.ui.editors.text.TextFileDocumentProvider provider =
                    new org.eclipse.ui.editors.text.TextFileDocumentProvider();
                provider.connect(file);
                try
                {
                    org.eclipse.jface.text.IDocument doc = provider.getDocument(file);
                    if (doc == null)
                    {
                        throw new RuntimeException("Could not open document for " + file.getFullPath());
                    }
                    proposal.apply(doc);
                    provider.saveDocument(new NullProgressMonitor(), file, doc, true);
                }
                finally
                {
                    provider.disconnect(file);
                }
            }
        }
        catch (RuntimeException re)
        {
            throw re;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /** Reads the raw problem arguments from a marker. */
    private String[] readMarkerArguments(IMarker marker)
    {
        try
        {
            Object argsAttr = marker.getAttribute("arguments");
            if (argsAttr instanceof String[] sa)   return sa;
            if (argsAttr instanceof String s && !s.isBlank()) return s.split("#");
        }
        catch (Exception ex) { /* ignore */ }
        return new String[0];
    }

    /**
     * Finds an IMarker by its numeric ID across the entire workspace.
     */
    private IMarker findMarkerById(long markerId)
    {
        try
        {
            IMarker[] all = ResourcesPlugin.getWorkspace().getRoot()
                .findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
            for (IMarker m : all)
            {
                if (m.getId() == markerId)
                {
                    return m;
                }
            }
        }
        catch (CoreException e)
        {
            // ignore
        }
        return null;
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