package com.github.gradusnikov.eclipse.assistai.mcp.results;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.McpOutputSchemas;
import com.github.gradusnikov.eclipse.assistai.mcp.services.CodeAnalysisService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.ProjectService;
import com.github.gradusnikov.eclipse.assistai.services.AiIgnoreService;
import com.github.gradusnikov.eclipse.assistai.tools.Javadocs;

/**
 * The responses the analysis tools - {@code listProjects}, {@code getTypeHierarchy} and
 * {@code getClassOutline} - return.
 * <p>
 * Three things are checked. That the generated schema names the fields a client is told
 * to branch on. That the empty and failed cases say so in a field instead of in prose.
 * And, against a real Java project, that the locations and line numbers reported are the
 * ones the reading tools take: an outline whose {@code startLine} does not land on the
 * member is worse than no outline, because the caller acts on it.
 */
public class AnalysisResponsesPDETest
{
    private static final String TEST_PROJECT_NAME = "AnalysisResponsesTestProject";

    /** Line 3 opens the class, line 7 opens greet, line 10 closes it, line 11 closes the class. */
    private static final String CHILD_SOURCE =
            "package com.example;\n"
            + "\n"
            + "public class Child extends Base\n"
            + "{\n"
            + "    private int counter = 0;\n"
            + "\n"
            + "    public String greet()\n"
            + "    {\n"
            + "        return \"hello \" + counter;\n"
            + "    }\n"
            + "}\n";

    private IProject             project;
    private CodeAnalysisService  codeAnalysisService;
    private ProjectService       projectService;
    private final NullProgressMonitor monitor = new NullProgressMonitor();

    @BeforeEach
    public void beforeEach() throws CoreException, IOException, InterruptedException
    {
        BundleContext bundleContext = FrameworkUtil.getBundle( AnalysisResponsesPDETest.class ).getBundleContext();
        ServiceTracker<IWorkspace, IWorkspace> workspaceTracker =
                new ServiceTracker<>( bundleContext, IWorkspace.class, null );
        workspaceTracker.open();
        IWorkspaceRoot root = workspaceTracker.getService().getRoot();

        project = root.getProject( TEST_PROJECT_NAME );
        if ( project.exists() )
        {
            project.delete( true, true, monitor );
        }

        IProjectDescription description = root.getWorkspace().newProjectDescription( TEST_PROJECT_NAME );
        description.setNatureIds( new String[] { JavaCore.NATURE_ID } );
        project.create( description, monitor );
        project.open( monitor );

        // A programmatically created project does not reliably inherit the Java builder
        // from its nature in a PDE test workspace, so configure it explicitly.
        IProjectDescription configured = project.getDescription();
        ICommand javaBuilder = configured.newCommand();
        javaBuilder.setBuilderName( JavaCore.BUILDER_ID );
        configured.setBuildSpec( new ICommand[] { javaBuilder } );
        project.setDescription( configured, monitor );

        IJavaProject javaProject = JavaCore.create( project );
        createFolder( "bin" );
        javaProject.setOutputLocation( project.getFolder( "bin" ).getFullPath(), monitor );
        createFolder( "src" );
        javaProject.setRawClasspath( new IClasspathEntry[] {
                JavaCore.newSourceEntry( project.getFullPath().append( "src" ) ),
                JavaRuntime.getDefaultJREContainerEntry() }, monitor );

        createFolder( "src/com" );
        createFolder( "src/com/example" );
        createFile( "src/com/example/Greeter.java",
                "package com.example;\n\n"
                + "/** Greets people. Politely, when asked. */\n"
                + "public interface Greeter\n{\n"
                + "    /**\n     * Says hello.\n     * Second sentence, e.g. the details.\n     *\n     * @return the greeting\n     */\n"
                + "    String greet();\n}\n" );
        createFile( "src/com/example/Base.java",
                "package com.example;\n\npublic class Base implements Greeter\n{\n"
                + "    public String greet()\n    {\n        return \"base\";\n    }\n}\n" );
        createFile( "src/com/example/Child.java", CHILD_SOURCE );

        project.build( IncrementalProjectBuilder.FULL_BUILD, monitor );
        Thread.sleep( 1000 );

        IEclipseContext context = EclipseContextFactory.create();
        context.set( ILog.class, Activator.getDefault().getLog() );
        context.set( AiIgnoreService.class, ContextInjectionFactory.make( AiIgnoreService.class, context ) );
        codeAnalysisService = ContextInjectionFactory.make( CodeAnalysisService.class, context );
        projectService = ContextInjectionFactory.make( ProjectService.class, context );
    }

