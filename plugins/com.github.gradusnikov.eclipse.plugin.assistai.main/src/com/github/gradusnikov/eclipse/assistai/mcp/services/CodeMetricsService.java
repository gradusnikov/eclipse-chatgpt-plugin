
package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.corext.callhierarchy.CallHierarchy;
import org.eclipse.jdt.internal.corext.callhierarchy.MethodWrapper;

import com.github.gradusnikov.eclipse.assistai.mcp.results.DeadCodeResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.Diagnostic;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.MethodMetricsResponse;
import com.github.gradusnikov.eclipse.assistai.tools.LineOffsets;

import org.eclipse.e4.core.di.annotations.Creatable;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Per-class code-shape queries that read live from JDT: methods of one class with no
 * references (dead-code candidates) and per-method fan-in/fan-out.
 * <p>
 * Both answers used to come from a separate knowledge-graph index that had to be rebuilt
 * and could lag the source. These compute from the compiler model on each call, so they
 * are exact for the workspace as it stands.
 * <p>
 * The scope is one class, deliberately. The cost of each answer is a JDT search or call
 * hierarchy <em>per method</em>, and JDT serializes those on its index lock, so a
 * whole-project scan is both slow and un-parallelizable. One class declares few enough
 * methods that the sequential cost is the same order as a single
 * {@code getMethodCallHierarchy} call - which is the scope at which these are useful: an
 * agent asking "is anything in this class unused?" or "which methods here are the hubs?"
 */
@Creatable
@Singleton
public class CodeMetricsService
{
    @Inject
    ILog logger;

    @Inject
    CodeAnalysisService codeAnalysisService;

