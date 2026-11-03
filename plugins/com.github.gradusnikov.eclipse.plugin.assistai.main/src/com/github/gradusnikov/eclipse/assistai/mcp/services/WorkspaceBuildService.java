package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.IJavaModelMarker;

import com.github.gradusnikov.eclipse.assistai.mcp.operations.Operation;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.OperationContext;
import com.github.gradusnikov.eclipse.assistai.mcp.results.CompilationProblemsResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.CompilationProblemsResponse.FileProblems;
import com.github.gradusnikov.eclipse.assistai.mcp.results.CompilationProblemsResponse.Problem;
import com.github.gradusnikov.eclipse.assistai.mcp.results.CompilationProblemsResponse.Severity;
import com.github.gradusnikov.eclipse.assistai.mcp.results.Diagnostic;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.WorkspaceBuildResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.WorkspaceBuildResponse.BuildKind;
import com.github.gradusnikov.eclipse.assistai.mcp.results.WorkspaceBuildResponse.Phase;
import com.github.gradusnikov.eclipse.assistai.mcp.results.WorkspaceBuildResponse.Status;

/** Refreshes saved resources, runs the configured builders and snapshots their markers. */
@Creatable
public class WorkspaceBuildService
{
    private final IWorkspace workspace = ResourcesPlugin.getWorkspace();

    public WorkspaceBuildResponse buildWorkspace( final String buildKind, final String refreshBeforeBuild,
            final String maxProblems )
    {
        final long started = System.nanoTime();
        final IProgressMonitor monitor = OperationContext.current().map( Operation::monitor )
                .orElseGet( NullProgressMonitor::new );
        BuildKind kind = null;
        boolean refresh = true;
        boolean completed = false;
        Phase phase = Phase.VALIDATION;
        Status status = Status.FAILED;
        CompilationProblemsResponse problems = null;
        final List<Diagnostic> diagnostics = new ArrayList<>();
        try
        {
            kind = buildKind == null || buildKind.isBlank() ? BuildKind.INCREMENTAL
                    : BuildKind.valueOf( buildKind.trim().toUpperCase( Locale.ROOT ) );
            if ( refreshBeforeBuild != null && !refreshBeforeBuild.isBlank() )
            {
                if ( !"true".equalsIgnoreCase( refreshBeforeBuild.trim() )
                        && !"false".equalsIgnoreCase( refreshBeforeBuild.trim() ) )
                {
                    throw new IllegalArgumentException( "refreshBeforeBuild must be true or false." );
                }
                refresh = Boolean.parseBoolean( refreshBeforeBuild.trim() );
            }
            final int limit = maxProblems == null || maxProblems.isBlank() ? 100 : Integer.parseInt( maxProblems.trim() );
            if ( limit < 0 || limit > 1000 )
            {
                throw new IllegalArgumentException( "maxProblems must be between 0 and 1000." );
            }
            phase = Phase.WAITING;
            monitor.setTaskName( "Waiting to build the workspace" );
            checkCancelled( monitor );
            // Keep refresh, build and the marker snapshot together. The root rule also
            // serializes with automatic builds and prevents a second build changing markers.
            final var rule = workspace.getRoot();
            Job.getJobManager().beginRule( rule, monitor );
            try
            {
                final SubMonitor progress = SubMonitor.convert( monitor, "Building the workspace", 100 );
                if ( refresh )
                {
                    phase = Phase.REFRESH;
                    progress.setTaskName( "Refreshing saved workspace resources" );
                    workspace.getRoot().refreshLocal( IResource.DEPTH_INFINITE, progress.split( 20 ) );
                }
                checkCancelled( monitor );
                phase = Phase.BUILD;
                progress.setTaskName( "Running " + kind + " workspace build" );
                workspace.build( kind == BuildKind.FULL ? IncrementalProjectBuilder.FULL_BUILD
                        : IncrementalProjectBuilder.INCREMENTAL_BUILD, progress.split( 70 ) );
                completed = true;
                checkCancelled( monitor );
                phase = Phase.DIAGNOSTICS;
                progress.setTaskName( "Collecting post-build problems" );
                problems = collectProblems( limit, monitor );
                checkCancelled( monitor );
                phase = Phase.COMPLETED;
                status = problems.hasErrors() ? Status.COMPLETED_WITH_ERRORS : Status.SUCCESS;
            }
            finally
            {
                Job.getJobManager().endRule( rule );
            }
        }
        catch ( OperationCanceledException e )
        {
            status = Status.CANCELLED;
        }
        catch ( CoreException e )
        {
            status = monitor.isCanceled() || e.getStatus().matches( IStatus.CANCEL ) ? Status.CANCELLED : Status.FAILED;
            addDiagnostics( e.getStatus(), diagnostics );
        }
        catch ( RuntimeException e )
        {
            status = monitor.isCanceled() || Thread.currentThread().isInterrupted() ? Status.CANCELLED : Status.FAILED;
            diagnostics.add( Diagnostic.fatal( phase == Phase.VALIDATION ? DiagnosticCode.VALIDATION_ERROR
                    : DiagnosticCode.INTERNAL_ERROR, e.toString() ) );
        }
        finally
        {
            monitor.done();
        }
        final long duration = TimeUnit.NANOSECONDS.toMillis( System.nanoTime() - started );
        final String summary = "Workspace build " + status + " after " + duration + "ms (" + phase + ")."
                + ( problems == null ? " No post-build problem snapshot is available."
                        : " " + problems.errorCount() + " errors, " + problems.warningCount() + " warnings." );
        return new WorkspaceBuildResponse( status, kind, "WORKSPACE", refresh, completed, phase, duration,
                problems, List.copyOf( diagnostics ), summary );
    }

