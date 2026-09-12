package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStreamMonitor;
import org.eclipse.debug.core.model.IStreamsProxy;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.m2e.actions.MavenLaunchConstants;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.embedder.ICallable;
import org.eclipse.m2e.core.embedder.IMaven;
import org.eclipse.m2e.core.embedder.IMavenExecutionContext;
import org.eclipse.m2e.core.internal.IMavenConstants;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.IMavenProjectRegistry;
import org.eclipse.m2e.core.project.MavenUpdateRequest;

import com.github.gradusnikov.eclipse.assistai.mcp.operations.Operation;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.OperationContext;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.ProcessOutputSource;
import com.github.gradusnikov.eclipse.assistai.mcp.results.Diagnostic;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.MavenBuildResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.MavenDependenciesResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.MavenProjectListResponse;

import jakarta.inject.Inject;

@SuppressWarnings( "restriction" ) // For using M2E internal classes
@Creatable
public class MavenService
{
    /**
     * m2e's Maven nature. Hardcoded rather than taken from IMavenConstants, which lives in
     * an internal package.
     */
    private static final String MAVEN_NATURE_ID = "org.eclipse.m2e.core.maven2Nature";


    @Inject
    ILog           logger;

    @Inject
    UISynchronize  sync;

    /**
     * Backstop for a build run as an MCP operation. Its caller has already been handed
     * an operationId by then, so this only stops a hung build from holding the worker
     * thread forever.
     */
    private static final int MAX_BUILD_MINUTES = 180;

    /**
     * Runs a Maven build the way the IDE's Run As &gt; Maven build runs it: through
     * m2e's Maven launch configuration, in a separate JVM with the configured Maven
     * runtime, with the whole Maven log going to a console of its own.
     * <p>
     * The build used to run in-process through the m2e embedder, which had two
     * consequences a caller could not see past. Nothing of Maven's log reached the
     * console - only the few lines a listener of ours printed - so a failing compile
     * or test left no trace of why. And the goals string was split into "goals",
     * so {@code clean verify -DskipTests} failed with "Unknown lifecycle phase
     * -DskipTests": the very thing anyone who has typed a Maven command line writes.
     * The launcher takes the goals field verbatim, as the IDE's own dialog does.
     *
     * @param projectName the Eclipse project, or - when no project has that name - the
     *            Maven artifactId or groupId:artifactId of one m2e knows
     * @param goals everything after {@code mvn}: phases, goals and options alike
     * @param profiles comma-separated profiles to activate, or null
     * @param properties comma-separated {@code key=value} pairs, or null
     * @param pomDirectory project-relative directory holding the pom to build, or null
     *            for the project root
     * @param timeout seconds to wait when called outside an MCP operation; inside one
     *            the framework owns the wait and this method waits for the build itself
     */
    public MavenBuildResponse runMavenBuild( String projectName, String goals, String profiles, String properties,
                                             String pomDirectory, boolean offline, boolean updateSnapshots,
                                             boolean skipTests, boolean debugOutput, Integer timeout )
    {
        Objects.requireNonNull( projectName, "Project name cannot be null" );
        Objects.requireNonNull( goals, "Maven goals cannot be null" );
        long started = System.currentTimeMillis();

        String arguments = mavenArguments( goals );
        if ( projectName.isBlank() )
        {
            return MavenBuildResponse.notStarted( projectName, null, null,
                    Diagnostic.fatal( DiagnosticCode.VALIDATION_ERROR, "Project name cannot be empty." ),
                    elapsed( started ) );
        }
        if ( arguments.isEmpty() )
        {
            return MavenBuildResponse.notStarted( projectName, null, null,
                    Diagnostic.fatal( DiagnosticCode.VALIDATION_ERROR,
                            "No Maven goals given. Pass what you would type after 'mvn', e.g. 'clean verify -DskipTests'." ),
                    elapsed( started ) );
        }

        List<String> profileList = parseProfiles( profiles );
        List<String> propertyList;
        try
        {
            propertyList = parseProperties( properties );
        }
        catch ( IllegalArgumentException e )
        {
            return MavenBuildResponse.notStarted( projectName, null, null,
                    Diagnostic.fatal( DiagnosticCode.VALIDATION_ERROR, e.getMessage() ), elapsed( started ) );
        }
        String mavenCommand = describeCommand( arguments, profileList, propertyList, offline, updateSnapshots,
                skipTests, debugOutput );

        IProject project = resolveProject( projectName );
        if ( project == null )
        {
            return MavenBuildResponse.notStarted( projectName, null, mavenCommand,
                    Diagnostic.fatal( DiagnosticCode.PROJECT_NOT_FOUND,
                            "No project named '" + projectName + "' in the workspace, and no Maven project with that artifactId. "
                                    + "Known Maven projects: " + knownMavenProjects() + "." ),
                    elapsed( started ) );
        }
        if ( !project.isOpen() )
        {
            return MavenBuildResponse.notStarted( project.getName(), null, mavenCommand,
                    Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_ACCESSIBLE,
                            "Project '" + project.getName() + "' is closed." ),
                    elapsed( started ) );
        }

