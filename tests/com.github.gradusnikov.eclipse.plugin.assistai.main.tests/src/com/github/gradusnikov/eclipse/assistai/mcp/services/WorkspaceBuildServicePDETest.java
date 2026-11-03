package com.github.gradusnikov.eclipse.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceDescription;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.github.gradusnikov.eclipse.assistai.mcp.McpJson;
import com.github.gradusnikov.eclipse.assistai.mcp.McpOutputSchemas;
import com.github.gradusnikov.eclipse.assistai.mcp.ToolExecutor;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.Operation;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.OperationRegistry;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.OperationState;
import com.github.gradusnikov.eclipse.assistai.mcp.results.CompilationProblemsResponse.Severity;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.SchemaTypes;
import com.github.gradusnikov.eclipse.assistai.mcp.results.WorkspaceBuildResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.WorkspaceBuildResponse.BuildKind;
import com.github.gradusnikov.eclipse.assistai.mcp.results.WorkspaceBuildResponse.Phase;
import com.github.gradusnikov.eclipse.assistai.mcp.results.WorkspaceBuildResponse.Status;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.EclipseIntegrationsMcpServer;

/** Exercises actual workspace builders with auto-build off, including the MCP hand-off path. */
@Timeout( 60 )
public class WorkspaceBuildServicePDETest
{
    private static final String BUILDER_ID = "com.github.gradusnikov.eclipse.plugin.assistai.main.tests.workspaceBuildTestBuilder";
    private final IWorkspace workspace = ResourcesPlugin.getWorkspace();
    private final NullProgressMonitor monitor = new NullProgressMonitor();
    private final List<IProject> projects = new ArrayList<>();
    private final WorkspaceBuildService service = new WorkspaceBuildService();
    private boolean autoBuilding;

    @BeforeEach
    public void disableAutoBuild() throws Exception
    {
        final IWorkspaceDescription description = workspace.getDescription();
        autoBuilding = description.isAutoBuilding();
        description.setAutoBuilding( false );
        workspace.setDescription( description );
        Job.getJobManager().join( ResourcesPlugin.FAMILY_AUTO_BUILD, monitor );
        Job.getJobManager().join( ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor );
    }

    @AfterEach
    public void cleanup() throws Exception
    {
        try
        {
            for ( final IProject project : projects.reversed() )
            {
                project.delete( true, true, monitor );
            }
        }
        finally
        {
            final IWorkspaceDescription description = workspace.getDescription();
            description.setAutoBuilding( autoBuilding );
            workspace.setDescription( description );
        }
    }

    @Test
    public void refreshBuildSeesExternalErrorsAndFixesStaleMarkersAndClassOutput() throws Exception
    {
        final IProject project = javaProject( "ExternalWrites" );
        final IFile source = project.getFile( "src/Example.java" );
        Files.writeString( source.getLocation().toFile().toPath(), "public class Example { int value = 1; }" );
        final WorkspaceBuildResponse initial = build();
        assertEquals( Status.SUCCESS, initial.status(), initial.toString() );
        assertEquals( BuildKind.INCREMENTAL, initial.buildKind() );
        assertTrue( initial.refreshBeforeBuild() );
        assertTrue( initial.buildCompleted() );
        final var output = project.getFile( "bin/Example.class" ).getLocation().toFile().toPath();
        final byte[] originalClass = Files.readAllBytes( output );
        Files.writeString( source.getLocation().toFile().toPath(), "public class Example { MissingType value; }" );
        final WorkspaceBuildResponse broken = build();
        assertEquals( Status.COMPLETED_WITH_ERRORS, broken.status(), broken.toString() );
        assertTrue( broken.buildCompleted() );
        assertTrue( broken.problems().errorCount() > 0 );
        assertTrue( broken.problems().files().stream().anyMatch( file -> project.getName().equals( file.projectName() )
                && "src/Example.java".equals( file.filePath() )
                && file.problems().stream().anyMatch( problem -> problem.severity() == Severity.ERROR && problem.lineNumber() == 1 ) ) );
        Files.writeString( source.getLocation().toFile().toPath(), "public class Example { int value = 9876; }" );
        final WorkspaceBuildResponse fixed = build();
        assertEquals( Status.SUCCESS, fixed.status(), fixed.toString() );
        assertEquals( 0, fixed.problems().errorCount() );
        assertEquals( 0, project.findMarkers( IMarker.PROBLEM, true, IResource.DEPTH_INFINITE ).length );
        assertFalse( Arrays.equals( originalClass, Files.readAllBytes( output ) ), "The latest source must produce new bytecode" );
        assertEquals( Phase.COMPLETED, fixed.phase() );
        assertTrue( fixed.durationMillis() >= 0 );
        assertFalse( workspace.getDescription().isAutoBuilding(), "The tool must not enable auto-build" );
    }

