package com.github.gradusnikov.eclipse.assistai.resources;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IType;

/**
 * Wrapper for tool results that may contain cacheable resources.
 * Tools should return this instead of plain String to enable caching.
 */
public record ResourceToolResult(
    ResourceDescriptor descriptor,
    String content
) {
    
    /**
     * Creates result for a workspace file (IFile).
     */
    public static ResourceToolResult fromFile(IFile file, String content, String toolName) {
        return new ResourceToolResult(
            ResourceDescriptor.fromWorkspaceFile(file, toolName),
            content
        );
    }
    
    /**
     * Creates result for a Java type source (IType).
     */
    public static ResourceToolResult fromJavaType(IType type, String content, String toolName) {
        return new ResourceToolResult(
            ResourceDescriptor.fromJavaType(type, toolName),
            content
        );
    }
    
    /**
     * Creates result for a Java class by name (when IType lookup succeeded but we have path info).
     */
    public static ResourceToolResult fromClassName(String fullyQualifiedName, IPath workspacePath, String content, String toolName) {
        return new ResourceToolResult(
            ResourceDescriptor.fromClassName(fullyQualifiedName, workspacePath, toolName),
            content
        );
    }
    
    /**
     * Creates result for project layout.
     */
    public static ResourceToolResult forProjectLayout(String projectName, String content, String toolName) {
        return new ResourceToolResult(
            ResourceDescriptor.forProjectLayout(projectName, toolName),
            content
        );
    }
    
    /**
     * Creates result for console output.
     */
    public static ResourceToolResult forConsole(String consoleName, String content, String toolName) {
        return new ResourceToolResult(
            ResourceDescriptor.forConsole(consoleName, toolName),
            content
        );
    }
    
    /**
     * Creates a non-cacheable transient result.
     * Use this for error messages, transient data, etc.
     */
    public static ResourceToolResult transientResult(String content, String toolName) {
        return new ResourceToolResult(
            ResourceDescriptor.transientResult(toolName),
            content
        );
    }
    
    /**
     * Whether this result should be cached.
     */
    public boolean isCacheable() {
        return descriptor != null && descriptor.isCacheable();
    }
    
    /**
     * Gets the content, never null.
     */
    public String getContent() {
        return content != null ? content : "";
    }
}