        IContainer pomDir = pomDirectory == null || pomDirectory.isBlank()
                ? project
                : project.getFolder( IPath.fromOSString( pomDirectory ) );
        String pomDirPath = pomDir.getFullPath().toString();
        if ( !pomDir.exists() || !pomDir.getFile( IPath.fromOSString( "pom.xml" ) ).exists() )
        {
            return MavenBuildResponse.notStarted( project.getName(), pomDirPath, mavenCommand,
                    Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_FOUND,
                            "There is no pom.xml in " + pomDirPath + "." ),
                    elapsed( started ) );
        }

        ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
        ILaunchConfigurationType type = launchManager.getLaunchConfigurationType(
                MavenLaunchConstants.LAUNCH_CONFIGURATION_TYPE_ID );
        if ( type == null )
        {
            return MavenBuildResponse.notStarted( project.getName(), pomDirPath, mavenCommand,
                    Diagnostic.fatal( DiagnosticCode.MAVEN_LAUNCH_TYPE_MISSING,
                            "m2e's Maven launch support (org.eclipse.m2e.launching) is not installed." ),
                    elapsed( started ) );
        }

        String launchName = null;
        OutputCollector output = new OutputCollector();
        try
        {
            ILaunchConfiguration configuration = mavenLaunchConfiguration( launchManager, type, pomDir, arguments,
                    profileList, propertyList, offline, updateSnapshots, skipTests, debugOutput );
            launchName = configuration.getName();
            String consoleHint = launchName;
            Optional<Operation> operation = OperationContext.current();
            operation.ifPresent( op -> op.setConsoleHint( consoleHint ) );

            AtomicReference<ILaunch> launched = new AtomicReference<>();
            AtomicReference<CoreException> refused = new AtomicReference<>();
            sync.syncExec( () -> {
                try
                {
                    launched.set( configuration.launch( ILaunchManager.RUN_MODE, new NullProgressMonitor() ) );
                }
                catch ( CoreException e )
                {
                    refused.set( e );
                }
            } );
            ILaunch launch = launched.get();
            if ( launch == null )
            {
                String reason = refused.get() == null ? "see the error log for the cause"
                        : ExceptionUtils.getRootCauseMessage( refused.get() );
                logger.error( "Maven launch '" + launchName + "' failed to start", refused.get() );
                return MavenBuildResponse.notStarted( project.getName(), pomDirPath, mavenCommand,
                        Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                                "Eclipse could not launch '" + launchName + "': " + reason ),
                        elapsed( started ) );
            }

            for ( IProcess process : launch.getProcesses() )
            {
                output.attach( process );
            }
            // Streams the log into the operation and makes cancelling it terminate the JVM.
            operation.ifPresent( op -> ProcessOutputSource.attach( op, launch ) );

            // Run as an MCP operation, the caller has already been handed an operationId
            // and the only bound left is a backstop. Called directly - from a test, an
            // agent - the caller's timeout is still the bound.
            long boundMillis = operation.isPresent() || timeout == null || timeout <= 0
                    ? TimeUnit.MINUTES.toMillis( MAX_BUILD_MINUTES )
                    : TimeUnit.SECONDS.toMillis( timeout );
            boolean terminated;
            try
            {
                terminated = awaitTermination( launch, boundMillis );
            }
            catch ( InterruptedException e )
            {
                // cancelOperation interrupts this thread; the launch itself is terminated
                // by the operation's cancel hook.
                Thread.currentThread().interrupt();
                return MavenBuildResponse.cancelled( project.getName(), pomDirPath, launchName, mavenCommand,
                        elapsed( started ), output.errorLines(), output.errorLinesTruncated(), output.tail() );
            }

            if ( !terminated )
            {
                return MavenBuildResponse.running( project.getName(), pomDirPath, launchName, mavenCommand,
                        elapsed( started ), output.errorLines(), output.errorLinesTruncated(), output.tail() );
            }

            // What the build wrote under target/ is on disk; let the workspace see it.
            refreshQuietly( project );
            return MavenBuildResponse.finished( project.getName(), pomDirPath, launchName, mavenCommand,
                    exitCode( launch ), output.reportedSuccess(), elapsed( started ), output.errorLines(),
                    output.errorLinesTruncated(), output.tail() );
        }
        catch ( CoreException e )
        {
            logger.error( "Error running Maven build", e );
            return MavenBuildResponse.notStarted( project.getName(), pomDirPath, mavenCommand,
                    Diagnostic.fatal( DiagnosticCode.INTERNAL_ERROR,
                            "Error running Maven build: " + ExceptionUtils.getRootCauseMessage( e ) ),
                    elapsed( started ) );
        }
    }

    /**
     * The goals field as Maven will see it: whitespace collapsed, and a leading
     * {@code mvn} or {@code mvnw} dropped, because a caller who types the whole
     * command line has said what they mean.
     */
    static String mavenArguments( String goals )
    {
        String arguments = goals.trim().replaceAll( "\\s+", " " );
        while ( true )
        {
            int space = arguments.indexOf( ' ' );
            String first = space < 0 ? arguments : arguments.substring( 0, space );
            String tool = first.replace( '\\', '/' );
            tool = tool.substring( tool.lastIndexOf( '/' ) + 1 ).toLowerCase( Locale.ROOT );
            if ( !tool.equals( "mvn" ) && !tool.equals( "mvn.cmd" ) && !tool.equals( "mvnw" ) && !tool.equals( "mvnw.cmd" ) )
            {
                return arguments;
            }
            arguments = space < 0 ? "" : arguments.substring( space + 1 );
        }
    }

    /**
     * {@code key=value} pairs for the launcher's properties list, each of which it
     * turns into {@code -Dkey=value}.
     */
    static List<String> parseProperties( String properties )
    {
        List<String> list = new ArrayList<>();
        if ( properties == null || properties.isBlank() )
        {
            return list;
        }
        for ( String entry : properties.split( "[,\\n]" ) )
        {
            String pair = entry.trim();
            if ( pair.isEmpty() )
            {
                continue;
            }
            if ( pair.startsWith( "-D" ) )
            {
                pair = pair.substring( 2 );
            }
            int equals = pair.indexOf( '=' );
            if ( equals < 1 )
            {
                throw new IllegalArgumentException( "Property '" + pair
                        + "' is not of the form key=value. Give properties as 'key=value,key2=value2', or put them in goals as -Dkey=value." );
            }
            list.add( pair );
        }
        return list;
    }

    /** The command line in {@code mvn} syntax, in the order the launcher assembles it. */
    private static String describeCommand( String arguments, List<String> profiles, List<String> properties,
                                           boolean offline, boolean updateSnapshots, boolean skipTests, boolean debugOutput )
    {
        StringBuilder command = new StringBuilder( "mvn -B" );
        if ( debugOutput )
        {
            command.append( " -X -e" );
        }
        if ( offline )
        {
            command.append( " -o" );
        }
        if ( updateSnapshots )
        {
            command.append( " -U" );
        }
        if ( skipTests )
        {
            command.append( " -Dmaven.test.skip=true -DskipTests" );
        }
        for ( String property : properties )
        {
            command.append( " -D" ).append( property );
        }
        if ( !profiles.isEmpty() )
        {
            command.append( " -P" ).append( String.join( ",", profiles ) );
        }
        return command.append( ' ' ).append( arguments ).toString();
    }

    /**
     * The project a caller named: by its Eclipse name, or failing that by the Maven
     * artifactId or groupId:artifactId of a project m2e knows. The two are frequently
     * different strings, and a caller reading a pom sees only the Maven ones.
     */
    private IProject resolveProject( String name )
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject( name );
        if ( project.exists() )
        {
            return project;
        }
        for ( IMavenProjectFacade facade : MavenPlugin.getMavenProjectRegistry().getProjects() )
        {
            String artifactId = facade.getArtifactKey().artifactId();
            String coordinates = facade.getArtifactKey().groupId() + ":" + artifactId;
            if ( name.equalsIgnoreCase( artifactId ) || name.equalsIgnoreCase( coordinates ) )
            {
                return facade.getProject();
            }
        }
        for ( IProject candidate : ResourcesPlugin.getWorkspace().getRoot().getProjects() )
        {
            if ( candidate.getName().equalsIgnoreCase( name ) )
            {
                return candidate;
            }
        }
        return null;
    }

    private static String knownMavenProjects()
    {
        List<String> names = new ArrayList<>();
        for ( IMavenProjectFacade facade : MavenPlugin.getMavenProjectRegistry().getProjects() )
        {
            names.add( facade.getProject().getName() + " (" + facade.getArtifactKey().groupId() + ":"
                    + facade.getArtifactKey().artifactId() + ")" );
        }
        return names.isEmpty() ? "none" : String.join( ", ", names );
    }

    /**
     * The launch configuration the IDE would use for this pom directory and goals -
     * an existing one when there is one, as Run As &gt; Maven build reuses its own -
     * with the options this call asked for written into it and saved, so it can be
     * rerun from the Run Configurations dialog.
     */
    private ILaunchConfiguration mavenLaunchConfiguration( ILaunchManager launchManager, ILaunchConfigurationType type,
                                                           IContainer pomDir, String arguments, List<String> profiles,
                                                           List<String> properties, boolean offline, boolean updateSnapshots,
                                                           boolean skipTests, boolean debugOutput ) throws CoreException
    {
        String pomDirLocation = pomDir.getLocation().toOSString();

        ILaunchConfigurationWorkingCopy workingCopy = null;
        for ( ILaunchConfiguration existing : launchManager.getLaunchConfigurations( type ) )
        {
            if ( pomDirLocation.equals( existing.getAttribute( MavenLaunchConstants.ATTR_POM_DIR, "" ) )
                    && arguments.equals( existing.getAttribute( MavenLaunchConstants.ATTR_GOALS, "" ) ) )
            {
                workingCopy = existing.getWorkingCopy();
                break;
            }
        }
        if ( workingCopy == null )
        {
            String name = launchManager.generateLaunchConfigurationName( pomDir.getName() + " [" + arguments + "]" );
            workingCopy = type.newInstance( null, name );
        }

        workingCopy.setAttribute( MavenLaunchConstants.ATTR_POM_DIR, pomDirLocation );
        workingCopy.setAttribute( MavenLaunchConstants.ATTR_GOALS, arguments );
        workingCopy.setAttribute( MavenLaunchConstants.ATTR_PROFILES, String.join( ",", profiles ) );
        workingCopy.setAttribute( MavenLaunchConstants.ATTR_PROPERTIES, properties );
        workingCopy.setAttribute( MavenLaunchConstants.ATTR_OFFLINE, offline );
        workingCopy.setAttribute( MavenLaunchConstants.ATTR_UPDATE_SNAPSHOTS, updateSnapshots );
        workingCopy.setAttribute( MavenLaunchConstants.ATTR_SKIP_TESTS, skipTests );
        workingCopy.setAttribute( MavenLaunchConstants.ATTR_DEBUG_OUTPUT, debugOutput );
        // Batch mode: no progress bars in the log. No colour: the log is read by a
        // program, and ANSI escapes would sit inside every [ERROR] line it looks for.
        workingCopy.setAttribute( MavenLaunchConstants.ATTR_BATCH, true );
        workingCopy.setAttribute( MavenLaunchConstants.ATTR_COLOR, MavenLaunchConstants.ATTR_COLOR_VALUE_NEVER );
        return workingCopy.doSave();
    }

    private static boolean awaitTermination( ILaunch launch, long boundMillis ) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + boundMillis;
        while ( !launch.isTerminated() && System.currentTimeMillis() < deadline )
        {
            Thread.sleep( 200 );
        }
        return launch.isTerminated();
    }

    /**
     * The exit value of the launch's process, or null when there is none to report.
     * Null rather than a sentinel: {@code -1} is an ordinary exit code.
     */
    private static Integer exitCode( ILaunch launch )
    {
        for ( IProcess process : launch.getProcesses() )
        {
            if ( !process.isTerminated() )
            {
                continue;
            }
            try
            {
                return process.getExitValue();
            }
            catch ( DebugException e )
            {
                return null;
            }
        }
        return null;
    }

    private void refreshQuietly( IProject project )
    {
        try
        {
            project.refreshLocal( IResource.DEPTH_INFINITE, new NullProgressMonitor() );
        }
        catch ( CoreException e )
        {
            logger.warn( "Could not refresh " + project.getName() + " after the Maven build", e );
        }
    }

    private static long elapsed( long startMillis )
    {
        return System.currentTimeMillis() - startMillis;
    }

    /**
     * Reads the build's two streams as lines, keeping what a caller acts on: every
     * distinct {@code [ERROR]} line, the tail where Maven prints its verdict, and the
     * verdict itself. The whole log stays in the console; holding it here as well
     * would make a large build's response as large as the build.
     */
    static final class OutputCollector
    {
        private final StringBuilder pending = new StringBuilder();
        private final Deque<String> tail = new ArrayDeque<>();
        private final List<String> errors = new ArrayList<>();
        private final Set<String> seenErrors = new HashSet<>();
        private int totalLines;
        private boolean errorsTruncated;
        private boolean reportedSuccess;

        void attach( IProcess process )
        {
            IStreamsProxy proxy = process.getStreamsProxy();
            if ( proxy == null )
            {
                return;
            }
            listen( proxy.getOutputStreamMonitor() );
            listen( proxy.getErrorStreamMonitor() );
        }

        private void listen( IStreamMonitor monitor )
        {
            if ( monitor == null )
            {
                return;
            }
            // Seed and subscribe under the monitor's lock, so output arriving between
            // the two is neither lost nor duplicated.
            synchronized ( monitor )
            {
                append( monitor.getContents() );
                monitor.addListener( ( text, source ) -> append( text ) );
            }
        }

        synchronized void append( String text )
        {
            if ( text == null || text.isEmpty() )
            {
                return;
            }
            pending.append( text );
            int newline;
            while ( ( newline = indexOfNewline( pending ) ) >= 0 )
            {
                String line = pending.substring( 0, newline );
                int skip = pending.charAt( newline ) == '\r' && newline + 1 < pending.length()
                        && pending.charAt( newline + 1 ) == '\n' ? 2 : 1;
                pending.delete( 0, newline + skip );
                addLine( line );
            }
        }

        private static int indexOfNewline( StringBuilder text )
        {
            for ( int i = 0; i < text.length(); i++ )
            {
                char c = text.charAt( i );
                if ( c == '\n' || c == '\r' )
                {
                    // A lone '\r' at the very end may be half of a CRLF; wait for the rest.
                    return c == '\r' && i == text.length() - 1 ? -1 : i;
                }
            }
            return -1;
        }

        private void addLine( String line )
        {
            totalLines++;
            tail.addLast( line );
            if ( tail.size() > MavenBuildResponse.OutputTail.MAX_LINES )
            {
                tail.removeFirst();
            }
            String trimmed = line.trim();
            if ( trimmed.startsWith( "[ERROR]" ) )
            {
                String body = trimmed.substring( "[ERROR]".length() ).trim();
                if ( !body.isEmpty() && seenErrors.add( body ) )
                {
                    if ( errors.size() < MavenBuildResponse.MAX_ERROR_LINES )
                    {
                        errors.add( body );
                    }
                    else
                    {
                        errorsTruncated = true;
                    }
                }
            }
            else if ( trimmed.contains( "BUILD SUCCESS" ) )
            {
                reportedSuccess = true;
            }
        }

        private void flush()
        {
            if ( pending.length() > 0 )
            {
                addLine( pending.toString() );
                pending.setLength( 0 );
            }
        }

        synchronized List<String> errorLines()
        {
            flush();
            return List.copyOf( errors );
        }

        synchronized boolean errorLinesTruncated()
        {
            return errorsTruncated;
        }

        synchronized boolean reportedSuccess()
        {
            flush();
            return reportedSuccess;
        }

        synchronized MavenBuildResponse.OutputTail tail()
        {
            flush();
            return new MavenBuildResponse.OutputTail( String.join( "\n", tail ), totalLines, totalLines > tail.size() );
        }
    }

    /**
     * The headless equivalent of the IDE's "Maven > Update Project" action: re-reads the
     * pom, re-resolves dependencies and reconfigures the project's classpath and facets.
     * <p>
     * This is what a pom edit needs before the workspace reflects it - without it, a newly
     * added dependency is simply not on the classpath and everything that uses it still
     * fails to compile.
     *
     * @param projectName            the Maven project to update
     * @param forceDependencyUpdate  re-resolve SNAPSHOT and release dependencies even when
     *                               they are already cached (the "Force Update of
     *                               Snapshots/Releases" checkbox)
     * @param offline                work offline, resolving only from the local repository
     */
    public String updateMavenProject( String projectName, boolean forceDependencyUpdate, boolean offline )
    {
        Objects.requireNonNull( projectName, "Project name cannot be null" );

        if ( projectName.isEmpty() )
        {
            throw new RuntimeException( "Error: Project name cannot be empty." );
        }

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject( projectName );
        if ( !project.exists() )
        {
            throw new RuntimeException( "Error: Project '" + projectName + "' does not exist." );
        }
        if ( !project.isOpen() )
        {
            throw new RuntimeException( "Error: Project '" + projectName + "' is closed." );
        }

        try
        {
            if ( !project.hasNature( MAVEN_NATURE_ID ) )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' is not a Maven project." );
            }

            // Cancellable, unlike a NullProgressMonitor: resolving dependencies can reach
            // the network and take a while, so the caller must be able to abort it.
            IProgressMonitor monitor = OperationContext.current()
                                                       .map( Operation::monitor )
                                                       .map( IProgressMonitor.class::cast )
                                                       .orElseGet( NullProgressMonitor::new );

            MavenUpdateRequest request = new MavenUpdateRequest( project, offline, forceDependencyUpdate );
            MavenPlugin.getProjectConfigurationManager().updateProjectConfiguration( request, monitor );

            StringBuilder sb = new StringBuilder();
            sb.append( "Updated Maven project '" ).append( projectName ).append( "'." );
            if ( forceDependencyUpdate )
            {
                sb.append( " Dependencies were re-resolved." );
            }
            if ( offline )
            {
                sb.append( " Resolved offline, from the local repository only." );
            }
            sb.append( "\n\nUse getCompilationErrors to check the project after the update." );
            return sb.toString();
        }
        catch ( CoreException e )
        {
            logger.error( "Error updating Maven project " + projectName, e );
            throw new RuntimeException( "Error updating Maven project '" + projectName + "': " + e.getMessage(), e );
        }
    }

    /**
     * Gets the effective POM for a Maven project.
     * 
     * @param projectName
     *            The name of the Maven project
     * @return The effective POM as XML
     */
    public String getEffectivePom( String projectName )
    {
        Objects.requireNonNull( projectName, "Project name cannot be null" );

        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: Project name cannot be empty." );
        }

        try
        {
            // Get the project
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject( projectName );

            if ( !project.exists() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' does not exist." );
            }

            if ( !project.isOpen() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' is closed." );
            }

            // Check if it's a Maven project
            if ( !project.hasNature( IMavenConstants.NATURE_ID ) )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' is not a Maven project." );
            }

            // Get Maven project facade
            IMavenProjectRegistry projectRegistry = MavenPlugin.getMavenProjectRegistry();
            IMavenProjectFacade facade = projectRegistry.getProject( project );

            if ( facade == null )
            {
                throw new RuntimeException( "Error: Could not find Maven configuration for project '" + projectName + "'." );
            }

            // Get the effective POM
            final IMaven maven = MavenPlugin.getMaven();
            final IMavenExecutionContext context = maven.createExecutionContext();

            try
            {
                // Use ICallable to get the effective POM XML
                String effectivePom = context.execute( new ICallable<String>()
                {
                    @Override
                    public String call( IMavenExecutionContext context, IProgressMonitor monitor ) throws CoreException
                    {
                        try
                        {
                            // Read the project using the available API
                            MavenProject mavenProject = maven.readProject( facade.getPomFile(), monitor );

                            // Convert the model to XML
                            try (ByteArrayOutputStream out = new ByteArrayOutputStream())
                            {
                                maven.writeModel( mavenProject.getModel(), out );
                                return out.toString();
                            }
                        }
                        catch ( Exception e )
                        {
                            throw new CoreException( org.eclipse.core.runtime.Status.error( "Failed to get effective POM", e ) );
                        }
                    }
                }, new NullProgressMonitor() );

                return effectivePom;
            }
            catch ( CoreException e )
            {
                throw new RuntimeException( "Failed to get effective POM: " + e.getMessage(), e );
            }

        }
        catch ( CoreException e )
        {
            throw new RuntimeException( "Error retrieving effective POM: " + e.getMessage(), e );
        }
    }

    /**
     * The Maven projects m2e knows about in this workspace.
     * <p>
     * Both names are reported: the Eclipse {@code projectName} every other tool takes,
     * and the Maven coordinates a build command takes. They are frequently different
     * strings, and the coordinates used to arrive as {@code ": "}-separated text inside
     * an indented bullet.
     *
     * @return the projects, or an empty list with a count of zero
     */
    public MavenProjectListResponse listMavenProjects()
    {
        try
        {
            IMavenProjectRegistry projectRegistry = MavenPlugin.getMavenProjectRegistry();
            List<MavenProjectListResponse.MavenProject> projects = new ArrayList<>();

            for ( IMavenProjectFacade facade : projectRegistry.getProjects() )
            {
                IProject project = facade.getProject();
                projects.add( new MavenProjectListResponse.MavenProject(
                        project.getName(),
                        facade.getArtifactKey().groupId(),
                        facade.getArtifactKey().artifactId(),
                        facade.getArtifactKey().version(),
                        facade.getPackaging() ) );
            }

            return MavenProjectListResponse.of( projects );
        }
        catch ( Exception e )
        {
            throw new RuntimeException( "Error listing Maven projects: " + e.getMessage(), e );
        }
    }

    /**
     * The dependencies one project's pom declares.
     * <p>
     * These come from the Maven project model - what the pom declares after
     * inheritance - not from the resolved transitive graph. That is a property of the
     * tool and belongs in its description, which is why it is no longer a {@code Note:}
     * line inside the answer.
     * <p>
     * A dependency whose version comes from a parent's {@code dependencyManagement}
     * reports a null {@code version}, where it used to print the literal text
     * {@code null}.
     * 
     * @param projectName
     *            The name of the Maven project
     * @return the declared dependencies, or an empty list with a count of zero
     */
    public MavenDependenciesResponse getProjectDependencies( String projectName )
    {
        Objects.requireNonNull( projectName, "Project name cannot be null" );

        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: Project name cannot be empty." );
        }

        try
        {
            // Get the project
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject( projectName );

            if ( !project.exists() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' does not exist." );
            }

            if ( !project.isOpen() )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' is closed." );
            }

            // Check if it's a Maven project
            if ( !project.hasNature( IMavenConstants.NATURE_ID ) )
            {
                throw new RuntimeException( "Error: Project '" + projectName + "' is not a Maven project." );
            }

            // Get Maven project facade
            IMavenProjectRegistry projectRegistry = MavenPlugin.getMavenProjectRegistry();
            IMavenProjectFacade facade = projectRegistry.getProject( project );

            if ( facade == null )
            {
                throw new RuntimeException( "Error: Could not find Maven configuration for project '" + projectName + "'." );
            }

            final List<MavenDependenciesResponse.MavenDependency> dependencies = new ArrayList<>();

            final IMaven maven = MavenPlugin.getMaven();
            final IMavenExecutionContext context = maven.createExecutionContext();

            try
            {
                // Execute in Maven context to get dependencies
                context.execute( new ICallable<Void>()
                {
                    @Override
                    public Void call( IMavenExecutionContext context, IProgressMonitor monitor ) throws CoreException
                    {
                        try
                        {
                            // Read the project using the available API
                            MavenProject mavenProject = maven.readProject( facade.getPomFile(), monitor );

                            for ( Dependency dependency : mavenProject.getModel().getDependencies() )
                            {
                                dependencies.add( new MavenDependenciesResponse.MavenDependency(
                                        dependency.getGroupId(),
                                        dependency.getArtifactId(),
                                        // Null, not "null": the version is managed elsewhere.
                                        dependency.getVersion(),
                                        // Maven's own default, applied here so a caller
                                        // comparing scopes does not have to know the rule.
                                        dependency.getScope() == null ? "compile" : dependency.getScope() ) );
                            }

                            return null;
                        }
                        catch ( Exception e )
                        {
                            throw new CoreException( org.eclipse.core.runtime.Status.error( "Error analyzing dependencies", e ) );
                        }
                    }
                }, new NullProgressMonitor() );

                return MavenDependenciesResponse.of( projectName, dependencies );

            }
            catch ( CoreException e )
            {
                throw new RuntimeException( "Error retrieving project dependencies: " + e.getMessage(), e );
            }
        }
        catch ( CoreException e )
        {
            throw new RuntimeException( "Error retrieving project dependencies: " + e.getMessage(), e );
        }
    }

    /**
     * Parses Maven profiles from a comma-separated string.
     */
    private List<String> parseProfiles( String profiles )
    {
        List<String> profileList = new ArrayList<>();
        if ( profiles != null && !profiles.trim().isEmpty() )
        {
            for ( String profile : profiles.split( "," ) )
            {
                if ( !profile.trim().isEmpty() )
                {
                    profileList.add( profile.trim() );
                }
            }
        }
        return profileList;
    }
}
