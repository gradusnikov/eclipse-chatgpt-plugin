package com.github.gradusnikov.eclipse.assistai.mcp.results;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.mcp.McpJson;
import com.github.gradusnikov.eclipse.assistai.mcp.McpOutputSchemas;
import com.github.gradusnikov.eclipse.assistai.resources.SourceOrigin;

/**
 * The response records the project, Maven and type-resolution tools advertise.
 * <p>
 * Two things are checked of each: that what goes on the wire contains exactly the
 * fields the generated schema promises - the guard that caught the derived-accessor
 * defect in batch 2 - and that the failure and empty cases say so in a field rather
 * than in a sentence occupying the answer slot.
 */
public class ProjectToolResponsesPDETest
{
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

    @SuppressWarnings( "unchecked" )
    private static List<String> enumOf( Class<?> type, String field )
    {
        return (List<String>) property( properties( type ), field ).get( "enum" );
    }

    // ---- openProject -----------------------------------------------------

    private static OpenProjectResponse anImport()
    {
        return OpenProjectResponse.of( OpenProjectResponse.Status.IMPORTED, "MyProject",
                "/tmp/some-directory", "/tmp/some-directory" );
    }

    @Test
    public void openProjectSerializesExactlyTheFieldsItAdvertises()
    {
        assertEquals( properties( OpenProjectResponse.class ).keySet(), McpJson.toMap( anImport() ).keySet() );
    }

    @Test
    public void openProjectAdvertisesTheNameEveryOtherToolTakesNext()
    {
        Map<String, Object> fields = properties( OpenProjectResponse.class );

        assertTrue( fields.containsKey( "projectName" ), fields.keySet().toString() );
        assertTrue( fields.containsKey( "directoryPath" ),
                "the argument is echoed because projectName need not be its last segment" );
        assertTrue( fields.containsKey( "location" ) );
    }

    @Test
    public void openProjectSeparatesTheThreeWaysItCanSucceed()
    {
        List<String> statuses = enumOf( OpenProjectResponse.class, "status" );

        assertTrue( statuses.contains( "IMPORTED" ), statuses.toString() );
        assertTrue( statuses.contains( "OPENED" ), statuses.toString() );
        assertTrue( statuses.contains( "ALREADY_OPEN" ), statuses.toString() );
        assertTrue( statuses.contains( "FAILED" ), statuses.toString() );
    }

    @Test
    public void openProjectNamesNoProjectWhenNothingWasOpened()
    {
        OpenProjectResponse response = OpenProjectResponse.failed( "/no/such/dir",
                Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_FOUND, "Directory does not exist: /no/such/dir" ) );