    @Test
    public void workspaceBuildOrdersReferencedProjects() throws Exception
    {
        final IProject consumer = javaProject( "AConsumer" );
        final IProject dependency = javaProject( "ZDependency" );
        final var javaConsumer = JavaCore.create( consumer );
        final List<IClasspathEntry> classpath = new ArrayList<>( List.of( javaConsumer.getRawClasspath() ) );
        classpath.add( JavaCore.newProjectEntry( dependency.getFullPath() ) );
        javaConsumer.setRawClasspath( classpath.toArray( IClasspathEntry[]::new ), monitor );
        Files.createDirectories( dependency.getFolder( "src/example" ).getLocation().toFile().toPath() );
        Files.writeString( dependency.getFile( "src/example/Base.java" ).getLocation().toFile().toPath(),
                "package example; public class Base { public static int value() { return 42; } }" );
        Files.writeString( consumer.getFile( "src/Consumer.java" ).getLocation().toFile().toPath(),
                "public class Consumer { int value = example.Base.value(); }" );
        final WorkspaceBuildResponse result = build();
        assertEquals( Status.SUCCESS, result.status(), result.toString() );
        assertTrue( dependency.getFile( "bin/example/Base.class" ).exists() );
        assertTrue( consumer.getFile( "bin/Consumer.class" ).exists() );
        Files.writeString( dependency.getFile( "src/example/Base.java" ).getLocation().toFile().toPath(),
                "package example; public class Base { public static int updatedValue() { return 123; } }" );
        Files.writeString( consumer.getFile( "src/Consumer.java" ).getLocation().toFile().toPath(),
                "public class Consumer { int value = example.Base.updatedValue(); }" );
        final WorkspaceBuildResponse updated = build();
        assertEquals( Status.SUCCESS, updated.status(), updated.toString() );
    }

    @Test
    public void fullBuildAndRefreshOptOutUseTheRequestedSemantics() throws Exception
    {
        final IProject project = controlledProject( "record" );
        assertEquals( Status.SUCCESS, build().status() );
        ControlledBuilder.lastKind = -1;
        final var externalFile = project.getFile( "external.txt" );
        Files.writeString( externalFile.getLocation().toFile().toPath(), "external write" );
        final WorkspaceBuildResponse result = service.buildWorkspace( "FULL", "false", "0" );
        assertEquals( Status.SUCCESS, result.status(), result.toString() );
        assertEquals( BuildKind.FULL, result.buildKind() );
        assertFalse( result.refreshBeforeBuild() );
        assertFalse( externalFile.exists(), "Opting out must not refresh external files" );
        assertEquals( IncrementalProjectBuilder.FULL_BUILD, ControlledBuilder.lastKind );
        assertFalse( ControlledBuilder.cleaned, "FULL must not invoke clean" );
    }

    @Test
    public void boundedDetailsStillCountAllSeveritiesAndListErrorsFirst() throws Exception
    {
        final IProject project = javaProject( "Problems" );
        marker( project, IMarker.SEVERITY_WARNING, "warning one" );
        marker( project, IMarker.SEVERITY_ERROR, "error one" );
        marker( project, IMarker.SEVERITY_WARNING, "warning two" );
        final WorkspaceBuildResponse result = service.buildWorkspace( null, null, "1" );
        assertEquals( Status.COMPLETED_WITH_ERRORS, result.status(), result.toString() );
        assertEquals( 1, result.problems().errorCount() );
        assertEquals( 2, result.problems().warningCount() );
        assertTrue( result.problems().truncated() );
        assertEquals( 1, result.problems().files().stream().mapToInt( file -> file.problems().size() ).sum() );
        assertEquals( Severity.ERROR, result.problems().files().getFirst().problems().getFirst().severity() );
        final WorkspaceBuildResponse countsOnly = service.buildWorkspace( null, null, "0" );
        assertEquals( 3, countsOnly.problems().totalProblems() );
        assertTrue( countsOnly.problems().files().isEmpty() );
    }

    @Test
    public void buildExceptionAndBuilderCancellationAreNotSuccess() throws Exception
    {
        final IProject project = controlledProject( "fail" );
        final WorkspaceBuildResponse failed = build();
        assertEquals( Status.FAILED, failed.status(), failed.toString() );
        assertEquals( Phase.BUILD, failed.phase() );
        assertFalse( failed.buildCompleted() );
        assertNull( failed.problems(), "Failure must not advertise an empty successful snapshot" );
        assertTrue( failed.diagnostics().stream().anyMatch( diagnostic -> diagnostic.message().contains( "deliberate builder failure" ) ) );
        configureBuilder( project, "cancel" );
        final WorkspaceBuildResponse cancelled = build();
        assertEquals( Status.CANCELLED, cancelled.status(), cancelled.toString() );
        assertFalse( cancelled.buildCompleted() );
    }

