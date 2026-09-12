package com.github.gradusnikov.eclipse.plugin.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.internal.IMavenConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;

import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.MavenBuildResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.MavenBuildResponse.BuildStatus;
import com.github.gradusnikov.eclipse.assistai.mcp.results.MavenDependenciesResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.MavenProjectListResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.services.MavenService;

@SuppressWarnings("restriction")
public class MavenServicePDETest extends AbstractOperationPDETest
{
    private static final String TEST_PROJECT_NAME = "MavenServiceTestProject";
    private IProject project;
    private MavenService service;
    private NullProgressMonitor monitor = new NullProgressMonitor();
    
    @BeforeEach
    public void beforeEach() throws CoreException, IOException, InterruptedException {
        // Get workspace
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        
        // Delete the project if it exists
        project = root.getProject(TEST_PROJECT_NAME);
        if (project.exists()) {
            project.delete(true, true, monitor);
        }
        
        // Create a test project
        project = root.getProject(TEST_PROJECT_NAME);
        IProjectDescription desc = project.getWorkspace().newProjectDescription(project.getName());
        // Set Maven nature
        desc.setNatureIds(new String[] {JavaCore.NATURE_ID, IMavenConstants.NATURE_ID});
        project.create(desc, monitor);
        project.open(monitor);
        
        // Create Maven project structure
        createMavenProjectStructure();
        
        // Create pom.xml
        createPomXml();
        
        // Set up mock IMavenProjectRegistry and IMavenProjectFacade
        setupMocks();
        
        // Initialize service with DI context
        IEclipseContext context = EclipseContextFactory.create();
        
        // Create a mock ILog
        ILog log = new ILog() {
            @Override
            public void removeLogListener(ILogListener listener) {
            }
            
            @Override
            public void log(IStatus status) {
                System.out.println(status.getMessage());
                if (status.getException() != null) {
                    status.getException().printStackTrace();
                }
            }
            
            @Override
            public Bundle getBundle() {
                return null;
            }
            
            @Override
            public void addLogListener(ILogListener listener) {
            }
        };
        context.set(ILog.class, log);
        
        // Create and set mock UISynchronize
        UISynchronize uiSync = new UISynchronize() {
            @Override
            public void syncExec(Runnable runnable) {
                runnable.run();
            }
            
            @Override
            public void asyncExec(Runnable runnable) {
                runnable.run();
            }

            @Override
            protected boolean isUIThread(Thread thread) {
                return true;
            }

            @Override
            protected void showBusyWhile(Runnable runnable) {
                runnable.run();
            }

            @Override
            protected boolean dispatchEvents() {
                return false;
            }
        };
        context.set(UISynchronize.class, uiSync);
        
        
        // Create the service under test
        service = ContextInjectionFactory.make(MavenService.class, context);

        // Wire up operation registry using the OSGi service context so that
        // Operation-backed service calls have a proper context available.
        org.osgi.framework.BundleContext bundleContext =
            org.osgi.framework.FrameworkUtil.getBundle( MavenServicePDETest.class ).getBundleContext();
        org.eclipse.e4.core.contexts.IEclipseContext serviceContext =
            org.eclipse.e4.core.contexts.EclipseContextFactory.getServiceContext( bundleContext );
        initOperationRegistry( serviceContext );
    }
    
    /**
     * Set up mocks for M2E components that are difficult to initialize in tests
     */
    private void setupMocks() throws CoreException {
        // This is a workaround for testing - in a real environment, the M2E plugin would handle this
        try {
            // Add .project and .classpath files
            createFile(".project", createDotProjectContent());
            createFile(".classpath", createDotClasspathContent());
            
            // Force a refresh
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
            
            // Attempt to update the Maven configuration - this may or may not work in test environment
            try {
                MavenPlugin.getProjectConfigurationManager().updateProjectConfiguration(project, monitor);
            } catch (Exception e) {
                System.out.println("Note: Could not update Maven configuration: " + e.getMessage());
                // This is expected in test environment
            }
        } catch (Exception e) {
            System.out.println("Warning: Error setting up Maven project: " + e.getMessage());
            // Continue anyway - our tests will use mocked behavior
        }
    }
    
