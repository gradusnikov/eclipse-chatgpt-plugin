
package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.github.gradusnikov.eclipse.assistai.mcp.operations.Operation;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.OperationContext;
import com.github.gradusnikov.eclipse.assistai.mcp.results.CallHierarchyResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.ClassOutlineResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.CompilationProblemsResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.Diagnostic;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.ImportSuggestionsResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.QuickFixResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.ReferencesResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TypeHierarchyResponse;
import com.github.gradusnikov.eclipse.assistai.tools.Javadocs;
import com.github.gradusnikov.eclipse.assistai.tools.LineOffsets;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaModelMarker;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeParameter;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
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
    
    private final java.util.concurrent.ConcurrentHashMap<org.eclipse.core.runtime.IPath, Object> fileLocks = new java.util.concurrent.ConcurrentHashMap<>();

    @Inject
    ILog logger;

    @Inject
    AiIgnoreService aiIgnoreService;
    
    /**
     * Who calls a method, and what it calls.
     * <p>
     * {@code MethodWrapper} holds the {@code IMethod}, so the project, the
     * project-relative path and the line are all in hand; they used to be discarded for
     * {@code cu.getElementName()}, a bare file name that no reading or editing tool
     * accepts, which made every use of this tool cost a follow-up
     * {@code findReferences} to learn where. A node reports the same location triple
     * that tool does.
     *
     * @param methodSignature parameter type signatures, comma separated - optional, and
     *            needed only to pick between overloads
     * @param maxDepth how far to walk the callers; 3 when omitted
     */
    @SuppressWarnings("restriction")
	public CallHierarchyResponse getMethodCallHierarchy(String fullyQualifiedClassName,
                                  String methodName, 
                                  String methodSignature, 
                                  Integer maxDepth)
    {
        int depthLimit = (maxDepth == null || maxDepth < 1) ? 3 : maxDepth;
        String target = fullyQualifiedClassName + "." + methodName;

        try 
        {
            IType type = findType(fullyQualifiedClassName);
            if (type == null)
            {
                return CallHierarchyResponse.failed(target, methodName, fullyQualifiedClassName, depthLimit,
                        CallHierarchyResponse.Status.TYPE_NOT_FOUND,
                        Diagnostic.fatal(DiagnosticCode.RESOURCE_NOT_FOUND, "Type '" + fullyQualifiedClassName
                                + "' is not on the build path of any open Java project."));
            }

            IMethod targetMethod = findMethod(type, methodName, methodSignature);
            if (targetMethod == null)
            {
                return CallHierarchyResponse.failed(target, methodName, fullyQualifiedClassName, depthLimit,
                        CallHierarchyResponse.Status.METHOD_NOT_FOUND,
                        Diagnostic.fatal(DiagnosticCode.RESOURCE_NOT_FOUND, "Type '" + fullyQualifiedClassName
                                + "' declares no method '" + methodName + "'"
                                + (methodSignature == null || methodSignature.isBlank()
                                        ? "." : " with signature '" + methodSignature + "'.")));
            }

            List<Diagnostic> diagnostics = new ArrayList<>();
            // One read per file rather than one per node: a wide hierarchy revisits the
            // same compilation unit many times to resolve line numbers.
            Map<IFile, String> sources = new LinkedHashMap<>();

            CallHierarchy callHierarchy = CallHierarchy.getDefault();

            List<CallHierarchyResponse.CallNode> callers = new ArrayList<>();
            collectCalls(callHierarchy.getCallerRoots(new IMethod[] { targetMethod }), 1, depthLimit,
                    callers, diagnostics, sources);

            // Callees stay one level deep: the question this tool answers is "who calls
            // this", and a full callee walk is a different and much larger search.
            List<CallHierarchyResponse.CallNode> callees = new ArrayList<>();
            collectCalls(callHierarchy.getCalleeRoots(new IMethod[] { targetMethod }), 1, 1,
                    callees, diagnostics, sources);

            return CallHierarchyResponse.of(target, methodName, fullyQualifiedClassName, depthLimit,
                    callers, callees, diagnostics);
        }
        catch (JavaModelException e) 
        {
            logger.error(e.getMessage(), e);
            return CallHierarchyResponse.failed(target, methodName, fullyQualifiedClassName, depthLimit,
                    CallHierarchyResponse.Status.FAILED,
                    Diagnostic.fatal(DiagnosticCode.INTERNAL_ERROR,
                            "Error retrieving call hierarchy: " + ExceptionUtils.getRootCauseMessage(e)));
        }
    }

    /**
     * Resolves a method on a type, optionally narrowed by a comma-separated list of
     * parameter type signatures.
     */
    private static IMethod findMethod(IType type, String methodName, String methodSignature)
            throws JavaModelException
    {
        if (methodSignature != null && !methodSignature.isBlank())
        {
            IMethod method = type.getMethod(methodName, methodSignature.split(","));
            return (method != null && method.exists()) ? method : null;
        }
        for (IMethod method : type.getMethods())
        {
            if (method.getElementName().equals(methodName))
            {
                return method;
            }
        }
        return null;
    }
    
    /** Collects compilation problems from the workspace or a single project. */
    public CompilationProblemsResponse getCompilationErrors( String projectName, String severity, Integer maxResults )
    {
        return getCompilationErrors( projectName, null, severity, maxResults );
    }

    /**
     * Collects compilation problems from the workspace, a project, or one folder or file
     * within a project.
     * <p>
     * Collection only - the rendering is the record's JSON serialization, so how
     * problems are described can change without changing which problems are reported.
     *
     * @param resourcePath project-relative path of a folder or file to restrict the report
     *            to; requires {@code projectName}
     * @param severity {@code ERROR}, {@code WARNING}, {@code INFO} or {@code ALL} (default)
     * @param maxResults how many problems to list; the counts are of everything that
     *            matched, so a truncated reply still answers "are there errors?"
     */
    public CompilationProblemsResponse getCompilationErrors( String projectName, String resourcePath, String severity, Integer maxResults )
    {
        String requestedSeverity = ( severity == null || severity.isBlank() ) ? "ALL" : severity.toUpperCase();
        int limit = ( maxResults == null || maxResults < 1 ) ? 50 : maxResults;

        int severityFilter = switch ( requestedSeverity )
        {
            case "ERROR" -> IMarker.SEVERITY_ERROR;
            case "WARNING" -> IMarker.SEVERITY_WARNING;
            case "INFO" -> IMarker.SEVERITY_INFO;
            default -> -1;
        };

        try
        {
            String scope;
            IMarker[] markers;
            if ( projectName != null && !projectName.isBlank() )
            {
                IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject( projectName );
                if ( project == null || !project.exists() )
                {
                    throw new RuntimeException( "Project '" + projectName + "' not found." );
                }
                if ( !project.isOpen() )
                {
                    throw new RuntimeException( "Project '" + projectName + "' is closed." );
                }
                if ( resourcePath != null && !resourcePath.isBlank() )
                {
                    IResource resource = project.findMember( resourcePath );
                    if ( resource == null )
                    {
                        throw new RuntimeException( "Resource '" + resourcePath + "' not found in project '" + projectName + "'." );
                    }
                    scope = ( resource instanceof IFile ? "File: " : "Folder: " ) + projectName + "/" + resource.getProjectRelativePath();
                    markers = resource.findMarkers( IMarker.PROBLEM, true, IResource.DEPTH_INFINITE );
                }
                else
                {
                    scope = "Project: " + projectName;
                    markers = project.findMarkers( IMarker.PROBLEM, true, IResource.DEPTH_INFINITE );
                }
            }
            else if ( resourcePath != null && !resourcePath.isBlank() )
            {
                throw new RuntimeException( "resourcePath requires projectName." );
            }
            else
            {
                scope = "Scope: All Projects";
                markers = ResourcesPlugin.getWorkspace().getRoot().findMarkers( IMarker.PROBLEM, true,
                        IResource.DEPTH_INFINITE );
            }

            List<IMarker> matched = new ArrayList<>();
            int errors = 0;
            int warnings = 0;
            int infos = 0;

            for ( IMarker marker : markers )
            {
                Integer value = (Integer) marker.getAttribute( IMarker.SEVERITY );
                if ( severityFilter != -1 && ( value == null || value.intValue() != severityFilter ) )
                {
                    continue;
                }
                matched.add( marker );
                switch ( value == null ? -1 : value.intValue() )
                {
                    case IMarker.SEVERITY_ERROR -> errors++;
                    case IMarker.SEVERITY_WARNING -> warnings++;
                    case IMarker.SEVERITY_INFO -> infos++;
                    default -> { /* unknown severity is counted only in the total */ }
                }
            }

            // Errors first: a caller reading a truncated list should see what blocks a
            // build before it sees style warnings.
            matched.sort( ( first, second ) -> Integer.compare(
                    severityOf( second ), severityOf( first ) ) );

            int total = matched.size();
            boolean truncated = total > limit;
            List<IMarker> listed = truncated ? matched.subList( 0, limit ) : matched;

            // Linked, so files keep the errors-first order the sort established.
            Map<IResource, List<CompilationProblemsResponse.Problem>> byResource = new LinkedHashMap<>();
            for ( IMarker marker : listed )
            {
                byResource.computeIfAbsent( marker.getResource(), ignored -> new ArrayList<>() )
                          .add( toProblem( marker ) );
            }

            List<CompilationProblemsResponse.FileProblems> files = new ArrayList<>();
            for ( Map.Entry<IResource, List<CompilationProblemsResponse.Problem>> entry : byResource.entrySet() )
            {
                IResource resource = entry.getKey();
                files.add( new CompilationProblemsResponse.FileProblems(
                        resource.getProject() == null ? null : resource.getProject().getName(),
                        resource.getProjectRelativePath().toString(),
                        entry.getValue() ) );
            }

            String summary = truncated
                    ? "Showing " + limit + " of " + total + " problems found."
                    : "Found " + total + " problems.";

            return new CompilationProblemsResponse( scope, total, errors, warnings, infos, files, truncated,
                    summary );
        }
        catch ( CoreException e )
        {
            logger.error( e.getMessage(), e );
            throw new RuntimeException( "Error retrieving compilation problems: " + ExceptionUtils.getStackTrace( e ) );
        }
    }

    private static int severityOf( IMarker marker )
    {
        try
        {
            Integer value = (Integer) marker.getAttribute( IMarker.SEVERITY );
            return value == null ? -1 : value.intValue();
        }
        catch ( CoreException e )
        {
            return -1;
        }
    }

    /** Converts one marker, resolving its context snippet and quick fixes. */
    private CompilationProblemsResponse.Problem toProblem( IMarker marker ) throws CoreException
    {
        Integer severityValue = (Integer) marker.getAttribute( IMarker.SEVERITY );
        CompilationProblemsResponse.Severity severity = switch ( severityValue == null ? -1 : severityValue.intValue() )
        {
            case IMarker.SEVERITY_ERROR -> CompilationProblemsResponse.Severity.ERROR;
            case IMarker.SEVERITY_WARNING -> CompilationProblemsResponse.Severity.WARNING;
            case IMarker.SEVERITY_INFO -> CompilationProblemsResponse.Severity.INFO;
            default -> CompilationProblemsResponse.Severity.UNKNOWN;
        };

        Integer lineNumber = (Integer) marker.getAttribute( IMarker.LINE_NUMBER );
        String message = (String) marker.getAttribute( IMarker.MESSAGE );

        Integer problemId = null;
        if ( IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER.equals( marker.getType() ) )
        {
            Object sourceId = marker.getAttribute( IJavaModelMarker.ID );
            if ( sourceId instanceof Integer id )
            {
                problemId = id;
            }
        }

        String snippet = null;
        String language = null;
        if ( lineNumber != null && marker.getResource() instanceof IFile file )
        {
            snippet = readContextSnippet( file, lineNumber );
            language = file.getFileExtension();
        }

        List<CompilationProblemsResponse.QuickFixOption> fixes = new ArrayList<>();
        try
        {
            fixes = toQuickFixOptions( collectQuickFixes( marker ) );
        }
        catch ( Exception e )
        {
            // Quick fix collection is best-effort: a problem is still worth reporting
            // even when the IDE cannot suggest a repair for it.
        }

        return new CompilationProblemsResponse.Problem(
                severity,
                lineNumber == null ? -1 : lineNumber.intValue(),
                message == null ? "No message provided" : message,
                marker.getId(),
                problemId,
                snippet,
                language,
                fixes );
    }

    /** The offending line and its neighbours, the offending one marked with "> ". */
    private String readContextSnippet( IFile file, int lineNumber )
    {
        try
        {
            String[] lines = readFileContent( file ).split( "\n" );
            if ( lineNumber < 1 || lineNumber > lines.length )
            {
                return null;
            }
            int start = Math.max( 1, lineNumber - 1 );
            int end = Math.min( lines.length, lineNumber + 1 );

            StringBuilder snippet = new StringBuilder();
            for ( int i = start - 1; i < end; i++ )
            {
                snippet.append( i == lineNumber - 1 ? "> " : "  " ).append( lines[i] ).append( "\n" );
            }
            return snippet.toString();
        }
        catch ( Exception e )
        {
            // A problem whose file cannot be read is still a problem worth reporting.
            return null;
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
    
    /**
     * Walks a wrapper's calls in pre-order, appending one node per method.
     * <p>
     * The roots JDT returns wrap the target method itself, so the walk starts from
     * their calls: depth 1 is a direct caller or callee, which is what the parameter
     * documentation has always promised.
     * <p>
     * Flat with a {@code depth} field rather than nested children - the schema
     * generator stops at a self-referencing record, so nested levels would be
     * advertised as objects of unspecified shape.
     */
    @SuppressWarnings("restriction")
	private void collectCalls(MethodWrapper[] parents, int depth, int maxDepth,
	        List<CallHierarchyResponse.CallNode> out, List<Diagnostic> diagnostics, Map<IFile, String> sources)
    {
        if (parents == null || depth > maxDepth)
        {
            return;
        }

        for (MethodWrapper parent : parents)
        {
            MethodWrapper[] calls;
            try
            {
                calls = parent.getCalls(new NullProgressMonitor());
            }
            catch (Exception e)
            {
                logger.error(e.getMessage(), e);
                // A level that could not be expanded is reported as a diagnostic rather
                // than as "[Error retrieving method details]" spliced into the tree,
                // where it read as part of the previous node.
                diagnostics.add(Diagnostic.fatal(DiagnosticCode.INTERNAL_ERROR,
                        "Could not expand the calls of " + parent.getName() + ": " + e.getMessage()));
                continue;
            }
            if (calls == null)
            {
                continue;
            }

            for (MethodWrapper call : calls)
            {
                if (!(call.getMember() instanceof IMethod method))
                {
                    continue;
                }
                out.add(toCallNode(method, depth, diagnostics, sources));
                collectCalls(new MethodWrapper[] { call }, depth + 1, maxDepth, out, diagnostics, sources);
            }
        }
    }

    /** One method in a call hierarchy, with the location triple the reading tools take. */
    private CallHierarchyResponse.CallNode toCallNode(IMethod method, int depth, List<Diagnostic> diagnostics,
            Map<IFile, String> sources)
    {
        String declaringType = null;
        String signature = null;
        String projectName = null;
        String filePath = null;
        int lineNumber = -1;

        try
        {
            IType type = method.getDeclaringType();
            declaringType = type == null ? null : type.getFullyQualifiedName();
            signature = formatMethodParameters(method);

            if (method.getResource() instanceof IFile file)
            {
                projectName = file.getProject().getName();
                filePath = file.getProjectRelativePath().toString();

                ISourceRange nameRange = method.getNameRange();
                if (nameRange != null && nameRange.getOffset() >= 0)
                {
                    String source = sources.computeIfAbsent(file, this::readFileQuietly);
                    if (source != null)
                    {
                        lineNumber = LineOffsets.lineInfoAt(source, nameRange.getOffset()).lineNumber();
                    }
                }
            }
        }
        catch (Exception e)
        {
            logger.error(e.getMessage(), e);
            diagnostics.add(Diagnostic.fatal(DiagnosticCode.INTERNAL_ERROR,
                    "Could not resolve details for " + method.getElementName() + ": " + e.getMessage()));
        }

        return new CallHierarchyResponse.CallNode(depth, method.getElementName(), declaringType, signature,
                projectName, filePath, lineNumber);
    }

    /** File content for line resolution; a file that cannot be read costs a line number, not the node. */
    private String readFileQuietly(IFile file)
    {
        try
        {
            return readFileContent(file);
        }
        catch (Exception e)
        {
            return null;
        }
    }
    
    /**
     * Resolves a type name against the open Java projects.
     *
     * @return the first matching type, or null when no project knows the name
     */
    private IType findType( String fullyQualifiedClassName ) throws JavaModelException
    {
        for ( IJavaProject project : getAvailableJavaProjects() )
        {
            IType type = project.findType( fullyQualifiedClassName );
            if ( type != null )
            {
                return type;
            }
        }
        return null;
    }

    /**
     * Retrieves the superclasses, implemented interfaces and subtypes of a type.
     * <p>
     * The three relations are kept apart rather than folded into one indented listing,
     * and every type that is workspace source also reports where its file is, because
     * the caller's next act is to open one of them.
     *
     * @param fullyQualifiedClassName The fully qualified name of the class
     * @param javadoc how much of each type's documentation to carry; NONE keeps the
     *            answer structural
     */
    public TypeHierarchyResponse getTypeHierarchy( String fullyQualifiedClassName, Javadocs.Detail javadoc )
    {
        try
        {
            IType targetType = findType( fullyQualifiedClassName );
            if ( targetType == null )
            {
                return TypeHierarchyResponse.notFound( fullyQualifiedClassName );
            }

            var hierarchy = targetType.newTypeHierarchy( new NullProgressMonitor() );

            return TypeHierarchyResponse.of( fullyQualifiedClassName,
                    toHierarchyTypes( hierarchy.getAllSuperclasses( targetType ), javadoc ),
                    toHierarchyTypes( hierarchy.getAllSuperInterfaces( targetType ), javadoc ),
                    toHierarchyTypes( hierarchy.getAllSubtypes( targetType ), javadoc ) );
        }
        catch ( Exception e )
        {
            logger.error( e.getMessage(), e );
            throw new RuntimeException( "Error retrieving type hierarchy: " + e.getMessage(), e );
        }
    }

    private static List<TypeHierarchyResponse.HierarchyType> toHierarchyTypes( IType[] types, Javadocs.Detail javadoc )
    {
        List<TypeHierarchyResponse.HierarchyType> hierarchyTypes = new ArrayList<>();
        for ( IType type : types )
        {
            hierarchyTypes.add( toHierarchyType( type, javadoc ) );
        }
        return hierarchyTypes;
    }

    /**
     * A type from a JAR or the JRE has no compilation unit, so it reports no location -
     * which is how a caller tells "I can open and edit this" from "I cannot".
     * <p>
     * Its documentation comes from source only, attached or in the workspace: a hierarchy
     * can reach dozens of library types, and a project's Javadoc location may be a URL.
     */
    private static TypeHierarchyResponse.HierarchyType toHierarchyType( IType type, Javadocs.Detail javadoc )
    {
        Javadocs.Rendered rendered = Javadocs.render( type, javadoc, false );
        String documentation = rendered == null ? null : rendered.markdown();
        ICompilationUnit unit = type.getCompilationUnit();
        if ( unit != null && unit.getResource() instanceof IFile file )
        {
            return new TypeHierarchyResponse.HierarchyType( type.getFullyQualifiedName(),
                    file.getProject().getName(), file.getProjectRelativePath().toString(), documentation );
        }
        return new TypeHierarchyResponse.HierarchyType( type.getFullyQualifiedName(), null, null, documentation );
    }

    /**
     * Returns the outline of a type: its declaration plus fields, method signatures and
     * inner types, each with the line range needed to read it.
     * <p>
     * The line range is the point of the call. A caller reads an outline to choose one
     * member and then fetch it, so an entry that named a member without saying where it
     * ends left that caller guessing its extent from the start of the next one.
     *
     * @param fullyQualifiedClassName the type to outline
     * @param includeFields whether field declarations are listed
     * @param javadoc how much of each member's documentation to carry
     */
    public ClassOutlineResponse getClassOutline( String fullyQualifiedClassName, boolean includeFields,
            Javadocs.Detail javadoc )
    {
        try
        {
            IType type = findType( fullyQualifiedClassName );
            if ( type == null )
            {
                return ClassOutlineResponse.failed( fullyQualifiedClassName, ClassOutlineResponse.Status.TYPE_NOT_FOUND,
                        "Type '" + fullyQualifiedClassName + "' was not found in any open Java project." );
            }

            ICompilationUnit unit = type.getCompilationUnit();
            if ( unit == null )
            {
                return ClassOutlineResponse.failed( fullyQualifiedClassName, ClassOutlineResponse.Status.NO_SOURCE,
                        "Type '" + fullyQualifiedClassName + "' has no attached source. Use getSource, which decompiles." );
            }

            IResource resource = unit.getResource();
            if ( resource != null && aiIgnoreService.isExcluded( resource ) )
            {
                return ClassOutlineResponse.failed( fullyQualifiedClassName, ClassOutlineResponse.Status.ACCESS_DENIED,
                        "Type '" + fullyQualifiedClassName + "' is excluded from AI processing by .aiignore." );
            }

            // The platform's line tracker, so a CRLF file reports the same lines as an LF one.
            IDocument document = new Document( unit.getBuffer().getContents() );

            List<ClassOutlineResponse.Member> fields = new ArrayList<>();
            if ( includeFields )
            {
                for ( IField field : type.getFields() )
                {
                    fields.add( toMember( document, field, formatFieldDeclaration( field ), javadoc ) );
                }
            }

            List<ClassOutlineResponse.Member> methods = new ArrayList<>();
            for ( IMethod method : type.getMethods() )
            {
                methods.add( toMember( document, method, formatMethodSignature( method ), javadoc ) );
            }

            List<ClassOutlineResponse.Member> innerTypes = new ArrayList<>();
            for ( IType innerType : type.getTypes() )
            {
                innerTypes.add( toMember( document, innerType, formatTypeDeclaration( innerType ), javadoc ) );
            }

            ClassOutlineResponse.Member declaration = toMember( document, type, formatTypeDeclaration( type ), javadoc );

            return ClassOutlineResponse.of( fullyQualifiedClassName,
                    resource == null ? null : resource.getProject().getName(),
                    resource == null ? null : resource.getProjectRelativePath().toString(),
                    declaration, fields, methods, innerTypes );
        }
        catch ( Exception e )
        {
            logger.error( e.getMessage(), e );
            throw new RuntimeException( "Error building the outline of '" + fullyQualifiedClassName + "': "
                    + e.getMessage(), e );
        }
    }

    /** Both line numbers are 1-based and inclusive, as the reading tools take them. */
    /** An outline is workspace source, so the attached-Javadoc lookup never applies. */
    private static ClassOutlineResponse.Member toMember( IDocument document, IMember member, String label,
            Javadocs.Detail javadoc ) throws JavaModelException, BadLocationException
    {
        ISourceRange range = member.getSourceRange();
        int startLine = document.getLineOfOffset( range.getOffset() ) + 1;
        int endLine = document.getLineOfOffset( range.getOffset() + Math.max( range.getLength() - 1, 0 ) ) + 1;
        Javadocs.Rendered rendered = Javadocs.render( member, javadoc, false );
        return new ClassOutlineResponse.Member( member.getElementName(), label, startLine, endLine,
                rendered == null ? null : rendered.markdown(), rendered != null && rendered.inherited() );
    }

    static String formatTypeDeclaration( IType type ) throws JavaModelException
    {
        StringBuilder declaration = new StringBuilder();
        appendAnnotations( declaration, type.getAnnotations() );

        int flags = type.getFlags();
        if ( type.isInterface() )
        {
            // JDT reports every interface as abstract; printing it back adds nothing.
            flags &= ~Flags.AccAbstract;
        }
        appendModifiers( declaration, flags );

        if ( type.isAnnotation() )
        {
            declaration.append( "@interface " );
        }
        else if ( type.isInterface() )
        {
            declaration.append( "interface " );
        }
        else if ( type.isEnum() )
        {
            declaration.append( "enum " );
        }
        else if ( type.isRecord() )
        {
            declaration.append( "record " );
        }
        else
        {
            declaration.append( "class " );
        }
        declaration.append( type.getElementName() );

        ITypeParameter[] typeParameters = type.getTypeParameters();
        if ( typeParameters.length > 0 )
        {
            declaration.append( "<" );
            for ( int i = 0; i < typeParameters.length; i++ )
            {
                if ( i > 0 )
                {
                    declaration.append( ", " );
                }
                declaration.append( typeParameters[i].getElementName() );
                String[] bounds = typeParameters[i].getBounds();
                if ( bounds.length > 0 )
                {
                    declaration.append( " extends " ).append( String.join( " & ", bounds ) );
                }
            }
            declaration.append( ">" );
        }

        String superclass = type.getSuperclassName();
        if ( superclass != null && !"Object".equals( superclass ) )
        {
            declaration.append( " extends " ).append( superclass );
        }

        String[] interfaces = type.getSuperInterfaceNames();
        if ( interfaces.length > 0 )
        {
            declaration.append( type.isInterface() ? " extends " : " implements " );
            declaration.append( String.join( ", ", interfaces ) );
        }

        return declaration.toString();
    }

    static String formatFieldDeclaration( IField field ) throws JavaModelException
    {
        if ( field.isEnumConstant() )
        {
            return field.getElementName();
        }

        StringBuilder declaration = new StringBuilder();
        appendAnnotations( declaration, field.getAnnotations() );
        appendModifiers( declaration, field.getFlags() );

        declaration.append( Signature.toString( field.getTypeSignature() ) );
        declaration.append( " " ).append( field.getElementName() );

        Object constant = field.getConstant();
        if ( constant instanceof String text )
        {
            declaration.append( " = \"" ).append( text ).append( "\"" );
        }
        else if ( constant != null )
        {
            declaration.append( " = " ).append( constant );
        }

        return declaration.toString();
    }

    static String formatMethodSignature( IMethod method ) throws JavaModelException
    {
        StringBuilder signature = new StringBuilder();
        appendAnnotations( signature, method.getAnnotations() );
        appendModifiers( signature, method.getFlags() );

        if ( !method.isConstructor() )
        {
            signature.append( Signature.toString( method.getReturnType() ) ).append( " " );
        }

        signature.append( method.getElementName() );
        signature.append( "(" ).append( formatMethodParameters( method ) ).append( ")" );

        String[] exceptions = method.getExceptionTypes();
        if ( exceptions.length > 0 )
        {
            signature.append( " throws " );
            for ( int i = 0; i < exceptions.length; i++ )
            {
                if ( i > 0 )
                {
                    signature.append( ", " );
                }
                signature.append( Signature.toString( exceptions[i] ) );
            }
        }

        return signature.toString();
    }

    /**
     * A method's parameter list as it reads in source - {@code String name, int count} -
     * without the enclosing parentheses.
     * <p>
     * Package-visible because {@link OutlineService} matches a caller's
     * {@code methodSignature} filter against exactly this rendering. It used to keep its
     * own copy, so an overload picked by {@code getMethodSource} and the same overload
     * listed by {@code getClassOutline} were formatted by two separate pieces of code
     * that only happened to agree.
     */
    static String formatMethodParameters( IMethod method ) throws JavaModelException
    {
        StringBuilder parameters = new StringBuilder();

        String[] parameterTypes = method.getParameterTypes();
        String[] parameterNames = method.getParameterNames();
        for ( int i = 0; i < parameterTypes.length; i++ )
        {
            if ( i > 0 )
            {
                parameters.append( ", " );
            }
            parameters.append( Signature.toString( parameterTypes[i] ) );
            if ( i < parameterNames.length )
            {
                parameters.append( " " ).append( parameterNames[i] );
            }
        }

        return parameters.toString();
    }

    private static void appendAnnotations( StringBuilder target, IAnnotation[] annotations )
    {
        for ( IAnnotation annotation : annotations )
        {
            target.append( "@" ).append( annotation.getElementName() ).append( " " );
        }
    }

    private static void appendModifiers( StringBuilder target, int flags )
    {
        String modifiers = Flags.toString( flags );
        if ( !modifiers.isEmpty() )
        {
            target.append( modifiers ).append( " " );
        }
    }


    /**
     * Finds all references to a Java element (type, method, or field) across the workspace.
     *
     * @param fullyQualifiedClassName The fully qualified name of the class
     * @param elementName Optional method or field name within the class (null to search for the class itself)
     * @return A formatted string listing all references
     */
    public ReferencesResponse findReferences(String fullyQualifiedClassName, String elementName)
    {
        String label = ( elementName != null && !elementName.isBlank() )
                ? fullyQualifiedClassName + "." + elementName
                : fullyQualifiedClassName;
        try
        {
            IType targetType = findType(fullyQualifiedClassName);
            if (targetType == null)
            {
                throw new RuntimeException("Type '" + fullyQualifiedClassName + "' not found.");
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
                    throw new RuntimeException("Element '" + elementName + "' not found in '"
                            + fullyQualifiedClassName + "'.");
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

            var references = new ArrayList<ReferencesResponse.Reference>();
            var requestor = new org.eclipse.jdt.core.search.SearchRequestor()
            {
                @Override
                public void acceptSearchMatch(org.eclipse.jdt.core.search.SearchMatch match)
                {
                    if (!(match.getElement() instanceof IJavaElement javaElement))
                    {
                        return;
                    }
                    var resource = match.getResource();

                    int line = -1;
                    String lineContent = null;
                    if (resource instanceof IFile file)
                    {
                        try
                        {
                            // The platform's line tracker, so a CRLF file reports the
                            // same line as an LF one.
                            var info = LineOffsets.lineInfoAt(readFileContent(file), match.getOffset());
                            line = info.lineNumber();
                            lineContent = info.lineContent();
                        }
                        catch (Exception e)
                        {
                            // A reference whose file cannot be read is still a reference.
                        }
                    }

                    references.add(new ReferencesResponse.Reference(
                            resource == null || resource.getProject() == null ? null : resource.getProject().getName(),
                            resource == null ? null : resource.getProjectRelativePath().toString(),
                            line,
                            javaElement.getElementName(),
                            lineContent));
                }
            };

            searchEngine.search(pattern, 
                    new org.eclipse.jdt.core.search.SearchParticipant[] { org.eclipse.jdt.core.search.SearchEngine.getDefaultSearchParticipant() },
                    scope, requestor, new NullProgressMonitor());

            return ReferencesResponse.of(label, references, false);
        }
        catch (RuntimeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            logger.error(e.getMessage(), e);
            throw new RuntimeException("Error finding references: " + e.getMessage(), e);
        }
    }


    /**
     * Unified quick fix descriptor - wraps either a JDT IJavaCompletionProposal
     * or a platform IMarkerResolution so both can be presented and applied uniformly.
     */
    private record QuickFix(String label, String description, java.util.function.Consumer<IMarker> applyFn)
    {
        void apply(IMarker marker) { applyFn.accept(marker); }
    }

    /**
     * Applies one quick fix proposal to one problem marker.
     * <p>
     * The failures are the point. {@code getCompilationErrors} already hands back a
     * typed {@code markerId} and quick-fix indices so that fix-and-recheck is
     * mechanical, and this used to end the loop in six English sentences sharing an
     * {@code "Error"} prefix - one of which,
     * {@code "Error applying quick fix: …"}, reads as success to anything skimming for
     * the word "applied". Each recoverable condition is now a
     * {@link QuickFixResponse.Status}, because each needs a different next move.
     *
     * @param markerId the id reported by {@code getCompilationErrors}
     * @param proposalIndex the 0-based index of a {@code quickFixes} entry
     */
    public QuickFixResponse executeQuickFix(long markerId, int proposalIndex)
    {
        IMarker marker = findMarkerById(markerId);
        if (marker == null)
        {
            return QuickFixResponse.markerNotFound(markerId, proposalIndex);
        }

        Object lock = (marker.getResource() instanceof IFile file)
            ? fileLocks.computeIfAbsent(file.getFullPath(), k -> new Object())
            : new Object();

        synchronized (lock)
        {
            marker = findMarkerById(markerId);
            if (marker == null)
            {
                return QuickFixResponse.markerNotFound(markerId, proposalIndex);
            }

            IResource resource = marker.getResource();
            String projectName = (resource == null || resource.getProject() == null)
                    ? null : resource.getProject().getName();
            String filePath = resource == null ? null : resource.getProjectRelativePath().toString();

            List<QuickFix> fixes = collectQuickFixes(marker);
            List<CompilationProblemsResponse.QuickFixOption> available = toQuickFixOptions(fixes);

            if (fixes.isEmpty())
            {
                return QuickFixResponse.noProposals(markerId, projectName, filePath, proposalIndex);
            }
            if (proposalIndex < 0 || proposalIndex >= fixes.size())
            {
                return QuickFixResponse.invalidProposalIndex(markerId, projectName, filePath, proposalIndex,
                        available);
            }

            QuickFix fix = fixes.get(proposalIndex);
            try
            {
                fix.apply(marker);

                if (resource instanceof IFile file)
                {
                    saveFileBuffer(file);
                    file.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
                }

                waitForAutoBuild();
            }
            catch (Exception e)
            {
                logger.error(e.getMessage(), e);
                return QuickFixResponse.applyFailed(markerId, projectName, filePath, proposalIndex, fix.label(),
                        available, Diagnostic.fatal(DiagnosticCode.INTERNAL_ERROR,
                                "Applying '" + fix.label() + "' failed: " + e.getMessage()
                                        + ". Re-read the file; part of the change may have been written."));
            }

            // Whether the problem actually went away, as a field. It was the
            // parenthetical in "applied (marker still present)".
            return QuickFixResponse.applied(markerId, projectName, filePath, proposalIndex, fix.label(),
                    !marker.exists(), available);
        }
    }

    /** The proposals as the caller already sees them on a {@code getCompilationErrors} problem. */
    private static List<CompilationProblemsResponse.QuickFixOption> toQuickFixOptions(List<QuickFix> fixes)
    {
        List<CompilationProblemsResponse.QuickFixOption> options = new ArrayList<>();
        for (int i = 0; i < fixes.size(); i++)
        {
            options.add(new CompilationProblemsResponse.QuickFixOption(
                    i, fixes.get(i).label(), fixes.get(i).description()));
        }
        return options;
    }

    /**
     * Collects quick fix proposals for any problem marker.
     *
     * Java markers: calls JavaCorrectionProcessor directly. This produces ICUCorrectionProposal
     * instances that apply and save headlessly without needing an open editor.
     * Skips IMarkerHelpRegistry for Java markers because CorrectionMarkerResolution.run()
     * calls JavaUI.openInEditor() and silently does nothing when no editor is open.
     *
     * Non-Java markers (PDE, m2e, build-path, etc.): uses IMarkerHelpRegistry and run().
     */
    @SuppressWarnings("restriction")
	private List<QuickFix> collectQuickFixes(IMarker marker)
    {
        List<QuickFix> fixes = new ArrayList<>();
        java.util.Set<String> seenLabels = new java.util.HashSet<>();

        try
        {
            if (marker.getType().equals(IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER)
                    && marker.getResource() instanceof IFile file)
            {
                // Java marker: collect proposals directly from JavaCorrectionProcessor
                ICompilationUnit cu = (ICompilationUnit) JavaCore.create(file);
                if (cu != null && cu.exists())
                {
                    int id      = marker.getAttribute(IJavaModelMarker.ID, -1);
                    int start   = marker.getAttribute(IMarker.CHAR_START, -1);
                    int end     = marker.getAttribute(IMarker.CHAR_END, -1);
                    boolean isError = marker.getAttribute(IMarker.SEVERITY, 0) == IMarker.SEVERITY_ERROR;
                    String[] args = readMarkerArguments(marker);

                    if (start >= 0 && end >= 0)
                    {
                        org.eclipse.jdt.internal.ui.text.correction.ProblemLocation location =
                            new org.eclipse.jdt.internal.ui.text.correction.ProblemLocation(
                                start, end - start, id, args, isError, marker.getType());

                        org.eclipse.jdt.internal.ui.text.correction.AssistContext context =
                            new org.eclipse.jdt.internal.ui.text.correction.AssistContext(cu, start, end - start);

                        List<IJavaCompletionProposal> proposals = new ArrayList<>();
                        org.eclipse.jdt.internal.ui.text.correction.JavaCorrectionProcessor.collectCorrections(
                            context, new org.eclipse.jdt.ui.text.java.IProblemLocation[]{ location }, proposals);

                        proposals.sort((a, b) -> Integer.compare(b.getRelevance(), a.getRelevance()));

                        for (IJavaCompletionProposal p : proposals)
                        {
                            String label = p.getDisplayString();
                            if (seenLabels.add(label))
                            {
                                String desc = p.getAdditionalProposalInfo();
                                    fixes.add(new QuickFix(label, desc, m -> applyJdtProposal(p, m)));
                            }
                        }
                    }
                }
            }
            else
            {
                // Non-Java marker: use the registry; run() works for PDE/m2e/build-path fixes
                org.eclipse.ui.IMarkerHelpRegistry registry = org.eclipse.ui.ide.IDE.getMarkerHelpRegistry();
                if (registry.hasResolutions(marker))
                {
                    for (org.eclipse.ui.IMarkerResolution r : registry.getResolutions(marker))
                    {
                        String label = r.getLabel();
                        if (seenLabels.add(label))
                        {
                            String desc = (r instanceof org.eclipse.ui.IMarkerResolution2 r2) ? r2.getDescription() : null;
                            fixes.add(new QuickFix(label, desc, m -> { r.run(m); }));
                        }
                    }
                }
            }
        }
        catch (Exception e)
        {
            // best-effort
        }

        return fixes;
    }
    /** Applies a JDT IJavaCompletionProposal headlessly. */
    private void applyJdtProposal(IJavaCompletionProposal proposal, IMarker marker)
    {
        try
        {
            IFile file = (IFile) marker.getResource();

            // For ICUCorrectionProposal: extract the TextEdit from the TextChange and apply
            // it directly to a Document built from the current file bytes. This is the only
            // path that works reliably in both live Eclipse and headless Tycho environments:
            // - proposal.apply(IDocument) may be a no-op headlessly (needs active editor)
            // - TextChange.perform() does not modify the document returned by getCurrentDocument()
            if (proposal instanceof org.eclipse.jdt.core.manipulation.ICUCorrectionProposal icp)
            {
                org.eclipse.ltk.core.refactoring.TextChange change = icp.getTextChange();
                String currentContent = new String(file.getContents(true).readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                org.eclipse.jface.text.Document doc = new org.eclipse.jface.text.Document(currentContent);
                org.eclipse.text.edits.TextEdit edit = change.getEdit();
                if (edit != null)
                {
                    edit.apply(doc);
                }
                String charset = file.getCharset();
                byte[] bytes = doc.get().getBytes(java.nio.charset.Charset.forName(charset));
                file.setContents(new java.io.ByteArrayInputStream(bytes), IResource.FORCE | IResource.KEEP_HISTORY, new NullProgressMonitor());
                return;
            }

            // Fallback for non-ICU proposals: use TextFileDocumentProvider.
            org.eclipse.ui.editors.text.TextFileDocumentProvider provider =
                new org.eclipse.ui.editors.text.TextFileDocumentProvider();
            provider.connect(file);
            try
            {
                org.eclipse.jface.text.IDocument doc = provider.getDocument(file);
                if (doc == null)
                    throw new RuntimeException("Could not open document for " + file.getFullPath());
                proposal.apply(doc);
                String charset = file.getCharset();
                byte[] bytes = doc.get().getBytes(java.nio.charset.Charset.forName(charset));
                file.setContents(new java.io.ByteArrayInputStream(bytes), IResource.FORCE | IResource.KEEP_HISTORY, new NullProgressMonitor());
            }
            finally
            {
                provider.disconnect(file);
                // Explicitly disconnect the underlying ITextFileBuffer to release the OS file handle
                // on Windows, where file handles are not released until the reference count reaches zero.
                org.eclipse.core.filebuffers.ITextFileBufferManager mgr =
                    org.eclipse.core.filebuffers.FileBuffers.getTextFileBufferManager();
                org.eclipse.core.runtime.IPath loc = file.getFullPath();
                org.eclipse.core.filebuffers.ITextFileBuffer buf =
                    mgr.getTextFileBuffer(loc, org.eclipse.core.filebuffers.LocationKind.IFILE);
                if (buf != null)
                {
                    mgr.disconnect(loc, org.eclipse.core.filebuffers.LocationKind.IFILE, new NullProgressMonitor());
                }
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException("applyJdtProposal failed: " + e.getMessage(), e);
        }
    }



    /**
     * Flushes the Eclipse ITextFileBuffer for the given IFile to disk.
     * IMarkerResolution.run() modifies the file buffer but may not commit it in a headless
     * (Tycho) environment. This ensures the changes reach disk.
     * ICUCorrectionProposal already writes via IFile.setContents() inside applyJdtProposal(),
     * so calling this afterward is harmless -- the buffer will be clean and isDirty() = false.
     */
    private void saveFileBuffer(IFile file)
    {
        try
        {
            org.eclipse.core.filebuffers.ITextFileBufferManager mgr =
                org.eclipse.core.filebuffers.FileBuffers.getTextFileBufferManager();
            org.eclipse.core.runtime.IPath location = file.getFullPath();
            mgr.connect(location, org.eclipse.core.filebuffers.LocationKind.IFILE, new NullProgressMonitor());
            try
            {
                org.eclipse.core.filebuffers.ITextFileBuffer buf =
                    mgr.getTextFileBuffer(location, org.eclipse.core.filebuffers.LocationKind.IFILE);
                if (buf != null && buf.isDirty())
                {
                    buf.commit(new NullProgressMonitor(), true);
                }
            }
            finally
            {
                mgr.disconnect(location, org.eclipse.core.filebuffers.LocationKind.IFILE, new NullProgressMonitor());
            }
        }
        catch (Exception e)
        {
            // best-effort: ICUCorrectionProposal already wrote directly via IFile.setContents()
        }
    }

    /**
     * Waits for Eclipse's auto-build to complete so that markers are refreshed
     * with correct offsets before the next quick fix is applied.
     */
    private void waitForAutoBuild()
    {
        try
        {
            // Joining with the operation's monitor rather than a NullProgressMonitor is what
            // makes this interruptible: a NullProgressMonitor can never be cancelled, so a
            // stuck or looping auto-build would park this thread with no way to reach it.
            IProgressMonitor monitor = OperationContext.current()
                    .map( Operation::monitor )
                    .map( IProgressMonitor.class::cast )
                    .orElseGet( NullProgressMonitor::new );
            org.eclipse.core.runtime.jobs.Job.getJobManager()
                .join(ResourcesPlugin.FAMILY_AUTO_BUILD, monitor);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        catch (Exception e)
        {
            // best-effort
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
     * Candidate types for the unresolved names in a Java file.
     * <p>
     * The answer is the fully qualified name, and it used to be wrapped in backticks
     * inside an {@code import …;} statement inside a two-space bullet - four
     * decorations to strip before it could be used. It is now a bare string in
     * {@code candidates}.
     * <p>
     * "No such project", "the project is closed", "no such file", "no unresolved types"
     * and "no candidates for this type" were five sentences that a caller could only
     * tell apart by reading them, and the first two were the same sentence. Each is a
     * status or a count.
     */
    public ImportSuggestionsResponse getImportSuggestions(String projectName, String filePath)
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists())
        {
            return ImportSuggestionsResponse.failed(projectName, filePath,
                    ImportSuggestionsResponse.Status.PROJECT_NOT_FOUND,
                    Diagnostic.fatal(DiagnosticCode.PROJECT_NOT_FOUND,
                            "No project named '" + projectName + "' exists in the workspace."));
        }
        // Distinct from the above: a closed project is one openProject call away, and
        // conflating the two sent a caller looking for a typo it had not made.
        if (!project.isOpen())
        {
            return ImportSuggestionsResponse.failed(projectName, filePath,
                    ImportSuggestionsResponse.Status.PROJECT_CLOSED,
                    Diagnostic.fatal(DiagnosticCode.RESOURCE_NOT_ACCESSIBLE,
                            "Project '" + projectName + "' is closed. Open it and try again."));
        }

        IFile file = project.getFile(filePath);
        if (!file.exists())
        {
            return ImportSuggestionsResponse.failed(projectName, filePath,
                    ImportSuggestionsResponse.Status.FILE_NOT_FOUND,
                    Diagnostic.fatal(DiagnosticCode.RESOURCE_NOT_FOUND,
                            "File '" + filePath + "' does not exist in project '" + projectName + "'."));
        }

        try
        {
            List<ImportSuggestionsResponse.UnresolvedType> unresolved = new ArrayList<>();

            for (IMarker marker : file.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO))
            {
                String message = (String) marker.getAttribute(IMarker.MESSAGE);
                Integer severity = (Integer) marker.getAttribute(IMarker.SEVERITY);

                if (message == null || severity == null || severity != IMarker.SEVERITY_ERROR)
                {
                    continue;
                }
                if (!message.contains("cannot be resolved"))
                {
                    continue;
                }

                String typeName = message.split(" ")[0].replace("\"", "");
                Integer line = (Integer) marker.getAttribute(IMarker.LINE_NUMBER);

                unresolved.add(new ImportSuggestionsResponse.UnresolvedType(
                        typeName,
                        line == null ? -1 : line.intValue(),
                        message,
                        findCandidateTypes(typeName)));
            }

            return ImportSuggestionsResponse.of(projectName, filePath, unresolved);
        }
        catch (Exception e)
        {
            logger.error(e.getMessage(), e);
            return ImportSuggestionsResponse.failed(projectName, filePath,
                    ImportSuggestionsResponse.Status.FAILED,
                    Diagnostic.fatal(DiagnosticCode.INTERNAL_ERROR,
                            "Error getting import suggestions: " + e.getMessage()));
        }
    }

    /** Every workspace type whose simple name matches exactly, as a fully qualified name. */
    private List<String> findCandidateTypes(String simpleName)
    {
        List<String> matches = new ArrayList<>();
        try
        {
            new org.eclipse.jdt.core.search.SearchEngine().searchAllTypeNames(
                    null, // any package
                    org.eclipse.jdt.core.search.SearchPattern.R_EXACT_MATCH,
                    simpleName.toCharArray(),
                    org.eclipse.jdt.core.search.SearchPattern.R_EXACT_MATCH,
                    org.eclipse.jdt.core.search.IJavaSearchConstants.TYPE,
                    org.eclipse.jdt.core.search.SearchEngine.createWorkspaceScope(),
                    new org.eclipse.jdt.core.search.TypeNameRequestor()
                    {
                        @Override
                        public void acceptType(int modifiers, char[] packageName, char[] simpleTypeName,
                                char[][] enclosingTypeNames, String path)
                        {
                            String qualifier = new String(packageName);
                            matches.add(qualifier.isEmpty()
                                    ? new String(simpleTypeName)
                                    : qualifier + "." + new String(simpleTypeName));
                        }
                    },
                    org.eclipse.jdt.core.search.IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
                    new NullProgressMonitor());
        }
        catch (Exception e)
        {
            // Best effort: a type the index cannot answer for still belongs in the
            // listing, with no candidates rather than not at all.
            logger.error(e.getMessage(), e);
        }
        return matches;
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