package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.project.MavenProject;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.embedder.ICallable;
import org.eclipse.m2e.core.embedder.IMaven;
import org.eclipse.m2e.core.embedder.IMavenExecutionContext;
import org.eclipse.m2e.core.internal.IMavenConstants;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.IMavenProjectRegistry;

import jakarta.inject.Inject;

@SuppressWarnings( "restriction" ) // For using M2E internal classes
@Creatable
public class MavenService
{

    @Inject
    ILog           logger;

    @Inject
    UISynchronize  sync;

    @Inject
    ConsoleService consoleService;

    /**
     * Runs a Maven build with the specified goals on a project.
     * 
     * @param projectName
     *            The name of the project to build
     * @param goals
     *            The Maven goals to execute (e.g., "clean install")
     * @param profiles
     *            Optional Maven profiles to activate
     * @param timeout
     *            Maximum time in seconds to wait for build completion (0 for no
     *            timeout)
     * @return A status message indicating the build has started
     */
    public String runMavenBuild( String projectName, String goals, String profiles, Integer timeout )
    {
        Objects.requireNonNull( projectName, "Project name cannot be null" );
        Objects.requireNonNull( goals, "Maven goals cannot be null" );

        if ( projectName.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: Project name cannot be empty." );
        }

        if ( goals.isEmpty() )
        {
            throw new IllegalArgumentException( "Error: Maven goals cannot be empty." );
        }

        // Set default timeout if not specified
        if ( timeout == null || timeout < 0 )
        {
            timeout = 0; // No timeout by default
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

            // Parse goals
            final List<String> goalList = parseGoals( goals );

            // Parse profiles
            final List<String> profileList = parseProfiles( profiles );

            // Get Maven project facade
            IMavenProjectRegistry projectRegistry = MavenPlugin.getMavenProjectRegistry();
            IMavenProjectFacade facade = projectRegistry.getProject( project );

            if ( facade == null )
            {
                throw new RuntimeException( "Error: Could not find Maven configuration for project '" + projectName + "'." );
            }

            // Start the build in a separate thread
            final String consoleOutput = "Maven Console";

            // Clear console before starting the build
            sync.syncExec( () -> {
                consoleService.clear( consoleOutput );
            } );
            Job job = new Job( "Maven Build: " + goals + " on " + projectName )
            {
                @Override
                protected org.eclipse.core.runtime.IStatus run( IProgressMonitor monitor )
                {
                    try
                    {
                        executeMavenBuild( facade, goalList, profileList, monitor );
                        return org.eclipse.core.runtime.Status.OK_STATUS;
                    }
                    catch ( Exception e )
                    {
                        logger.error( "Error executing Maven build", e );
                        return org.eclipse.core.runtime.Status.error( "Error executing Maven build", e );
                    }
                }
            };

            job.schedule();
            job.join( TimeUnit.MINUTES.toMillis( timeout ), new NullProgressMonitor() );

            // If timeout is specified, wait for completion
            String timeoutMessage = "";

            // Return a message with instructions on how to get the console
            // output
            return "Maven build started for project '" + projectName + "' with goals: " + goals
                    + ( profiles != null && !profiles.isEmpty() ? " and profiles: " + profiles : "" ) + timeoutMessage
                    + "\n\nTo view build output, use the getConsoleOutput tool with consoleName=\"Maven Console\""
                    + "\nExample: getConsoleOutput(consoleName=\"Maven Console\", maxLines=200)";

        }
        catch ( CoreException | InterruptedException e )
        {
            throw new RuntimeException( "Error starting Maven build: " + e.getMessage(), e );
        }
    }