        assertEquals( OpenProjectResponse.Status.FAILED, response.status() );
        assertNull( response.projectName(), "a caller must not address a project that does not exist" );
        assertEquals( "/no/such/dir", response.directoryPath() );
        assertEquals( DiagnosticCode.RESOURCE_NOT_FOUND, response.diagnostics().get( 0 ).code() );
    }

    @Test
    public void openProjectCarriesNoDiagnosticWhenItSucceeded()
    {
        assertTrue( anImport().diagnostics().isEmpty() );
        assertEquals( "MyProject", anImport().projectName() );
    }

    // ---- listMavenProjects -----------------------------------------------

    private static MavenProjectListResponse aMavenWorkspace()
    {
        return MavenProjectListResponse.of( List.of( new MavenProjectListResponse.MavenProject(
                "assistai.main", "com.example", "assist-ai", "1.2.0", "eclipse-plugin" ) ) );
    }

    @Test
    public void mavenProjectListSerializesExactlyTheFieldsItAdvertises()
    {
        assertEquals( properties( MavenProjectListResponse.class ).keySet(),
                McpJson.toMap( aMavenWorkspace() ).keySet() );
    }

    @Test
    public void mavenProjectListAdvertisesBothNamesSeparately()
    {
        Map<String, Object> project = propertiesOf( itemsOf( properties( MavenProjectListResponse.class ), "projects" ) );

        // The Eclipse name is what the other tools take; the coordinates are what a
        // build takes. They used to arrive as ": "-separated text in one bullet.
        assertTrue( project.containsKey( "projectName" ), project.keySet().toString() );
        assertTrue( project.containsKey( "groupId" ) );
        assertTrue( project.containsKey( "artifactId" ) );
        assertTrue( project.containsKey( "version" ) );
        assertTrue( project.containsKey( "packaging" ) );
    }

    @Test
    public void mavenProjectListCountsAnEmptyWorkspaceRatherThanSayingSo()
    {
        MavenProjectListResponse response = MavenProjectListResponse.of( List.of() );

        assertEquals( 0, response.totalProjects() );
        assertTrue( response.projects().isEmpty() );
    }

    @Test
    public void mavenProjectJoinsItsOwnCoordinates()
    {
        assertEquals( "com.example:assist-ai:1.2.0", aMavenWorkspace().projects().get( 0 ).coordinates() );
        assertEquals( 1, aMavenWorkspace().totalProjects() );
    }

    // ---- getProjectDependencies ------------------------------------------

    private static MavenDependenciesResponse someDependencies()
    {
        return MavenDependenciesResponse.of( "P", List.of(
                new MavenDependenciesResponse.MavenDependency( "org.junit.jupiter", "junit-jupiter", "5.10.0", "test" ),
                new MavenDependenciesResponse.MavenDependency( "org.slf4j", "slf4j-api", null, "compile" ) ) );
    }

    @Test
    public void mavenDependenciesSerializeExactlyTheFieldsTheyAdvertise()
    {
        assertEquals( properties( MavenDependenciesResponse.class ).keySet(),
                McpJson.toMap( someDependencies() ).keySet() );
    }

    @Test
    public void mavenDependenciesAdvertiseTheCoordinatesAsFields()
    {
        Map<String, Object> dependency =
                propertiesOf( itemsOf( properties( MavenDependenciesResponse.class ), "dependencies" ) );

        assertTrue( dependency.containsKey( "groupId" ), dependency.keySet().toString() );
        assertTrue( dependency.containsKey( "artifactId" ) );
        assertTrue( dependency.containsKey( "version" ) );
        assertTrue( dependency.containsKey( "scope" ) );
        assertEquals( "integer", property( properties( MavenDependenciesResponse.class ), "totalDependencies" )
                .get( "type" ) );
    }

    @Test
    public void aManagedVersionIsNullRatherThanTheWordNull()
    {
        MavenDependenciesResponse.MavenDependency managed = someDependencies().dependencies().get( 1 );

        assertNull( managed.version(), "the pom states no version here; it comes from dependencyManagement" );
        assertTrue( managed.versionManagedElsewhere() );
        assertFalse( someDependencies().dependencies().get( 0 ).versionManagedElsewhere() );
    }

    @Test
    public void aMissingScopeIsReportedAsMavensOwnDefault()
    {
        assertEquals( "compile", someDependencies().dependencies().get( 1 ).scope() );
    }

    @Test
    public void noDependenciesIsACountRatherThanASentence()
    {
        MavenDependenciesResponse response = MavenDependenciesResponse.of( "P", List.of() );

        assertEquals( 0, response.totalDependencies() );
        assertTrue( response.dependencies().isEmpty() );
        assertEquals( "P", response.projectName() );
    }

    // ---- getProjectProperties --------------------------------------------

    private static ProjectPropertiesResponse aJavaProject()
    {
        return ProjectPropertiesResponse.of( "P", "/tmp/P", List.of( "org.eclipse.jdt.core.javanature" ),
                List.of( "pom.xml" ),
                new ProjectPropertiesResponse.JavaProperties( "21", "21", "21", "target/classes",
                        List.of( "src/main/java" ), List.of( "Other" ), List.of( "/tmp/lib/a.jar" ) ) );
    }

    @Test
    public void projectPropertiesSerializeExactlyTheFieldsTheyAdvertise()
    {
        assertEquals( properties( ProjectPropertiesResponse.class ).keySet(),
                McpJson.toMap( aJavaProject() ).keySet() );
    }

    @Test
    public void projectPropertiesSeparateAMissingProjectFromAClosedOne()
    {
        // The two need opposite next moves: fix the name, or call openProject.
        List<String> statuses = enumOf( ProjectPropertiesResponse.class, "status" );

        assertTrue( statuses.contains( "PROJECT_NOT_FOUND" ), statuses.toString() );
        assertTrue( statuses.contains( "PROJECT_CLOSED" ), statuses.toString() );

        ProjectPropertiesResponse missing = ProjectPropertiesResponse.failed( "Nope",
                ProjectPropertiesResponse.Status.PROJECT_NOT_FOUND,
                Diagnostic.fatal( DiagnosticCode.PROJECT_NOT_FOUND, "not found" ) );
        ProjectPropertiesResponse closed = ProjectPropertiesResponse.failed( "Shut",
                ProjectPropertiesResponse.Status.PROJECT_CLOSED,
                Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_ACCESSIBLE, "closed" ) );

        assertEquals( DiagnosticCode.PROJECT_NOT_FOUND, missing.diagnostics().get( 0 ).code() );
        assertEquals( DiagnosticCode.RESOURCE_NOT_ACCESSIBLE, closed.diagnostics().get( 0 ).code() );
        assertNull( missing.java() );
        assertTrue( closed.natures().isEmpty() );
    }

    @Test
    public void projectPropertiesAdvertiseWhereANewClassMayGo()
    {
        Map<String, Object> java = propertiesOf( property( properties( ProjectPropertiesResponse.class ), "java" ) );

        assertEquals( "array", property( java, "sourceFolders" ).get( "type" ) );
        assertEquals( "string", SchemaTypes.carriedBy( property( java, "outputLocation" ) ) );
        assertTrue( java.containsKey( "complianceLevel" ) );
        assertTrue( java.containsKey( "referencedProjects" ) );
    }

    @Test
    public void projectPropertiesReportPathsInTheFormTheOtherToolsTake()
    {
        ProjectPropertiesResponse.JavaProperties java = aJavaProject().java();

        assertEquals( List.of( "src/main/java" ), java.sourceFolders(),
                "a workspace path like /P/src/main/java is accepted by no reading or editing tool" );
        assertEquals( "target/classes", java.outputLocation() );
        assertEquals( List.of( "Other" ), java.referencedProjects(),
                "a project reference is the projectName the other tools take" );
    }

    @Test
    public void aProjectWithNoJavaNatureReportsNoJavaBlock()
    {
        ProjectPropertiesResponse response = ProjectPropertiesResponse.of( "P", "/tmp/P",
                List.of( "org.python.pydev.pythonNature" ), List.of( "requirements.txt" ), null );

        assertEquals( ProjectPropertiesResponse.Status.OK, response.status(),
                "not being a Java project is not a failure" );
        assertNull( response.java() );
        assertEquals( List.of( "requirements.txt" ), response.buildFiles() );
    }

    // ---- explainTypeResolution -------------------------------------------

    private static TypeResolutionResponse aWorkspaceType()
    {
        return new TypeResolutionResponse( TypeResolutionResponse.Status.OK, "com.example.A", "com.example.A",
                "P", SourceOrigin.WORKSPACE_SOURCE, "P", "src/com/example/A.java",
                TypeResolutionResponse.RootKind.WORKSPACE_FOLDER, "/P/src", null,
                TypeResolutionResponse.ClasspathEntryKind.SOURCE, "/P/src", null, Diagnostic.none() );
    }

    @Test
    public void typeResolutionSerializesExactlyTheFieldsItAdvertises()
    {
        assertEquals( properties( TypeResolutionResponse.class ).keySet(),
                McpJson.toMap( aWorkspaceType() ).keySet() );
    }

    @Test
    public void typeResolutionReportsALocationTheReadingToolsAccept()
    {
        Map<String, Object> fields = properties( TypeResolutionResponse.class );

        assertTrue( fields.containsKey( "projectName" ), fields.keySet().toString() );
        assertTrue( fields.containsKey( "filePath" ) );
        assertEquals( "src/com/example/A.java", aWorkspaceType().filePath(),
                "it used to be the workspace path /P/src/com/example/A.java, which no tool accepts" );
        assertEquals( "P", aWorkspaceType().searchedProjectName() );
    }

    @Test
    public void typeResolutionReusesTheOriginEnumEveryReadReports()
    {
        List<String> origins = enumOf( TypeResolutionResponse.class, "sourceOrigin" );

        // The Kind: and Source strategy: lines were two prose renderings of this.
        assertTrue( origins.contains( "WORKSPACE_SOURCE" ), origins.toString() );
        assertTrue( origins.contains( "ATTACHED_SOURCE" ), origins.toString() );
        assertTrue( origins.contains( "DECOMPILED_CLASS" ), origins.toString() );
    }

    @Test
    public void typeResolutionSeparatesAnUnknownTypeFromAnUnknownProject()
    {
        List<String> statuses = enumOf( TypeResolutionResponse.class, "status" );
        assertTrue( statuses.contains( "TYPE_NOT_RESOLVED" ), statuses.toString() );
        assertTrue( statuses.contains( "PROJECT_NOT_FOUND" ), statuses.toString() );

        TypeResolutionResponse unresolved = TypeResolutionResponse.failed( "com.example.Nope", "P",
                TypeResolutionResponse.Status.TYPE_NOT_RESOLVED,
                Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_FOUND, "not on the classpath" ) );

        assertNull( unresolved.sourceOrigin(), "nothing resolved, so there is no origin to report" );
        assertNull( unresolved.filePath() );
        assertEquals( "P", unresolved.searchedProjectName() );
    }

    @Test
    public void typeResolutionAdvertisesWhereTheClasspathRootIs()
    {
        List<String> rootKinds = enumOf( TypeResolutionResponse.class, "rootKind" );

        assertTrue( rootKinds.contains( "WORKSPACE_FOLDER" ), rootKinds.toString() );
        assertTrue( rootKinds.contains( "EXTERNAL_ARCHIVE" ), rootKinds.toString() );

        List<String> entryKinds = enumOf( TypeResolutionResponse.class, "classpathEntryKind" );
        assertTrue( entryKinds.contains( "LIBRARY" ), entryKinds.toString() );
        assertTrue( entryKinds.contains( "CONTAINER" ), entryKinds.toString() );
    }

    // ---- getJavaDoc ------------------------------------------------------

    private static JavaDocResponse someJavaDoc()
    {
        return JavaDocResponse.of( JavaDocResponse.Status.OK, "com.example.A", "P", "Documents A.", List.of() );
    }

    @Test
    public void javaDocSerializesExactlyTheFieldsItAdvertises()
    {
        assertEquals( properties( JavaDocResponse.class ).keySet(), McpJson.toMap( someJavaDoc() ).keySet() );
    }

    @Test
    public void javaDocKeepsTheTypesCommentAsOneMarkdownString()
    {
        // One string, and nullable: a type that resolves but carries no comment of its
        // own reports null, rather than an empty string that a caller would have to tell
        // apart from documentation that is genuinely blank.
        assertEquals( List.of( "string", "null" ),
                property( properties( JavaDocResponse.class ), "javadoc" ).get( "type" ),
                "rendered Markdown is one piece of text, the trade DiffResponse also makes" );
    }

    @Test
    public void javaDocSeparatesAnUndocumentedTypeFromAMisspelledName()
    {
        List<String> statuses = enumOf( JavaDocResponse.class, "status" );
        assertTrue( statuses.contains( "NO_JAVADOC" ), statuses.toString() );
        assertTrue( statuses.contains( "TYPE_NOT_FOUND" ), statuses.toString() );

        JavaDocResponse missing = JavaDocResponse.notFound( "com.example.Nope",
                Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_FOUND, "no project resolves it" ) );
        JavaDocResponse undocumented =
                JavaDocResponse.of( JavaDocResponse.Status.NO_JAVADOC, "com.example.A", "P", null, List.of() );

        assertNull( missing.projectName() );
        assertEquals( DiagnosticCode.RESOURCE_NOT_FOUND, missing.diagnostics().get( 0 ).code() );

        assertEquals( "P", undocumented.projectName(), "the type was found; it simply has no comment" );
        assertTrue( undocumented.diagnostics().isEmpty(),
                "an undocumented type is an ordinary state, not a fault" );
    }
}