    @AfterEach
    public void afterEach() throws CoreException {
        // Clean up the test project
        if (project != null && project.exists()) {
            // A Maven launch leaves the auto-build and m2e's own jobs holding files under
            // target/ for a moment; on Windows that makes the first delete fail.
            try {
                Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, monitor);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            CoreException lastFailure = null;
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    project.delete(true, true, monitor);
                    return;
                } catch (CoreException e) {
                    lastFailure = e;
                    try { Thread.sleep(300); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
            throw lastFailure;
        }
    }
    
    @Test
    public void testUpdateMavenProject_ValidProject() {
        try {
            // Offline, so the test does not depend on the network.
            String result = service.updateMavenProject(TEST_PROJECT_NAME, false, true);

            assertTrue(result.contains("Updated Maven project"), result);
            assertTrue(result.contains(TEST_PROJECT_NAME), result);
        } catch (RuntimeException e) {
            String message = String.valueOf(e.getMessage());
            if (message.contains("is not a Maven project") || message.contains("Could not find Maven configuration")) {
                // m2e configuration is not always available in the test environment.
                assumeTrue(false, "Skipping: Maven configuration not available (" + message + ")");
            }
            throw e;
        }
    }

    @Test
    public void testUpdateMavenProject_InvalidProject() {
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            service.updateMavenProject("NonExistentProject", false, false);
        });