    @AfterEach
    public void afterEach() throws CoreException
    {
        if ( project != null && project.exists() )
        {
            project.delete( true, true, monitor );
        }
    }

    // ---- schema ----------------------------------------------------------

    @SuppressWarnings( "unchecked" )
    private static Map<String, Object> properties( Class<?> type )
    {
        Map<String, Object> schema = McpOutputSchemas.forType( type );
        assertNotNull( schema, type.getSimpleName() + " must advertise a schema" );
        return (Map<String, Object>) schema.get( "properties" );
    }

    @SuppressWarnings( "unchecked" )
    private static Map<String, Object> itemsOf( Map<String, Object> properties, String field )
    {
        Map<String, Object> array = (Map<String, Object>) properties.get( field );
        assertEquals( "array", array.get( "type" ), field + " should be an array" );
        return (Map<String, Object>) array.get( "items" );
    }

    @SuppressWarnings( "unchecked" )
    private static Map<String, Object> propertiesOf( Map<String, Object> objectSchema )
    {
        return (Map<String, Object>) objectSchema.get( "properties" );
    }

    @SuppressWarnings( "unchecked" )
    private static Map<String, Object> property( Map<String, Object> properties, String field )
    {
        return (Map<String, Object>) properties.get( field );
    }

    @Test
    public void projectListAdvertisesTheNameEveryOtherToolTakes()
    {
        Map<String, Object> workspaceProject =
                propertiesOf( itemsOf( properties( ProjectListResponse.class ), "projects" ) );

        assertTrue( workspaceProject.containsKey( "projectName" ), workspaceProject.keySet().toString() );
        assertEquals( "boolean", property( workspaceProject, "open" ).get( "type" ),
                "open decides whether the project can be read at all, so it must be a flag" );
        assertEquals( "array", property( workspaceProject, "natures" ).get( "type" ) );
        assertTrue( workspaceProject.containsKey( "location" ) );
    }

    @Test
    public void typeHierarchyAdvertisesTheThreeRelationsSeparately()
    {
        Map<String, Object> fields = properties( TypeHierarchyResponse.class );

        assertEquals( "array", property( fields, "superclasses" ).get( "type" ) );
        assertEquals( "array", property( fields, "interfaces" ).get( "type" ) );
        assertEquals( "array", property( fields, "subtypes" ).get( "type" ) );
    }