    /**
     * Executes a Maven build with the specified goals and profiles.
     */
    private void executeMavenBuild( IMavenProjectFacade facade, List<String> goals, List<String> profiles, IProgressMonitor monitor ) throws CoreException
    {
        if ( monitor == null )
        {
            monitor = new NullProgressMonitor();
        }
        // Use M2E's execution context to run the build
        IMaven maven = MavenPlugin.getMaven();
        IMavenExecutionContext context = maven.createExecutionContext();

        // Configure the execution context with the resolver configuration
        context.getExecutionRequest().setActiveProfiles( profiles );
        
        MavenExecutionRequest request = context.getExecutionRequest();
        request.setActiveProfiles( profiles );
        request.setGoals( goals );
        
        // Create a custom execution listener that forwards output to the console
        final String consoleOutput = "Maven Console";
        CustomMavenExecutionListener listener = new CustomMavenExecutionListener(monitor, consoleOutput, consoleService );
        request.setExecutionListener( listener );
        
        request.setBaseDirectory( facade.getMavenProject().getBasedir() );
        request.setPom( facade.getPomFile() );
        MavenExecutionResult result = context.execute( request );

        if ( !result.getExceptions().isEmpty() )
        {
            // Write exception to console
            final String errorMessage = "Maven build failed: " + result.getExceptions().getFirst().getMessage();
            sync.asyncExec(() -> consoleService.println(consoleOutput, errorMessage));
            throw new RuntimeException( result.getExceptions().getFirst() );
        }
        
        // Update Maven project configuration if needed
        MavenPlugin.getProjectConfigurationManager().updateProjectConfiguration( facade.getProject(), monitor );
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
     * Lists all available Maven projects in the workspace.
     * 
     * @return A list of Maven projects with their details
     */
    public String listMavenProjects()
    {
        try
        {
            IMavenProjectRegistry projectRegistry = MavenPlugin.getMavenProjectRegistry();
            List<IMavenProjectFacade> facades = projectRegistry.getProjects();

            if ( facades.isEmpty() )
            {
                return "No Maven projects found in the workspace.";
            }

            StringBuilder result = new StringBuilder();
            result.append( "Maven projects in the workspace:\n\n" );

            for ( IMavenProjectFacade facade : facades )
            {
                IProject project = facade.getProject();
                String groupId = facade.getArtifactKey().groupId();
                String artifactId = facade.getArtifactKey().artifactId();
                String version = facade.getArtifactKey().version();
                String packaging = facade.getPackaging();

                result.append( "- Project: " ).append( project.getName() ).append( "\n" );
                result.append( "  GroupId: " ).append( groupId ).append( "\n" );
                result.append( "  ArtifactId: " ).append( artifactId ).append( "\n" );
                result.append( "  Version: " ).append( version ).append( "\n" );
                result.append( "  Packaging: " ).append( packaging ).append( "\n" );
                result.append( "\n" );
            }

            return result.toString();

        }
        catch ( Exception e )
        {
            throw new RuntimeException( "Error listing Maven projects: " + e.getMessage(), e );
        }
    }

    /**
     * Gets Maven project dependencies.
     * 
     * @param projectName
     *            The name of the Maven project
     * @return A list of dependencies with their details
     */
    public String getProjectDependencies( String projectName )
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

            // Get dependencies
            final StringBuilder result = new StringBuilder();
            result.append( "Dependencies for project '" ).append( projectName ).append( "':\n\n" );

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

                            // Add note about dependency extraction method
                            result.append( "Note: Showing dependencies from the Maven project model.\n\n" );

                            // Get the dependencies from the model
                            List<org.apache.maven.model.Dependency> dependencies = mavenProject.getModel().getDependencies();

                            if ( dependencies.isEmpty() )
                            {
                                result.append( "No dependencies found in this project.\n" );
                            }
                            else
                            {
                                for ( org.apache.maven.model.Dependency dependency : dependencies )
                                {
                                    result.append( "- GroupId: " ).append( dependency.getGroupId() ).append( "\n" );
                                    result.append( "  ArtifactId: " ).append( dependency.getArtifactId() ).append( "\n" );
                                    result.append( "  Version: " ).append( dependency.getVersion() ).append( "\n" );
                                    result.append( "  Scope: " ).append( dependency.getScope() != null ? dependency.getScope() : "compile" ).append( "\n" );
                                    result.append( "\n" );
                                }
                            }

                            return null;
                        }
                        catch ( Exception e )
                        {
                            throw new CoreException( org.eclipse.core.runtime.Status.error( "Error analyzing dependencies", e ) );
                        }
                    }
                }, new NullProgressMonitor() );

                return result.toString();

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
     * Parses Maven goals from a space-separated string.
     */
    private List<String> parseGoals( String goals )
    {
        List<String> goalList = new ArrayList<>();
        if ( goals != null && !goals.trim().isEmpty() )
        {
            for ( String goal : goals.split( "\\s+" ) )
            {
                if ( !goal.trim().isEmpty() )
                {
                    goalList.add( goal.trim() );
                }
            }
        }
        return goalList;
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