        assertTrue(exception.getMessage().contains("does not exist"), exception.getMessage());
    }

    // ---- runMavenBuild: the build runs through m2e's launcher, like Run As > Maven build ----

    /**
     * A Maven launch needs m2e's launcher and a JRE to run in. When the harness has
     * neither, that is a fact about the harness, not about the code under test.
     */
    private static void assumeLaunchable( MavenBuildResponse result )
    {
        if ( result.status() == BuildStatus.FAILED_TO_START )
        {
            DiagnosticCode code = result.diagnostics().get( 0 ).code();
            assumeTrue( code != DiagnosticCode.MAVEN_LAUNCH_TYPE_MISSING,
                    "Skipping: " + result.summaryText() );
        }
    }

    private MavenBuildResponse build( String goals, boolean offline )
    {
        return service.runMavenBuild( TEST_PROJECT_NAME, goals, null, null, null, offline, false, false, false, 300 );
    }

    @Test
    public void aBuildRunsThroughTheMavenLauncherAndReportsMavensOwnLog()
    {
        runWithOperationAndPrintConsoleOnProblems( "aBuildRunsThroughTheMavenLauncher", () -> {
            MavenBuildResponse result = build( "validate", true );
            assumeLaunchable( result );

            assertEquals( BuildStatus.SUCCESS, result.status(), () -> result.summaryText() + "\n" + result.output().text() );
            assertEquals( 0, result.exitCode() );
            assertEquals( TEST_PROJECT_NAME, result.projectName() );
            assertEquals( "/" + TEST_PROJECT_NAME, result.pomDirectory() );
            assertEquals( "mvn -B -o validate", result.mavenCommand() );
            // Maven's own log, not a summary of ours: the INFO lines and the verdict.
            assertTrue( result.output().text().contains( "[INFO] BUILD SUCCESS" ), result.output().text() );
            assertTrue( result.output().text().contains( "[INFO] Scanning for projects..." ), result.output().text() );
            assertTrue( result.errorLines().isEmpty(), result.errorLines().toString() );
            assertFalse( result.timedOut() );
            // The launch configuration is saved under the name the console carries.
            assertNotNull( result.launchName() );
            assertTrue( result.launchName().startsWith( TEST_PROJECT_NAME + " [validate]" ), result.launchName() );
            assertTrue( java.util.Arrays.stream( org.eclipse.debug.core.DebugPlugin.getDefault().getLaunchManager().getLaunchConfigurations() )
                    .anyMatch( c -> c.getName().equals( result.launchName() ) ), "the launch configuration was saved" );
        } );
    }

    @Test
    public void everythingAfterMvnReachesMavenUntouched()
    {
        runWithOperationAndPrintConsoleOnProblems( "everythingAfterMvnReachesMavenUntouched", () -> {
            // The way a command line is typed: a leading 'mvn', options among the goals.
            MavenBuildResponse result = build( "mvn  validate -Dsome.property=1 -N", true );
            assumeLaunchable( result );

            assertEquals( BuildStatus.SUCCESS, result.status(), () -> result.summaryText() + "\n" + result.output().text() );
            assertEquals( "mvn -B -o validate -Dsome.property=1 -N", result.mavenCommand() );
            assertTrue( result.launchName().contains( "[validate -Dsome.property=1 -N]" ), result.launchName() );
        } );
    }

    @Test
    public void aFailingBuildReportsItsExitCodeAndErrorLines()
    {
        runWithOperationAndPrintConsoleOnProblems( "aFailingBuildReportsItsExitCodeAndErrorLines", () -> {
            MavenBuildResponse result = build( "no-such-phase-xyz", true );
            assumeLaunchable( result );

            assertEquals( BuildStatus.FAILURE, result.status(), () -> result.summaryText() + "\n" + result.output().text() );
            assertEquals( 1, result.exitCode() );
            assertTrue( result.output().text().contains( "BUILD FAILURE" ), result.output().text() );
            assertFalse( result.errorLines().isEmpty(), "Maven's [ERROR] lines are reported as a list" );
            assertTrue( result.errorLines().stream().anyMatch( line -> line.contains( "no-such-phase-xyz" ) ),
                    result.errorLines().toString() );
            assertTrue( result.summaryText().startsWith( "BUILD FAILURE (exit code 1)" ), result.summaryText() );
            assertTrue( result.diagnostics().isEmpty(), "a failed build is a status, not a diagnostic" );
        } );
    }

    @Test
    public void profilesAndPropertiesBecomeMavenOptions()
    {
        runWithOperationAndPrintConsoleOnProblems( "profilesAndPropertiesBecomeMavenOptions", () -> {
            MavenBuildResponse result = service.runMavenBuild( TEST_PROJECT_NAME, "validate", "dev, test",
                    "env.check=1,-Dother=2", null, true, false, true, false, 300 );
            assumeLaunchable( result );

            assertEquals( BuildStatus.SUCCESS, result.status(), () -> result.summaryText() + "\n" + result.output().text() );
            assertEquals( "mvn -B -o -Dmaven.test.skip=true -DskipTests -Denv.check=1 -Dother=2 -Pdev,test validate",
                    result.mavenCommand() );
        } );
    }

    @Test
    public void aMavenArtifactIdNamesTheProjectToo()
    {
        assumeTrue( MavenPlugin.getMavenProjectRegistry().getProject( project ) != null,
                "Skipping: m2e has not registered the fixture project" );
        runWithOperationAndPrintConsoleOnProblems( "aMavenArtifactIdNamesTheProjectToo", () -> {
            MavenBuildResponse result = service.runMavenBuild( "maven-test-project", "validate", null, null, null,
                    true, false, false, false, 300 );
            assumeLaunchable( result );

            assertEquals( TEST_PROJECT_NAME, result.projectName(), "resolved to the Eclipse project" );
            assertEquals( BuildStatus.SUCCESS, result.status(), () -> result.summaryText() + "\n" + result.output().text() );
        } );
    }

    @Test
    public void aMissingProjectIsAFieldNotAnException()
    {
        MavenBuildResponse result = service.runMavenBuild( "NonExistentProject", "clean install", null, null, null,
                false, false, false, false, 10 );

        assertEquals( BuildStatus.FAILED_TO_START, result.status() );
        assertEquals( DiagnosticCode.PROJECT_NOT_FOUND, result.diagnostics().get( 0 ).code() );
        assertTrue( result.summaryText().contains( "NonExistentProject" ), result.summaryText() );
        assertNull( result.exitCode() );
    }

    @Test
    public void emptyGoalsAreRefusedBeforeAnythingIsLaunched()
    {
        for ( String goals : new String[] { "", "   ", "mvn", "./mvnw" } )
        {
            MavenBuildResponse result = service.runMavenBuild( TEST_PROJECT_NAME, goals, null, null, null,
                    false, false, false, false, 10 );
            assertEquals( BuildStatus.FAILED_TO_START, result.status(), goals );
            assertEquals( DiagnosticCode.VALIDATION_ERROR, result.diagnostics().get( 0 ).code(), goals );
        }
        assertThrows( NullPointerException.class,
                () -> service.runMavenBuild( TEST_PROJECT_NAME, null, null, null, null, false, false, false, false, 10 ) );
    }

    @Test
    public void aMalformedPropertyIsRefused()
    {
        MavenBuildResponse result = service.runMavenBuild( TEST_PROJECT_NAME, "validate", null, "novalue", null,
                false, false, false, false, 10 );

        assertEquals( BuildStatus.FAILED_TO_START, result.status() );
        assertEquals( DiagnosticCode.VALIDATION_ERROR, result.diagnostics().get( 0 ).code() );
        assertTrue( result.summaryText().contains( "novalue" ), result.summaryText() );
    }

    @Test
    public void aDirectoryWithoutAPomIsRefused()
    {
        MavenBuildResponse result = service.runMavenBuild( TEST_PROJECT_NAME, "validate", null, null, "src/main/java",
                false, false, false, false, 10 );

        assertEquals( BuildStatus.FAILED_TO_START, result.status() );
        assertEquals( DiagnosticCode.RESOURCE_NOT_FOUND, result.diagnostics().get( 0 ).code() );
        assertEquals( "/" + TEST_PROJECT_NAME + "/src/main/java", result.pomDirectory() );
    }

    @Test
    public void testGetEffectivePom() {
        try {
            // Test getting effective POM
            String pom = service.getEffectivePom(TEST_PROJECT_NAME);
            
            // Verify POM contains expected elements
            assertNotNull(pom);
            assertTrue(pom.contains("<groupId>com.example</groupId>"));
            assertTrue(pom.contains("<artifactId>maven-test-project</artifactId>"));
            assertTrue(pom.contains("<version>1.0.0-SNAPSHOT</version>"));
        } catch (RuntimeException e) {
            if (e.getMessage().contains("Could not find Maven configuration")) {
                // This is expected in test environment
                System.out.println("Note: Maven configuration not found - this is expected in test environment");
                assumeTrue(false, "Skipping test as Maven configuration is not available");
            } else {
                throw e;
            }
        }
    }
    
    @Test
    public void testListMavenProjects() {
        MavenProjectListResponse projects = service.listMavenProjects();

        // An empty workspace is a count of zero, not a sentence saying so.
        assertEquals(projects.projects().size(), projects.totalProjects());

        for (MavenProjectListResponse.MavenProject project : projects.projects()) {
            // The Eclipse name is what every other tool takes; the coordinates are what
            // a build takes. Both are fields rather than one indented bullet.
            assertNotNull(project.projectName());
            assertNotNull(project.groupId());
            assertNotNull(project.artifactId());
        }
    }
    
    @Test
    public void testGetProjectDependencies() {
        try {
            MavenDependenciesResponse dependencies = service.getProjectDependencies(TEST_PROJECT_NAME);

            assertEquals(TEST_PROJECT_NAME, dependencies.projectName());
            assertEquals(dependencies.dependencies().size(), dependencies.totalDependencies());

            Optional<MavenDependenciesResponse.MavenDependency> junit = dependencies.dependencies().stream()
                    .filter(dependency -> "junit".equals(dependency.artifactId()))
                    .findFirst();

            assertTrue(junit.isPresent(), String.valueOf(dependencies.dependencies()));
            assertEquals("junit", junit.get().groupId());
            assertEquals("4.13.2", junit.get().version());
            assertEquals("test", junit.get().scope());
            assertFalse(junit.get().versionManagedElsewhere(), "this pom states the version itself");
        } catch (RuntimeException e) {
            if (e.getMessage().contains("Could not find Maven configuration")) {
                // This is expected in test environment
                System.out.println("Note: Maven configuration not found - this is expected in test environment");
                assumeTrue(false, "Skipping test as Maven configuration is not available");
            } else {
                throw e;
            }
        }
    }
    
    @Test
    public void testGetProjectDependencies_InvalidProject() {
        // Test with non-existent project
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            service.getProjectDependencies("NonExistentProject");
        });
        
        assertTrue(exception.getMessage().contains("does not exist"));
    }
    
    private void createMavenProjectStructure() throws CoreException {
        // Create standard Maven directories
        createFolder("src/main/java");
        createFolder("src/main/resources");
        createFolder("src/test/java");
        createFolder("src/test/resources");
        createFolder("target");
    }
    
    private void createFolder(String path) throws CoreException {
        String[] segments = path.split("/");
        StringBuilder currentPath = new StringBuilder();
        
        for (String segment : segments) {
            currentPath.append(segment);
            IFolder folder = project.getFolder(currentPath.toString());
            if (!folder.exists()) {
                folder.create(IResource.NONE, true, monitor);
            }
            currentPath.append("/");
        }
    }
    
    private void createPomXml() throws CoreException {
        String pomContent = 
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
                "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                "    <modelVersion>4.0.0</modelVersion>\n" +
                "\n" +
                "    <groupId>com.example</groupId>\n" +
                "    <artifactId>maven-test-project</artifactId>\n" +
                "    <version>1.0.0-SNAPSHOT</version>\n" +
                "    <packaging>jar</packaging>\n" +
                "\n" +
                "    <name>Maven Test Project</name>\n" +
                "    <description>A test project for Maven service tests</description>\n" +
                "\n" +
                "    <properties>\n" +
                "        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n" +
                "        <maven.compiler.source>11</maven.compiler.source>\n" +
                "        <maven.compiler.target>11</maven.compiler.target>\n" +
                "    </properties>\n" +
                "\n" +
                "    <dependencies>\n" +
                "        <dependency>\n" +
                "            <groupId>junit</groupId>\n" +
                "            <artifactId>junit</artifactId>\n" +
                "            <version>4.13.2</version>\n" +
                "            <scope>test</scope>\n" +
                "        </dependency>\n" +
                "    </dependencies>\n" +
                "\n" +
                "    <build>\n" +
                "        <plugins>\n" +
                "            <plugin>\n" +
                "                <groupId>org.apache.maven.plugins</groupId>\n" +
                "                <artifactId>maven-compiler-plugin</artifactId>\n" +
                "                <version>3.8.1</version>\n" +
                "            </plugin>\n" +
                "        </plugins>\n" +
                "    </build>\n" +
                "\n" +
                "    <profiles>\n" +
                "        <profile>\n" +
                "            <id>dev</id>\n" +
                "            <properties>\n" +
                "                <env>development</env>\n" +
                "            </properties>\n" +
                "        </profile>\n" +
                "        <profile>\n" +
                "            <id>test</id>\n" +
                "            <properties>\n" +
                "                <env>test</env>\n" +
                "            </properties>\n" +
                "        </profile>\n" +
                "    </profiles>\n" +
                "</project>";
        
        IFile pomFile = project.getFile("pom.xml");
        ByteArrayInputStream source = new ByteArrayInputStream(pomContent.getBytes());
        
        if (pomFile.exists()) {
            pomFile.setContents(source, true, true, monitor);
        } else {
            pomFile.create(source, true, monitor);
        }
    }
    
    private IFile createFile(String path, String content) throws CoreException {
        IFile file = project.getFile(new Path(path));
        ByteArrayInputStream source = new ByteArrayInputStream(content.getBytes());
        
        if (file.exists()) {
            file.setContents(source, true, true, monitor);
        } else {
            file.create(source, true, monitor);
        }
        
        return file;
    }
    
    /**
     * Creates .project file content for a Maven project
     */
    private String createDotProjectContent() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
               "<projectDescription>\n" +
               "    <name>" + TEST_PROJECT_NAME + "</name>\n" +
               "    <comment></comment>\n" +
               "    <projects>\n" +
               "    </projects>\n" +
               "    <buildSpec>\n" +
               "        <buildCommand>\n" +
               "            <name>org.eclipse.jdt.core.javabuilder</name>\n" +
               "            <arguments>\n" +
               "            </arguments>\n" +
               "        </buildCommand>\n" +
               "        <buildCommand>\n" +
               "            <name>org.eclipse.m2e.core.maven2Builder</name>\n" +
               "            <arguments>\n" +
               "            </arguments>\n" +
               "        </buildCommand>\n" +
               "    </buildSpec>\n" +
               "    <natures>\n" +
               "        <nature>org.eclipse.jdt.core.javanature</nature>\n" +
               "        <nature>org.eclipse.m2e.core.maven2Nature</nature>\n" +
               "    </natures>\n" +
               "</projectDescription>";
    }
    
    /**
     * Creates .classpath file content for a Maven project
     */
    private String createDotClasspathContent() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
               "<classpath>\n" +
               "    <classpathentry kind=\"src\" output=\"target/classes\" path=\"src/main/java\">\n" +
               "        <attributes>\n" +
               "            <attribute name=\"optional\" value=\"true\"/>\n" +
               "            <attribute name=\"maven.pomderived\" value=\"true\"/>\n" +
               "        </attributes>\n" +
               "    </classpathentry>\n" +
               "    <classpathentry kind=\"src\" output=\"target/test-classes\" path=\"src/test/java\">\n" +
               "        <attributes>\n" +
               "            <attribute name=\"optional\" value=\"true\"/>\n" +
               "            <attribute name=\"maven.pomderived\" value=\"true\"/>\n" +
               "            <attribute name=\"test\" value=\"true\"/>\n" +
               "        </attributes>\n" +
               "    </classpathentry>\n" +
               "    <classpathentry kind=\"con\" path=\"org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-11\">\n" +
               "        <attributes>\n" +
               "            <attribute name=\"maven.pomderived\" value=\"true\"/>\n" +
               "        </attributes>\n" +
               "    </classpathentry>\n" +
               "    <classpathentry kind=\"con\" path=\"org.eclipse.m2e.MAVEN2_CLASSPATH_CONTAINER\">\n" +
               "        <attributes>\n" +
               "            <attribute name=\"maven.pomderived\" value=\"true\"/>\n" +
               "        </attributes>\n" +
               "    </classpathentry>\n" +
               "    <classpathentry kind=\"output\" path=\"target/classes\"/>\n" +
               "</classpath>";
    }
}