    /**
     * Annotations that make a method an entry point: something outside normal Java call
     * sites invokes it, so zero references does not mean dead. Matched by simple name,
     * case-sensitive. Kept to standard JUnit, Jakarta/e4 DI and common Spring web names;
     * project-specific frameworks are deliberately out of this upstream default.
     */
    private static final Set<String> ENTRY_POINT_ANNOTATIONS = Set.of(
            // JUnit 5
            "Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate",
            "BeforeEach", "AfterEach", "BeforeAll", "AfterAll",
            // JUnit 4
            "Before", "After", "BeforeClass", "AfterClass",
            // Jakarta / CDI / e4 DI lifecycle
            "Inject", "PostConstruct", "PreDestroy", "Produces", "Execute", "CanExecute",
            // an override is reached through its supertype
            "Override",
            // common Spring stereotypes that are invoked by the container
            "Bean", "EventListener",
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping" );

    /**
     * Methods of one class with no workspace references that are not recognizable entry
     * points.
     * <p>
     * The result is a list of <em>candidates</em>: the reference count is exact, but
     * reflection, dependency injection, serialization and non-Java callers can keep a
     * method this reports as unused. Entry points - {@code main}, constructors, test and
     * DI annotated methods, and overrides - are excluded before the reference search,
     * both because they are reachable by construction and because the search is the cost.
     *
     * @param fullyQualifiedClassName the class whose methods are scanned
     * @param maxResults how many candidates to list; the count is of everything that matched
     */
    @SuppressWarnings("restriction")
    public DeadCodeResponse findDeadCode( String fullyQualifiedClassName, Integer maxResults )
    {
        int limit = ( maxResults == null || maxResults < 1 ) ? 50 : maxResults;

        IType type;
        try
        {
            type = codeAnalysisService.findType( fullyQualifiedClassName );
        }
        catch ( JavaModelException e )
        {
            logger.error( e.getMessage(), e );
            return DeadCodeResponse.failed( fullyQualifiedClassName, DeadCodeResponse.Status.FAILED,
                    Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                            "Error resolving '" + fullyQualifiedClassName + "': "
                                    + ExceptionUtils.getRootCauseMessage( e ) ) );
        }
        if ( type == null )
        {
            return DeadCodeResponse.failed( fullyQualifiedClassName, DeadCodeResponse.Status.TYPE_NOT_FOUND,
                    Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_FOUND, "Type '" + fullyQualifiedClassName
                            + "' is not on the build path of any open Java project." ) );
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        List<DeadCodeResponse.Candidate> candidates = new ArrayList<>();
        Map<IFile, String> sources = new LinkedHashMap<>();
        Map<IType, ITypeHierarchy> hierarchies = new LinkedHashMap<>();
        int methodsScanned = 0;
        int totalCandidates = 0;

        try
        {
            for ( IMethod method : type.getMethods() )
            {
                methodsScanned++;
                try
                {
                    if ( isEntryPoint( method, hierarchies ) )
                    {
                        continue;
                    }
                    if ( countReferences( method ) == 0 )
                    {
                        totalCandidates++;
                        if ( candidates.size() < limit )
                        {
                            candidates.add( toDeadCandidate( method, diagnostics, sources ) );
                        }
                    }
                }
                catch ( Exception e )
                {
                    logger.error( e.getMessage(), e );
                    diagnostics.add( Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                            "Could not analyze " + method.getElementName() + ": " + e.getMessage() ) );
                }
            }
        }
        catch ( Exception e )
        {
            logger.error( e.getMessage(), e );
            return DeadCodeResponse.failed( fullyQualifiedClassName, DeadCodeResponse.Status.FAILED,
                    Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                            "Error scanning for dead code: " + ExceptionUtils.getRootCauseMessage( e ) ) );
        }

        boolean truncated = totalCandidates > candidates.size();
        return DeadCodeResponse.of( fullyQualifiedClassName, methodsScanned, candidates, truncated,
                totalCandidates, diagnostics );
    }

    /**
     * Per-method fan-in (distinct direct callers) and fan-out (distinct direct callees)
     * for the methods of one class, from the same JDT call hierarchy
     * {@code getMethodCallHierarchy} walks, counted one level deep instead of expanded
     * into a tree.
     *
     * @param fullyQualifiedClassName the class whose methods are measured
     * @param minFanOut only report methods whose fan-out is at least this; 0 = no floor
     * @param minFanIn only report methods whose fan-in is at least this; 0 = no floor
     * @param maxResults how many methods to list, highest total first
     */
    @SuppressWarnings("restriction")
    public MethodMetricsResponse getMethodMetrics( String fullyQualifiedClassName, Integer minFanOut,
            Integer minFanIn, Integer maxResults )
    {
        int floorOut = ( minFanOut == null || minFanOut < 0 ) ? 0 : minFanOut;
        int floorIn = ( minFanIn == null || minFanIn < 0 ) ? 0 : minFanIn;
        int limit = ( maxResults == null || maxResults < 1 ) ? 50 : maxResults;

        IType type;
        try
        {
            type = codeAnalysisService.findType( fullyQualifiedClassName );
        }
        catch ( JavaModelException e )
        {
            logger.error( e.getMessage(), e );
            return MethodMetricsResponse.failed( fullyQualifiedClassName, MethodMetricsResponse.Status.FAILED,
                    Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                            "Error resolving '" + fullyQualifiedClassName + "': "
                                    + ExceptionUtils.getRootCauseMessage( e ) ) );
        }
        if ( type == null )
        {
            return MethodMetricsResponse.failed( fullyQualifiedClassName, MethodMetricsResponse.Status.TYPE_NOT_FOUND,
                    Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_FOUND, "Type '" + fullyQualifiedClassName
                            + "' is not on the build path of any open Java project." ) );
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        List<MethodMetricsResponse.MethodMetric> matched = new ArrayList<>();
        Map<IFile, String> sources = new LinkedHashMap<>();
        int methodsScanned = 0;

        try
        {
            CallHierarchy callHierarchy = CallHierarchy.getDefault();

            for ( IMethod method : type.getMethods() )
            {
                methodsScanned++;
                try
                {
                    int fanIn = distinctDirectCalls( callHierarchy.getCallerRoots( new IMethod[] { method } ) );
                    int fanOut = distinctDirectCalls( callHierarchy.getCalleeRoots( new IMethod[] { method } ) );

                    if ( fanOut >= floorOut && fanIn >= floorIn )
                    {
                        matched.add( toMethodMetric( method, fanIn, fanOut, diagnostics, sources ) );
                    }
                }
                catch ( Exception e )
                {
                    logger.error( e.getMessage(), e );
                    diagnostics.add( Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                            "Could not measure " + method.getElementName() + ": " + e.getMessage() ) );
                }
            }
        }
        catch ( Exception e )
        {
            logger.error( e.getMessage(), e );
            return MethodMetricsResponse.failed( fullyQualifiedClassName, MethodMetricsResponse.Status.FAILED,
                    Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                            "Error measuring method metrics: " + ExceptionUtils.getRootCauseMessage( e ) ) );
        }

        // Highest first: a caller reading a truncated list should see the most connected
        // methods, which are the ones a refactoring has to be careful around.
        matched.sort( ( first, second ) ->
        {
            int byTotal = Integer.compare( second.fanIn() + second.fanOut(), first.fanIn() + first.fanOut() );
            return byTotal != 0 ? byTotal : Integer.compare( second.fanIn(), first.fanIn() );
        } );

        int totalMatched = matched.size();
        boolean truncated = totalMatched > limit;
        List<MethodMetricsResponse.MethodMetric> listed = truncated ? matched.subList( 0, limit ) : matched;

        return MethodMetricsResponse.of( fullyQualifiedClassName, floorOut, floorIn, methodsScanned,
                new ArrayList<>( listed ), truncated, totalMatched, diagnostics );
    }

    // ------------------------------------------------------------------------
    // shared helpers
    // ------------------------------------------------------------------------

    /**
     * Whether a method is reachable by something other than a plain Java call, so a zero
     * reference count does not mean it is dead.
     */
    private boolean isEntryPoint( IMethod method, Map<IType, ITypeHierarchy> hierarchies ) throws JavaModelException
    {
        if ( method.isConstructor() )
        {
            return true;
        }
        if ( "main".equals( method.getElementName() )
                && method.getParameterTypes().length == 1 )
        {
            return true;
        }
        for ( IAnnotation annotation : method.getAnnotations() )
        {
            if ( ENTRY_POINT_ANNOTATIONS.contains( annotation.getElementName() ) )
            {
                return true;
            }
        }
        return overridesSupertypeMethod( method, hierarchies );
    }

    /**
     * Whether a superclass or super-interface of the declaring type declares a method of
     * the same name and parameter count - belt and braces beyond {@code @Override}, which
     * source is not required to write.
     */
    private boolean overridesSupertypeMethod( IMethod method, Map<IType, ITypeHierarchy> hierarchies )
            throws JavaModelException
    {
        IType declaringType = method.getDeclaringType();
        if ( declaringType == null )
        {
            return false;
        }
        ITypeHierarchy hierarchy = hierarchies.get( declaringType );
        if ( hierarchy == null )
        {
            hierarchy = declaringType.newSupertypeHierarchy( new NullProgressMonitor() );
            hierarchies.put( declaringType, hierarchy );
        }

        int parameterCount = method.getParameterTypes().length;
        String name = method.getElementName();

        List<IType> supertypes = new ArrayList<>();
        for ( IType superclass : hierarchy.getAllSuperclasses( declaringType ) )
        {
            supertypes.add( superclass );
        }
        for ( IType superInterface : hierarchy.getAllSuperInterfaces( declaringType ) )
        {
            supertypes.add( superInterface );
        }
        for ( IType supertype : supertypes )
        {
            for ( IMethod candidate : supertype.getMethods() )
            {
                if ( candidate.getElementName().equals( name )
                        && candidate.getParameterTypes().length == parameterCount )
                {
                    return true;
                }
            }
        }
        return false;
    }

    /** Workspace references to a method, counted with the same engine {@code findReferences} uses. */
    private int countReferences( IMethod method ) throws CoreException
    {
        var searchEngine = new org.eclipse.jdt.core.search.SearchEngine();
        var pattern = org.eclipse.jdt.core.search.SearchPattern.createPattern(
                method, org.eclipse.jdt.core.search.IJavaSearchConstants.REFERENCES );
        if ( pattern == null )
        {
            return 0;
        }
        var scope = org.eclipse.jdt.core.search.SearchEngine.createWorkspaceScope();

        int[] count = { 0 };
        var requestor = new org.eclipse.jdt.core.search.SearchRequestor()
        {
            @Override
            public void acceptSearchMatch( org.eclipse.jdt.core.search.SearchMatch match )
            {
                count[0]++;
            }
        };
        searchEngine.search( pattern,
                new org.eclipse.jdt.core.search.SearchParticipant[] {
                        org.eclipse.jdt.core.search.SearchEngine.getDefaultSearchParticipant() },
                scope, requestor, new NullProgressMonitor() );
        return count[0];
    }

    /** Distinct direct callers or callees one level below the given roots. */
    @SuppressWarnings("restriction")
    private int distinctDirectCalls( MethodWrapper[] roots )
    {
        if ( roots == null )
        {
            return 0;
        }
        Set<String> distinct = new HashSet<>();
        for ( MethodWrapper root : roots )
        {
            MethodWrapper[] calls;
            try
            {
                calls = root.getCalls( new NullProgressMonitor() );
            }
            catch ( Exception e )
            {
                logger.error( e.getMessage(), e );
                continue;
            }
            if ( calls == null )
            {
                continue;
            }
            for ( MethodWrapper call : calls )
            {
                if ( call.getMember() instanceof IMethod member )
                {
                    distinct.add( member.getHandleIdentifier() );
                }
            }
        }
        return distinct.size();
    }

    private DeadCodeResponse.Candidate toDeadCandidate( IMethod method, List<Diagnostic> diagnostics,
            Map<IFile, String> sources )
    {
        Location location = locate( method, diagnostics, sources );
        return new DeadCodeResponse.Candidate( method.getElementName(), location.declaringType, location.signature,
                location.projectName, location.filePath, location.lineNumber );
    }

    private MethodMetricsResponse.MethodMetric toMethodMetric( IMethod method, int fanIn, int fanOut,
            List<Diagnostic> diagnostics, Map<IFile, String> sources )
    {
        Location location = locate( method, diagnostics, sources );
        return new MethodMetricsResponse.MethodMetric( method.getElementName(), location.declaringType,
                location.signature, location.projectName, location.filePath, location.lineNumber, fanIn, fanOut );
    }

    /** The location triple and signature the reading tools take, resolved like {@code toCallNode}. */
    private Location locate( IMethod method, List<Diagnostic> diagnostics, Map<IFile, String> sources )
    {
        Location location = new Location();
        try
        {
            IType type = method.getDeclaringType();
            location.declaringType = type == null ? null : type.getFullyQualifiedName();
            location.signature = CodeAnalysisService.formatMethodParameters( method );

            if ( method.getResource() instanceof IFile file )
            {
                location.projectName = file.getProject().getName();
                location.filePath = file.getProjectRelativePath().toString();

                ISourceRange nameRange = method.getNameRange();
                if ( nameRange != null && nameRange.getOffset() >= 0 )
                {
                    String source = sources.computeIfAbsent( file, this::readFileQuietly );
                    if ( source != null )
                    {
                        location.lineNumber = LineOffsets.lineInfoAt( source, nameRange.getOffset() ).lineNumber();
                    }
                }
            }
        }
        catch ( Exception e )
        {
            logger.error( e.getMessage(), e );
            diagnostics.add( Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                    "Could not resolve details for " + method.getElementName() + ": " + e.getMessage() ) );
        }
        return location;
    }

    private String readFileQuietly( IFile file )
    {
        try ( var is = file.getContents() )
        {
            return new String( is.readAllBytes(), file.getCharset() );
        }
        catch ( Exception e )
        {
            return null;
        }
    }

    /** A small mutable carrier so the location triple is resolved in one place. */
    private static final class Location
    {
        String declaringType;
        String signature;
        String projectName;
        String filePath;
        int lineNumber = -1;
    }
}
