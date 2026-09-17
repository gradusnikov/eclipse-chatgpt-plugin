package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jface.text.Document;
import org.eclipse.ui.editors.text.TextFileDocumentProvider;

import com.github.gradusnikov.eclipse.assistai.mcp.results.Diagnostic;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.JavaDocResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TypeResolutionResponse;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceDescriptor;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceReadResult;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceVersion;
import com.github.gradusnikov.eclipse.assistai.resources.SourceOrigin;
import com.github.gradusnikov.eclipse.assistai.services.AiIgnoreService;
import com.github.gradusnikov.eclipse.assistai.tools.Javadocs;

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
     * The documentation of a type and of the members it declares, as Markdown.
     * <p>
     * Rendered through {@link Javadocs}, that is by JDT's hover engine, so an undocumented
     * override reports its supertype's text and says so. This is the one place a project's
     * Javadoc location is consulted for a binary member without source: a single type the
     * caller asked for by name, not every type in a search result.
     *
     * @param memberName a field, method or member type name to restrict the members to,
     *            or null for all of them; every overload of a method is listed
     * @param javadoc SUMMARY or FULL; the tool exists to render documentation, so NONE is
     *            rejected rather than answered with nothing
     * @return the documentation with its status, or a not-found result carrying the reason
     *         as a code
     */
    public JavaDocResponse getJavaDoc( String fullyQualifiedClassName, String memberName, Javadocs.Detail javadoc )
    {
        if ( javadoc == Javadocs.Detail.NONE )
        {
            throw new IllegalArgumentException( "getJavaDoc renders documentation; javadoc must be SUMMARY or FULL" );
        }
        // Null once blank, so one test below stands for both spellings of "every member".
        String wanted = memberName == null || memberName.isBlank() ? null : memberName;

        for ( IJavaProject javaProject : getAvailableJavaProjects() )
        {
            IType type;
            try
            {
                type = javaProject.findType( fullyQualifiedClassName );
            }
            catch ( JavaModelException e )
            {
                logger.error( e.getMessage(), e );
                continue;
            }
            if ( type == null )
            {
                continue;
            }

            String projectName = javaProject.getElementName();
            try
            {
                List<JavaDocResponse.MemberJavadoc> members = new ArrayList<>();
                for ( IJavaElement child : type.getChildren() )
                {
                    if ( child instanceof IMember member && ( wanted == null || wanted.equals( member.getElementName() ) ) )
                    {
                        members.add( describe( member, javadoc ) );
                    }
                }
                if ( wanted != null && members.isEmpty() )
                {
                    return JavaDocResponse.memberNotFound( fullyQualifiedClassName, projectName,
                            Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_FOUND, "'" + fullyQualifiedClassName
                                    + "' declares no member named '" + wanted
                                    + "'. getClassOutline lists the members it does declare." ) );
                }

                Javadocs.Rendered typeJavadoc = Javadocs.render( type, javadoc, true );
                boolean documented = typeJavadoc != null || members.stream().anyMatch( member -> member.javadoc() != null );
                return JavaDocResponse.of(
                        documented ? JavaDocResponse.Status.OK : JavaDocResponse.Status.NO_JAVADOC,
                        fullyQualifiedClassName, projectName, typeJavadoc == null ? null : typeJavadoc.markdown(), members );
            }
            catch ( JavaModelException e )
            {
                throw new RuntimeException( "Could not read the members of " + fullyQualifiedClassName, e );
            }
        }

        return JavaDocResponse.notFound( fullyQualifiedClassName,
                Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_FOUND, "No open Java project resolves '"
                        + fullyQualifiedClassName + "'. A member type is named x.y.A.B, and a type name must"
                        + " match its compilation unit name to be found." ) );
    }

    private static JavaDocResponse.MemberJavadoc describe( IMember member, Javadocs.Detail javadoc ) throws JavaModelException
    {
        Javadocs.Rendered rendered = Javadocs.render( member, javadoc, true );
        return new JavaDocResponse.MemberJavadoc( member.getElementName(), label( member ),
                rendered == null ? null : rendered.markdown(), rendered != null && rendered.inherited() );
    }

    /** The declaration as getClassOutline prints it, so the two tools agree on how a member reads. */
    private static String label( IMember member ) throws JavaModelException
    {
        if ( member instanceof IMethod method )
        {
            return CodeAnalysisService.formatMethodSignature( method );
        }
        if ( member instanceof IField field )
        {
            return CodeAnalysisService.formatFieldDeclaration( field );
        }
        if ( member instanceof IType type )
        {
            return CodeAnalysisService.formatTypeDeclaration( type );
        }
        // An initializer block has no declaration to print.
        return member.getElementName();
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
     * How a Java type resolves on one project's classpath.
     * <p>
     * Mostly a report a person reads, with one part a program acts on: where the type's
     * source is. That used to be a workspace-absolute path
     * ({@code /Project/src/A.java}), which no reading or editing tool accepts, so an
     * agent that copied it got {@code RESOURCE_NOT_FOUND}. It is a projectName plus a
     * project-relative filePath here.
     * <p>
     * The {@code Kind} and {@code Source strategy} lines were two renderings of one
     * fact that already has a type - {@link SourceOrigin}, which every read reports -
     * so they are that enum rather than prose.
     *
     * @return the resolution, or a failed result naming which of the two lookups missed
     */
    public TypeResolutionResponse explainTypeResolution( String projectName, String fullyQualifiedClassName )
    {
        if ( projectName == null || projectName.isBlank() )
        {
            throw new IllegalArgumentException( "Project name cannot be empty." );
        }
        if ( fullyQualifiedClassName == null || fullyQualifiedClassName.isBlank() )
        {
            throw new IllegalArgumentException( "Fully qualified class name cannot be empty." );
        }

        Optional<IJavaProject> javaProject = getAvailableJavaProjects().stream()
                .filter( project -> projectName.equals( project.getElementName() ) )
                .findFirst();
        if ( javaProject.isEmpty() )
        {
            return TypeResolutionResponse.failed( fullyQualifiedClassName, projectName,
                    TypeResolutionResponse.Status.PROJECT_NOT_FOUND,
                    Diagnostic.fatal( DiagnosticCode.PROJECT_NOT_FOUND, "No open Java project named '" + projectName
                            + "'. Use listProjects to see the workspace." ) );
        }

        try
        {
            IType type = javaProject.get().findType( fullyQualifiedClassName );
            if ( type == null )
            {
                return TypeResolutionResponse.failed( fullyQualifiedClassName, projectName,
                        TypeResolutionResponse.Status.TYPE_NOT_RESOLVED,
                        Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_FOUND, "Type '" + fullyQualifiedClassName
                                + "' is not resolved on the classpath of project '" + projectName + "'." ) );
            }

            IPackageFragmentRoot root = (IPackageFragmentRoot) type.getAncestor( IJavaElement.PACKAGE_FRAGMENT_ROOT );
            IClasspathEntry entry = root == null ? null : root.getResolvedClasspathEntry();
            ICompilationUnit compilationUnit = type.getCompilationUnit();
            IClassFile classFile = type.getClassFile();
            IResource resource = getTypeResource( type );
            String attachedSource = classFile == null ? null : classFile.getSource();

            SourceOrigin origin;
            if ( compilationUnit != null )
            {
                origin = SourceOrigin.WORKSPACE_SOURCE;
            }
            else if ( attachedSource != null && !attachedSource.isBlank() )
            {
                origin = SourceOrigin.ATTACHED_SOURCE;
            }
            else
            {
                // A prediction rather than an observation: nothing has decompiled yet.
                origin = SourceOrigin.DECOMPILED_CLASS;
            }

            return new TypeResolutionResponse(
                    TypeResolutionResponse.Status.OK,
                    fullyQualifiedClassName,
                    type.getFullyQualifiedName( '.' ),
                    projectName,
                    origin,
                    resource == null || resource.getProject() == null ? null : resource.getProject().getName(),
                    resource == null ? null : resource.getProjectRelativePath().toString(),
                    rootKindOf( root ),
                    root == null ? null : root.getPath().toString(),
                    root == null || root.getSourceAttachmentPath() == null
                            ? null : root.getSourceAttachmentPath().toString(),
                    entry == null ? null : classpathEntryKind( entry.getEntryKind() ),
                    entry == null ? null : entry.getPath().toString(),
                    classFile == null ? null : classFile.getPath().toString(),
                    Diagnostic.none() );
        }
        catch ( JavaModelException e )
        {
            throw new RuntimeException( "Could not explain type resolution for " + fullyQualifiedClassName, e );
        }
    }

    private static TypeResolutionResponse.RootKind rootKindOf( IPackageFragmentRoot root )
    {
        if ( root == null )
        {
            return null;
        }
        if ( root.isArchive() )
        {
            return root.isExternal() ? TypeResolutionResponse.RootKind.EXTERNAL_ARCHIVE
                    : TypeResolutionResponse.RootKind.WORKSPACE_ARCHIVE;
        }
        return root.isExternal() ? TypeResolutionResponse.RootKind.EXTERNAL_FOLDER
                : TypeResolutionResponse.RootKind.WORKSPACE_FOLDER;
    }

    private static TypeResolutionResponse.ClasspathEntryKind classpathEntryKind( int kind )
    {
        return switch ( kind )
        {
            case IClasspathEntry.CPE_SOURCE -> TypeResolutionResponse.ClasspathEntryKind.SOURCE;
            case IClasspathEntry.CPE_PROJECT -> TypeResolutionResponse.ClasspathEntryKind.PROJECT;
            case IClasspathEntry.CPE_LIBRARY -> TypeResolutionResponse.ClasspathEntryKind.LIBRARY;
            case IClasspathEntry.CPE_VARIABLE -> TypeResolutionResponse.ClasspathEntryKind.VARIABLE;
            case IClasspathEntry.CPE_CONTAINER -> TypeResolutionResponse.ClasspathEntryKind.CONTAINER;
            default -> TypeResolutionResponse.ClasspathEntryKind.UNKNOWN;
        };
    }


    /**
     * Retrieves source for a workspace or referenced-library class. Original
     * source (including source attachments) is preferred; binary classes are
     * decompiled only when no source is attached.
     * <p>
     * The {@code origin} of the result is the point of returning a record here: all
     * three cases look like ordinary Java, but only workspace source can be written
     * back, and an agent that edited a decompiled class would be editing a rendering
     * of bytecode.
     *
     * @param fullyQualifiedClassName
     *            the fully qualified class name
     * @return the source with its origin and version, or a failed result carrying
     *         the reason as a code
     */
    public ResourceReadResult getSourceWithResource( String fullyQualifiedClassName )
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
                        return ResourceReadResult.failed( file.getProject().getName(),
                                file.getProjectRelativePath().toString(),
                                Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_ACCESSIBLE, "'"
                                        + fullyQualifiedClassName + "' is excluded from AI processing by .aiignore." ) );
                    }

                    String workspaceSource = readWorkspaceSource( file );
                    if ( workspaceSource != null && !workspaceSource.isBlank() )
                    {
                        return ResourceReadResult.of(
                                ResourceDescriptor.fromJavaType( type, toolName ), workspaceSource,
                                SourceOrigin.WORKSPACE_SOURCE, ResourceVersion.of( file ), Diagnostic.none() );
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
                    return ResourceReadResult.of(
                            ResourceDescriptor.fromJavaType( type, toolName ), attachedSource,
                            SourceOrigin.ATTACHED_SOURCE, ResourceVersion.UNKNOWN, Diagnostic.none() );
                }

                Optional<String> decompiledSource = classFileDecompiler.decompile( classFile );
                if ( decompiledSource.isPresent() )
                {
                    return ResourceReadResult.of(
                            ResourceDescriptor.fromJavaType( type, toolName ), decompiledSource.get(),
                            SourceOrigin.DECOMPILED_CLASS, ResourceVersion.UNKNOWN, Diagnostic.none() );
                }
            }
            catch ( Exception e )
            {
                logger.error( "Could not retrieve source for " + fullyQualifiedClassName, e );
            }
        }

        return ResourceReadResult.failed( null, null, Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_FOUND,
                "No source available for '" + fullyQualifiedClassName
                        + "'. The type resolved on no open project's classpath, or has neither attached"
                        + " source nor decompilable bytecode." ) );
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