    @Test
    public void typeHierarchyAdvertisesWhereEachTypeCanBeOpened()
    {
        Map<String, Object> hierarchyType =
                propertiesOf( itemsOf( properties( TypeHierarchyResponse.class ), "subtypes" ) );

        assertTrue( hierarchyType.containsKey( "fullyQualifiedName" ) );
        assertTrue( hierarchyType.containsKey( "projectName" ) );
        assertTrue( hierarchyType.containsKey( "filePath" ),
                "a subtype is only actionable if it can be fed back to the reading tools" );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    public void classOutlineAdvertisesTheLineRangeNeededToReadAMember()
    {
        Map<String, Object> member = propertiesOf( itemsOf( properties( ClassOutlineResponse.class ), "methods" ) );

        assertEquals( "integer", property( member, "startLine" ).get( "type" ) );
        assertEquals( "integer", property( member, "endLine" ).get( "type" ),
                "without an end line the caller has to guess where a member stops" );
        assertTrue( member.containsKey( "label" ) );
        assertTrue( member.containsKey( "name" ) );

        List<String> statuses = (List<String>) property( properties( ClassOutlineResponse.class ), "status" ).get( "enum" );
        assertTrue( statuses.contains( "TYPE_NOT_FOUND" ), statuses.toString() );
        assertTrue( statuses.contains( "NO_SOURCE" ), statuses.toString() );
        assertTrue( statuses.contains( "ACCESS_DENIED" ), statuses.toString() );
    }

    // ---- the empty and failed cases --------------------------------------

    @Test
    public void projectListCountsOpenProjectsSeparatelyFromAll()
    {
        ProjectListResponse response = ProjectListResponse.of( List.of(
                new ProjectListResponse.WorkspaceProject( "Open", true, List.of( JavaCore.NATURE_ID ), "/tmp/open" ),
                new ProjectListResponse.WorkspaceProject( "Closed", false, List.of(), "/tmp/closed" ) ) );

        assertEquals( 2, response.totalProjects() );
        assertEquals( 1, response.openProjects(), "a closed project cannot be read, searched or built" );
        assertTrue( response.projects().get( 0 ).hasJavaNature() );
        assertFalse( response.projects().get( 1 ).hasJavaNature() );
    }

    @Test
    public void projectListSummarizesAnEmptyWorkspace()
    {
        ProjectListResponse response = ProjectListResponse.of( List.of() );

        assertEquals( 0, response.totalProjects() );
        assertEquals( 0, response.openProjects() );
        assertTrue( response.projects().isEmpty() );
    }

    @Test
    public void typeHierarchyReportsANameNoProjectKnowsAsAStatus()
    {
        TypeHierarchyResponse response = TypeHierarchyResponse.notFound( "com.example.Nope" );

        assertEquals( TypeHierarchyResponse.Status.TYPE_NOT_FOUND, response.status(),
                "'not found' must be distinguishable from 'found, but has no relations'" );
        assertTrue( response.superclasses().isEmpty() );
        assertTrue( response.interfaces().isEmpty() );
        assertTrue( response.subtypes().isEmpty() );
        assertFalse( response.hasSubtypes() );
    }

    @Test
    public void classOutlineFailureCarriesNoMembers()
    {
        ClassOutlineResponse response = ClassOutlineResponse.failed( "com.example.Nope",
                ClassOutlineResponse.Status.TYPE_NOT_FOUND, "no such type" );

        assertEquals( ClassOutlineResponse.Status.TYPE_NOT_FOUND, response.status() );
        assertNull( response.declaration() );
        assertNull( response.projectName() );
        assertTrue( response.fields().isEmpty() );
        assertTrue( response.methods().isEmpty() );
        assertTrue( response.innerTypes().isEmpty() );
    }

    @Test
    public void aMemberKnowsHowManyLinesReadingItCosts()
    {
        assertEquals( 1, new ClassOutlineResponse.Member( "f", "int f", 5, 5, null, false ).lineCount() );
        assertEquals( 4, new ClassOutlineResponse.Member( "m", "void m()", 7, 10, null, false ).lineCount() );
    }

    // ---- against a real project ------------------------------------------

    @Test
    public void listsTheTestProjectAsOpenWithItsJavaNature()
    {
        ProjectListResponse response = projectService.listProjects();

        ProjectListResponse.WorkspaceProject found = response.projects().stream()
                .filter( candidate -> TEST_PROJECT_NAME.equals( candidate.projectName() ) )
                .findFirst()
                .orElseThrow();

        assertTrue( found.open() );
        assertTrue( found.hasJavaNature(), found.natures().toString() );
        assertNotNull( found.location(), "a project with local content reports where it is on disk" );
        assertEquals( response.projects().size(), response.totalProjects() );
    }

    @Test
    public void outlinesATypeWithLineRangesThatLandOnItsMembers() throws CoreException, IOException
    {
        ClassOutlineResponse response = codeAnalysisService.getClassOutline( "com.example.Child", true, Javadocs.Detail.SUMMARY );

        assertEquals( ClassOutlineResponse.Status.OK, response.status() );
        assertEquals( TEST_PROJECT_NAME, response.projectName() );
        assertEquals( "src/com/example/Child.java", response.filePath(),
                "the reading and editing tools take a project-relative path" );

        assertEquals( 3, response.declaration().startLine() );
        assertEquals( 11, response.declaration().endLine() );
        assertTrue( response.declaration().label().contains( "Child" ) );

        ClassOutlineResponse.Member counter = member( response.fields(), "counter" );
        assertEquals( 5, counter.startLine() );
        assertEquals( 5, counter.endLine() );

        ClassOutlineResponse.Member greet = member( response.methods(), "greet" );
        assertEquals( 7, greet.startLine() );
        assertEquals( 10, greet.endLine() );

        // The contract that matters: the reported range, fed back to a reader, is the member.
        String[] lines = read( "src/com/example/Child.java" ).split( "\n", -1 );
        assertTrue( lines[greet.startLine() - 1].contains( "greet" ), lines[greet.startLine() - 1] );
        assertEquals( "}", lines[greet.endLine() - 1].trim() );
    }

    @Test
    public void omitsFieldsWhenNotAskedForThem()
    {
        ClassOutlineResponse response = codeAnalysisService.getClassOutline( "com.example.Child", false, Javadocs.Detail.SUMMARY );

        assertEquals( ClassOutlineResponse.Status.OK, response.status() );
        assertTrue( response.fields().isEmpty() );
        assertFalse( response.methods().isEmpty(), "methods are listed either way" );
    }

    @Test
    public void reportsTypeNotFoundRatherThanAnEmptyOutline()
    {
        ClassOutlineResponse response = codeAnalysisService.getClassOutline( "com.example.NoSuchType", true, Javadocs.Detail.SUMMARY );

        assertEquals( ClassOutlineResponse.Status.TYPE_NOT_FOUND, response.status() );
    }

    @Test
    public void reportsWhereEachTypeInTheHierarchyLives()
    {
        TypeHierarchyResponse response = codeAnalysisService.getTypeHierarchy( "com.example.Child", Javadocs.Detail.NONE );

        assertEquals( TypeHierarchyResponse.Status.OK, response.status() );

        TypeHierarchyResponse.HierarchyType base = hierarchyType( response.superclasses(), "com.example.Base" );
        assertTrue( base.inWorkspace() );
        assertEquals( TEST_PROJECT_NAME, base.projectName() );
        assertEquals( "src/com/example/Base.java", base.filePath() );

        TypeHierarchyResponse.HierarchyType greeter = hierarchyType( response.interfaces(), "com.example.Greeter" );
        assertEquals( "src/com/example/Greeter.java", greeter.filePath() );

        // A type that is not workspace source reports no location, which is how a caller
        // tells "I can open this" from "I cannot".
        Optional<TypeHierarchyResponse.HierarchyType> object = response.superclasses().stream()
                .filter( type -> "java.lang.Object".equals( type.fullyQualifiedName() ) )
                .findFirst();
        object.ifPresent( type -> {
            assertFalse( type.inWorkspace() );
            assertNull( type.filePath() );
        } );
    }

    @Test
    public void reportsSubtypesOfASupertype()
    {
        TypeHierarchyResponse response = codeAnalysisService.getTypeHierarchy( "com.example.Base", Javadocs.Detail.NONE );

        assertEquals( TypeHierarchyResponse.Status.OK, response.status() );
        assertTrue( response.hasSubtypes() );
        assertEquals( TEST_PROJECT_NAME, hierarchyType( response.subtypes(), "com.example.Child" ).projectName() );
    }

    @Test
    public void reportsAnUnknownTypeAsAStatusRatherThanThrowing()
    {
        TypeHierarchyResponse response = codeAnalysisService.getTypeHierarchy( "com.example.NoSuchType", Javadocs.Detail.NONE );

        assertEquals( TypeHierarchyResponse.Status.TYPE_NOT_FOUND, response.status() );
    }

    // ---- javadoc ---------------------------------------------------------

    @Test
    public void outlineCarriesTheFirstJavadocSentenceOfEachMember()
    {
        ClassOutlineResponse response =
                codeAnalysisService.getClassOutline( "com.example.Greeter", true, Javadocs.Detail.SUMMARY );

        assertEquals( "Greets people.", response.declaration().javadoc() );
        ClassOutlineResponse.Member greet = member( response.methods(), "greet" );
        assertEquals( "Says hello.", greet.javadoc() );
        assertFalse( greet.javadocInherited() );
    }

    @Test
    public void anUndocumentedOverrideReportsItsSupertypesJavadocAndSaysSo()
    {
        // Child.greet overrides Base.greet, which implements Greeter.greet: the comment is two levels up.
        ClassOutlineResponse response =
                codeAnalysisService.getClassOutline( "com.example.Child", true, Javadocs.Detail.SUMMARY );

        ClassOutlineResponse.Member greet = member( response.methods(), "greet" );
        assertEquals( "Says hello.", greet.javadoc() );
        assertTrue( greet.javadocInherited() );
        assertNull( response.declaration().javadoc(), "a type does not inherit its supertype's comment" );
        assertNull( member( response.fields(), "counter" ).javadoc() );
    }

    @Test
    public void fullJavadocRendersTheWholeCommentAsMarkdown()
    {
        ClassOutlineResponse response =
                codeAnalysisService.getClassOutline( "com.example.Greeter", true, Javadocs.Detail.FULL );

        String javadoc = member( response.methods(), "greet" ).javadoc();
        assertTrue( javadoc.contains( "Second sentence, e.g. the details." ), javadoc );
        assertTrue( javadoc.contains( "the greeting" ), javadoc );
        assertFalse( javadoc.contains( "/**" ), "rendered, not pasted: " + javadoc );
        assertFalse( javadoc.contains( "<" ), "Markdown, not HTML: " + javadoc );
    }

    @Test
    public void noJavadocLeavesTheFieldsNull()
    {
        ClassOutlineResponse response =
                codeAnalysisService.getClassOutline( "com.example.Greeter", true, Javadocs.Detail.NONE );

        assertNull( response.declaration().javadoc() );
        assertNull( member( response.methods(), "greet" ).javadoc() );
    }

    @Test
    public void hierarchyCarriesJavadocOnlyWhenAsked()
    {
        TypeHierarchyResponse structural =
                codeAnalysisService.getTypeHierarchy( "com.example.Child", Javadocs.Detail.NONE );
        assertNull( hierarchyType( structural.interfaces(), "com.example.Greeter" ).javadoc() );

        TypeHierarchyResponse summarised =
                codeAnalysisService.getTypeHierarchy( "com.example.Child", Javadocs.Detail.SUMMARY );
        assertEquals( "Greets people.", hierarchyType( summarised.interfaces(), "com.example.Greeter" ).javadoc() );
    }

    // ---- fixture ---------------------------------------------------------

    private static ClassOutlineResponse.Member member( List<ClassOutlineResponse.Member> members, String name )
    {
        return members.stream()
                .filter( candidate -> name.equals( candidate.name() ) )
                .findFirst()
                .orElseThrow( () -> new AssertionError( "no member named " + name + " in " + members ) );
    }

    private static TypeHierarchyResponse.HierarchyType hierarchyType(
            List<TypeHierarchyResponse.HierarchyType> types, String fullyQualifiedName )
    {
        return types.stream()
                .filter( candidate -> fullyQualifiedName.equals( candidate.fullyQualifiedName() ) )
                .findFirst()
                .orElseThrow( () -> new AssertionError( "no " + fullyQualifiedName + " in " + types ) );
    }

    private void createFolder( String path ) throws CoreException
    {
        IFolder folder = project.getFolder( new Path( path ) );
        if ( !folder.exists() )
        {
            folder.create( IResource.NONE, true, monitor );
        }
    }

    private void createFile( String path, String content ) throws CoreException
    {
        IFile file = project.getFile( new Path( path ) );
        file.create( new ByteArrayInputStream( content.getBytes() ), true, monitor );
    }

    private String read( String path ) throws CoreException, IOException
    {
        try ( var stream = project.getFile( new Path( path ) ).getContents() )
        {
            return new String( stream.readAllBytes() );
        }
    }
}
