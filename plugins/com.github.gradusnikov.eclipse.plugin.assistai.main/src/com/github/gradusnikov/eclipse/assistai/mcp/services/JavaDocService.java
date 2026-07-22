package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jface.text.Document;
import org.eclipse.ui.editors.text.TextFileDocumentProvider;

import com.github.gradusnikov.eclipse.assistai.resources.ResourceToolResult;
import com.github.gradusnikov.eclipse.assistai.services.AiIgnoreService;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;

import jakarta.inject.Inject;

/**
 * Service for retrieving JavaDoc and source code information from Java
 * projects.
 */
@Creatable
public class JavaDocService
{

    @Inject
    private ILog                logger;

    @Inject
    private AiIgnoreService     aiIgnoreService;

    @Inject
    private ClassFileDecompiler classFileDecompiler;

    /**
     * Retrieves the attached JavaDoc documentation for a given class within the
     * available Java projects. It searches all projects for the JavaDoc and if
     * found, it returns the JavaDoc content. If no JavaDoc is found, it returns
     * a message stating that JavaDoc is not available for the specified class.
     *
     * @param fullyQualifiedClassName
     *            The fully qualified name of the class to find the JavaDoc for.
     * @return The JavaDoc string if available; otherwise, a message indicating
     *         it is not available.
     */
    public String getJavaDoc( String fullyQualifiedClassName )
    {
        return getAvailableJavaProjects().stream().map( project -> getAttachedJavadoc( fullyQualifiedClassName, project ) ).filter( Objects::nonNull )
                .filter( Predicate.not( String::isBlank ) ).findAny().orElse( "JavaDoc is not available for " + fullyQualifiedClassName );
    }

    /**
     * Retrieves the source code attached to the specified class within the
     * available Java projects. It searches all projects for the source code and
     * if found, returns the source content. If no source code is found or an
     * exception occurs, it returns a message indicating that the source is not
     * available for the specified class.
     *
     * @param fullyQualifiedClassName
     *            The fully qualified name of the class for which to find the
     *            source code.
     * @return The source code string if available; otherwise, a message
     *         indicating it is not available.
     */
    public String getSource( String fullyQualifiedClassName )
    {
        return getSourceWithResource( fullyQualifiedClassName ).getContent();
    }

    /**
     * Retrieves a list of all available Java projects in the current workspace.
     * It filters out non-Java projects and only includes projects that are open
     * and have the Java nature.
     *
     * @return A list of {@link IJavaProject} representing the available Java
     *         projects.
     * @throws RuntimeException
     *             if an error occurs while accessing project information.
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
     * Gathers and returns JavaDoc information for a specified class within a
     * given Java project. It retrieves the JavaDoc by looking up the type
     * corresponding to the fully qualified class name and extracting its
     * documentation, as well as the documentation of its children elements.
     *
     * @param fullyQualifiedClassName
     *            The fully qualified name of the class for which to retrieve
     *            JavaDoc.
     * @param javaProject
     *            The Java project within which to search for the class.
     * @return A string containing the JavaDoc for the class and its children,
     *         or an empty string if not found.
     */
    private String getAttachedJavadoc( String fullyQualifiedClassName, IJavaProject javaProject )
    {
        String javaDoc = "";
        try
        {
            IType type = javaProject.findType( fullyQualifiedClassName );
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
     * This method extracts the JavaDoc directly if it is attached to the
     * member, or from the source buffer if it is available. If no JavaDoc is
     * found, this method returns an empty string.
     *
     * @param member
     *            The member for which to retrieve the JavaDoc documentation.
     * @return A string containing the JavaDoc documentation, or an empty string
     *         if none is found.
     * @throws JavaModelException
     *             if an error occurs while retrieving the JavaDoc.
     */
    private String getMemberJavaDoc( IMember member ) throws JavaModelException
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
     * Retrieves source for a workspace or referenced-library class. Original
     * source (including source attachments) is preferred; binary classes are
     * decompiled only when no source is attached.
     *
     * @param fullyQualifiedClassName
     *            the fully qualified class name
     * @return source content and resource metadata, or a transient not-found
     *         result
     */
    public ResourceToolResult getSourceWithResource( String fullyQualifiedClassName )
    {
        final String toolName = "getSource";

        for ( IJavaProject javaProject : getAvailableJavaProjects() )
        {
            try
            {
                IType type = javaProject.findType( fullyQualifiedClassName );
                if ( type == null )
                {
                    continue;
                }

                IResource resource = getTypeResource( type );
                if ( resource instanceof IFile file )
                {
                    if ( aiIgnoreService.isExcluded( file ) )
                    {
                        return ResourceToolResult
                                .transientResult( "Access denied: '" + fullyQualifiedClassName + "' is excluded from AI processing by .aiignore.", toolName );
                    }

                    String workspaceSource = readWorkspaceSource( file );
                    if ( workspaceSource != null && !workspaceSource.isBlank() )
                    {
                        return ResourceToolResult.fromJavaType( type, workspaceSource, toolName );
                    }
                }

                IClassFile classFile = type.getClassFile();
                String attachedSource = classFile == null ? type.getSource() : classFile.getSource();
                if ( ( attachedSource == null || attachedSource.isBlank() ) && classFile != null )
                {
                    attachedSource = type.getSource();
                }
                if ( attachedSource != null && !attachedSource.isBlank() )
                {
                    return ResourceToolResult.fromJavaType( type, attachedSource, toolName );
                }

                Optional<String> decompiledSource = classFileDecompiler.decompile( classFile );
                if ( decompiledSource.isPresent() )
                {
                    return ResourceToolResult.fromJavaType( type, decompiledSource.get(), toolName );
                }
            }
            catch ( Exception e )
            {
                logger.error( "Could not retrieve source for " + fullyQualifiedClassName, e );
            }
        }

        return ResourceToolResult.transientResult( "Source is not available for " + fullyQualifiedClassName, toolName );
    }

    private IResource getTypeResource( IType type ) throws JavaModelException
    {
        IResource resource = type.getCorrespondingResource();
        if ( resource == null )
        {
            resource = type.getResource();
        }
        if ( resource == null )
        {
            resource = type.getUnderlyingResource();
        }
        return resource;
    }

    private String readWorkspaceSource( IFile file ) throws CoreException
    {
        TextFileDocumentProvider provider = new TextFileDocumentProvider();
        provider.connect( file );
        try
        {
            Document document = (Document) provider.getDocument( file );
            return document == null ? null : document.get();
        }
        finally
        {
            provider.disconnect( file );
        }
    }
}