    @Test
    public void invalidArgumentsDoNotBuild() throws Exception
    {
        controlledProject( "record" );
        for ( final String[] args : List.of( new String[] { "CLEAN", null, null },
                new String[] { null, "yes", null }, new String[] { null, null, "-1" },
                new String[] { null, null, "1001" }, new String[] { null, null, "many" } ) )
        {
            final WorkspaceBuildResponse result = service.buildWorkspace( args[0], args[1], args[2] );
            assertEquals( Status.FAILED, result.status() );
            assertEquals( Phase.VALIDATION, result.phase() );
            assertEquals( DiagnosticCode.VALIDATION_ERROR, result.diagnostics().getFirst().code() );
            assertFalse( result.buildCompleted() );
        }
        assertEquals( -1, ControlledBuilder.lastKind );
    }

    @Test
    public void realToolHandsOffAndRetainsStructuredCompletion() throws Exception
    {
        controlledProject( "block" );
        final OperationRegistry registry = new OperationRegistry();
        final Operation operation = startTool( registry );
        final CountDownLatch completed = new CountDownLatch( 1 );
        operation.addCompletionHook( completed::countDown );
        try
        {
            assertTrue( ControlledBuilder.entered.await( 10, TimeUnit.SECONDS ) );
            final String handOff = (String) registry.awaitOrHandOff( operation, 0 );
            assertTrue( handOff.contains( "operationId: " + operation.getId() ), handOff );
            assertEquals( OperationState.RUNNING, operation.getState() );
            assertNotNull( operation.getProgress() );
            ControlledBuilder.release.countDown();
            final WorkspaceBuildResponse result = (WorkspaceBuildResponse) operation.getFuture().get( 10, TimeUnit.SECONDS );
            assertEquals( Status.SUCCESS, result.status(), result.toString() );
            assertTrue( completed.await( 10, TimeUnit.SECONDS ) );
            assertEquals( result, operation.getStructuredResult() );
            assertEquals( OperationState.COMPLETED, operation.getState() );
        }
        finally
        {
            ControlledBuilder.release.countDown();
            assertTrue( ControlledBuilder.exited.await( 10, TimeUnit.SECONDS ) );
        }
    }

    @Test
    public void cancellingOperationStopsTheBuilderAndReportsCancellation() throws Exception
    {
        controlledProject( "block" );
        final OperationRegistry registry = new OperationRegistry();
        final Operation operation = startTool( registry );
        try
        {
            assertTrue( ControlledBuilder.entered.await( 10, TimeUnit.SECONDS ) );
            registry.cancelOperation( operation.getId() );
            assertTrue( ControlledBuilder.exited.await( 10, TimeUnit.SECONDS ), "Builder must stop, not merely stop waiting" );
            assertEquals( OperationState.CANCELLED, operation.getState() );
            assertTrue( operation.monitor().isCanceled() );
            assertNull( operation.getStructuredResult(), "A cancelled operation cannot publish SUCCESS" );
        }
        finally
        {
            ControlledBuilder.release.countDown();
        }
    }

    @Test
    @SuppressWarnings( "unchecked" )
    public void endpointAdvertisesItsStructuredSchema() throws Exception
    {
        final Tool annotation = EclipseIntegrationsMcpServer.class
                .getMethod( "buildWorkspace", String.class, String.class, String.class ).getAnnotation( Tool.class );
        assertTrue( annotation.longExecution() );
        assertEquals( WorkspaceBuildResponse.class, annotation.outputType() );
        final Map<String, Object> schema = McpOutputSchemas.forType( annotation.outputType() );
        final Map<String, Object> properties = (Map<String, Object>) schema.get( "properties" );
        final WorkspaceBuildResponse response = build();
        assertEquals( properties.keySet(), McpJson.toMap( response ).keySet() );
        assertTrue( properties.keySet().containsAll( List.of( "status", "buildKind", "scope", "refreshBeforeBuild",
                "buildCompleted", "phase", "durationMillis", "problems", "diagnostics" ) ) );
        assertEquals( List.of( "SUCCESS", "COMPLETED_WITH_ERRORS", "FAILED", "CANCELLED" ),
                SchemaTypes.enumOf( properties.get( "status" ) ) );
        assertEquals( List.of( "INCREMENTAL", "FULL" ), SchemaTypes.enumOf( properties.get( "buildKind" ) ) );
        assertTrue( SchemaTypes.propertiesOf( properties.get( "problems" ) ).keySet()
                .containsAll( List.of( "errorCount", "warningCount", "files", "truncated" ) ) );
        assertEquals( "WORKSPACE", response.scope() );
    }

