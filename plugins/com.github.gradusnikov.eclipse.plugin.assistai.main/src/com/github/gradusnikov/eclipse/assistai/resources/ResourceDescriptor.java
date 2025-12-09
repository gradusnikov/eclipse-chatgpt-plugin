package com.github.gradusnikov.eclipse.assistai.resources;

import java.net.URI;
import java.util.Optional;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IType;

/**
 * Describes a resource that can be cached. Provides factory methods
 * for creating descriptors from Eclipse resource types.
 * 
 * URI Schemes:
 * - workspace:///ProjectName/path/to/file.java  â Workspace files (IFile)
 * - jdt:///com.example.ClassName                â Java types (IType)
 * - project:///ProjectName/layout               â Project structure
 * - console:///ConsoleName                      â Console output
 */
public record ResourceDescriptor(
    URI uri,
    ResourceType type,
    String displayName,
    IPath workspacePath,    // null for non-workspace resources
    String toolName
) {
    
    public enum ResourceType {
        WORKSPACE_FILE,     // IFile in workspace
        JAVA_TYPE,          // IType (class/interface)
        PROJECT_LAYOUT,     // Project structure
        CONSOLE_OUTPUT,     // Console content
        EXTERNAL_FILE,      // File outside workspace
        QUERY_RESULT,       // Database/search result
        TRANSIENT           // Non-cacheable
    }
    
    /**
     * Creates descriptor from an Eclipse workspace file.
     */
    public static ResourceDescriptor fromWorkspaceFile(IFile file, String toolName) {
        IPath fullPath = file.getFullPath();
        URI uri = createWorkspaceUri(fullPath);
        
        return new ResourceDescriptor(
            uri,
            ResourceType.WORKSPACE_FILE,
            file.getName(),
            fullPath,
            toolName
        );
    }
    
    /**
     * Creates descriptor from an Eclipse IResource.
     */
    public static ResourceDescriptor fromResource(IResource resource, String toolName) {
        if (resource instanceof IFile file) {
            return fromWorkspaceFile(file, toolName);
        }
        
        IPath fullPath = resource.getFullPath();
        URI uri = createWorkspaceUri(fullPath);
        
        return new ResourceDescriptor(
            uri,
            ResourceType.WORKSPACE_FILE,
            resource.getName(),
            fullPath,
            toolName
        );
    }
    
    /**
     * Creates descriptor from a JDT IType.
     */
    public static ResourceDescriptor fromJavaType(IType type, String toolName) {
        String fqn = type.getFullyQualifiedName();
        URI uri = URI.create("jdt:///" + fqn);
        
        // Try to get workspace path if source is available
        IPath workspacePath = null;
        try {
            IResource resource = type.getResource();
            if (resource != null) {
                workspacePath = resource.getFullPath();
            }
        } catch (Exception e) {
            // Ignore - might be from a JAR
        }
        
        return new ResourceDescriptor(
            uri,
            ResourceType.JAVA_TYPE,
            type.getElementName() + ".java",
            workspacePath,
            toolName
        );
    }
    
    /**
     * Creates descriptor from a fully qualified class name (when IType is not available).
     */
    public static ResourceDescriptor fromClassName(String fullyQualifiedName, IPath workspacePath, String toolName) {
        URI uri = URI.create("jdt:///" + fullyQualifiedName);
        String simpleName = fullyQualifiedName.substring(fullyQualifiedName.lastIndexOf('.') + 1);
        
        return new ResourceDescriptor(
            uri,
            ResourceType.JAVA_TYPE,
            simpleName + ".java",
            workspacePath,
            toolName
        );
    }
    
    /**
     * Creates descriptor for project layout.
     */
    public static ResourceDescriptor forProjectLayout(String projectName, String toolName) {
        URI uri = URI.create("project:///" + encode(projectName) + "/layout");
        IPath workspacePath = IPath.fromOSString("/" + projectName);
        
        return new ResourceDescriptor(
            uri,
            ResourceType.PROJECT_LAYOUT,
            projectName + " (layout)",
            workspacePath,
            toolName
        );
    }
    
    /**
     * Creates descriptor for console output.
     */
    public static ResourceDescriptor forConsole(String consoleName, String toolName) {
        URI uri = URI.create("console:///" + encode(consoleName));
        
        return new ResourceDescriptor(
            uri,
            ResourceType.CONSOLE_OUTPUT,
            consoleName,
            null,
            toolName
        );
    }
    
    /**
     * Creates descriptor for transient (non-cacheable) results.
     */
    public static ResourceDescriptor transientResult(String toolName) {
        return new ResourceDescriptor(
            null,
            ResourceType.TRANSIENT,
            "transient",
            null,
            toolName
        );
    }
    
    /**
     * Checks if this resource exists in the workspace.
     */
    public boolean existsInWorkspace() {
        if (workspacePath == null) {
            return false;
        }
        IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(workspacePath);
        return resource != null && resource.exists();
    }
    
    /**
     * Gets the IFile if this is a workspace file resource.
     */
    public Optional<IFile> toWorkspaceFile() {
        if (workspacePath == null) {
            return Optional.empty();
        }
        IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(workspacePath);
        return resource instanceof IFile file ? Optional.of(file) : Optional.empty();
    }
    
    /**
     * Returns true if this resource should be cached.
     */
    public boolean isCacheable() {
        return type != ResourceType.TRANSIENT && uri != null;
    }
    
    // --- Helper methods ---
    
    private static URI createWorkspaceUri(IPath path) {
        return URI.create("workspace://" + path.toString());
    }
    
    private static String encode(String value) {
        return value.replace(" ", "%20")
                    .replace("#", "%23")
                    .replace("?", "%3F");
    }
}