    private static void checkCancelled( final IProgressMonitor monitor )
    {
        if ( monitor.isCanceled() || Thread.currentThread().isInterrupted() )
        {
            throw new OperationCanceledException();
        }
    }

    private static void addDiagnostics( final IStatus status, final List<Diagnostic> diagnostics )
    {
        if ( diagnostics.size() < 50 )
        {
            diagnostics.add( Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                    status.getPlugin() + " (" + status.getCode() + "): " + status.getMessage() ) );
            for ( final IStatus child : status.getChildren() )
            {
                addDiagnostics( child, diagnostics );
            }
        }
    }

    private CompilationProblemsResponse collectProblems( final int limit, final IProgressMonitor monitor ) throws CoreException
    {
        final IMarker[] markers = workspace.getRoot().findMarkers( IMarker.PROBLEM, true, IResource.DEPTH_INFINITE );
        // Reuse the analysis response shape, but avoid source/quick-fix resolution while
        // holding the workspace rule: that can need the UI thread or trigger more work.
        Arrays.sort( markers, Comparator.comparingInt( (IMarker marker) -> marker.getAttribute( IMarker.SEVERITY, -1 ) ).reversed() );
        final Map<IResource, List<Problem>> byResource = new LinkedHashMap<>();
        int errors = 0;
        int warnings = 0;
        int infos = 0;
        for ( int i = 0; i < markers.length; i++ )
        {
            checkCancelled( monitor );
            final IMarker marker = markers[i];
            final Severity severity = switch ( marker.getAttribute( IMarker.SEVERITY, -1 ) )
            {
                case IMarker.SEVERITY_ERROR -> { errors++; yield Severity.ERROR; }
                case IMarker.SEVERITY_WARNING -> { warnings++; yield Severity.WARNING; }
                case IMarker.SEVERITY_INFO -> { infos++; yield Severity.INFO; }
                default -> Severity.UNKNOWN;
            };
            if ( i < limit )
            {
                final Object problemId = marker.isSubtypeOf( IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER )
                        ? marker.getAttribute( IJavaModelMarker.ID ) : null;
                final Problem problem = new Problem( severity, marker.getAttribute( IMarker.LINE_NUMBER, -1 ),
                        marker.getAttribute( IMarker.MESSAGE, "No message provided" ), marker.getId(),
                        problemId instanceof Integer id ? id : null, null, null, List.of() );
                byResource.computeIfAbsent( marker.getResource(), ignored -> new ArrayList<>() ).add( problem );
            }
        }
        final List<FileProblems> files = new ArrayList<>();
        for ( final var entry : byResource.entrySet() )
        {
            final IResource resource = entry.getKey();
            files.add( new FileProblems( resource.getProject() == null ? null : resource.getProject().getName(),
                    resource.getProjectRelativePath().toString(), List.copyOf( entry.getValue() ) ) );
        }
        return new CompilationProblemsResponse( "Scope: All Projects", markers.length, errors, warnings, infos,
                List.copyOf( files ), markers.length > limit,
                "Showing " + Math.min( limit, markers.length ) + " of " + markers.length + " post-build problems." );
    }
}