    private WorkspaceBuildResponse build()
    {
        return service.buildWorkspace( null, null, null );
    }

    private Operation startTool( final OperationRegistry registry ) throws Exception
    {
        final EclipseIntegrationsMcpServer server = new EclipseIntegrationsMcpServer();
        final var field = EclipseIntegrationsMcpServer.class.getDeclaredField( "workspaceBuildService" );
        field.setAccessible( true );
        field.set( server, service );
        final Operation operation = registry.register( "buildWorkspace", "workspace" );
        registry.attachFuture( operation, new ToolExecutor( server ).call( "buildWorkspace", Map.of(), operation ) );
        return operation;
    }

    private IProject project( final String name ) throws CoreException
    {
        final IProject project = workspace.getRoot().getProject( name + "_" + UUID.randomUUID() );
        projects.add( project );
        project.create( monitor );
        project.open( monitor );
        return project;
    }

    private IProject javaProject( final String name ) throws CoreException
    {
        final IProject project = project( name );
        final IProjectDescription description = project.getDescription();
        description.setNatureIds( new String[] { JavaCore.NATURE_ID } );
        project.setDescription( description, monitor );
        project.getFolder( "src" ).create( true, true, monitor );
        project.getFolder( "bin" ).create( true, true, monitor );
        final var javaProject = JavaCore.create( project );
        javaProject.setOutputLocation( project.getFolder( "bin" ).getFullPath(), monitor );
        javaProject.setRawClasspath( new IClasspathEntry[] {
                JavaCore.newSourceEntry( project.getFolder( "src" ).getFullPath() ),
                JavaRuntime.getDefaultJREContainerEntry() }, monitor );
        return project;
    }

    private void marker( final IProject project, final int severity, final String message ) throws CoreException
    {
        final IMarker marker = project.createMarker( IMarker.PROBLEM );
        marker.setAttribute( IMarker.SEVERITY, severity );
        marker.setAttribute( IMarker.MESSAGE, message );
    }

    private IProject controlledProject( final String mode ) throws CoreException
    {
        ControlledBuilder.entered = new CountDownLatch( 1 );
        ControlledBuilder.release = new CountDownLatch( 1 );
        ControlledBuilder.exited = new CountDownLatch( 1 );
        ControlledBuilder.lastKind = -1;
        ControlledBuilder.cleaned = false;
        final IProject project = project( "ControlledBuilder" );
        configureBuilder( project, mode );
        return project;
    }

    private void configureBuilder( final IProject project, final String mode ) throws CoreException
    {
        assertNotNull( Platform.getExtensionRegistry().getExtension( "org.eclipse.core.resources.builders", BUILDER_ID ),
                "The test builder must be registered before its behavior can be tested" );
        final IProjectDescription description = project.getDescription();
        final ICommand builder = description.newCommand();
        builder.setBuilderName( BUILDER_ID );
        builder.setArguments( Map.of( "mode", mode ) );
        description.setBuildSpec( new ICommand[] { builder } );
        project.setDescription( description, monitor );
    }

    /** Registered only in the test fragment, providing real failure and cancellation paths. */
    public static class ControlledBuilder extends IncrementalProjectBuilder
    {
        static volatile CountDownLatch entered;
        static volatile CountDownLatch release;
        static volatile CountDownLatch exited;
        static volatile int lastKind;
        static volatile boolean cleaned;

        @Override
        protected IProject[] build( final int kind, final Map<String, String> args, final IProgressMonitor monitor ) throws CoreException
        {
            lastKind = kind;
            switch ( args.get( "mode" ) )
            {
                case "fail" -> throw new CoreException( new org.eclipse.core.runtime.Status(
                        org.eclipse.core.runtime.IStatus.ERROR, "workspace-build-test", "deliberate builder failure" ) );
                case "cancel" -> throw new OperationCanceledException();
                case "block" ->
                {
                    entered.countDown();
                    try
                    {
                        while ( !release.await( 20, TimeUnit.MILLISECONDS ) && !monitor.isCanceled() )
                        {
                            monitor.subTask( "Waiting in test builder" );
                        }
                        if ( monitor.isCanceled() )
                        {
                            throw new OperationCanceledException();
                        }
                    }
                    catch ( InterruptedException e )
                    {
                        Thread.currentThread().interrupt();
                        throw new OperationCanceledException();
                    }
                    finally
                    {
                        exited.countDown();
                    }
                }
                default -> { }
            }
            return null;
        }

        @Override
        protected void clean( final IProgressMonitor monitor )
        {
            cleaned = true;
        }
    }
}
