package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.results.CallHierarchyResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.ClassOutlineResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.CompilationProblemsResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.ConsoleOutputResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.FileListResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.ImportSuggestionsResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.JavaDocResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.MarkdownOutlineResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.MavenBuildResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.MavenDependenciesResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.MavenProjectListResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.MethodSearchResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.MethodSourceResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.OpenProjectResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.PackageSummaryResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.ProjectLayoutResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.ProjectListResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.ProjectPropertiesResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.QuickFixResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.ReferencesResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.SearchReplaceResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.SearchResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestClassesResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TestRunResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TypeHierarchyResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TypeResolutionResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TypeSearchResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.WorkspaceOverviewResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;
import com.github.gradusnikov.eclipse.assistai.mcp.services.CodeAnalysisService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.CodeDiscoveryService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.CodeEditingService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.ConsoleService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.EditorService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.MarkdownService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.JavaDocService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.MavenService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.OutlineService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.ProjectService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.ResourceService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.SearchService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.UnitTestService;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceReadResult;

import io.modelcontextprotocol.spec.McpSchema;
import jakarta.inject.Inject;

@Creatable
@McpServer( name = "eclipse-ide" )
public class EclipseIntegrationsMcpServer
{
    @Inject
    private JavaDocService      javaDocService;

    @Inject
    private ProjectService      projectService;

    @Inject
    private CodeAnalysisService codeAnalysisService;

    @Inject
    private ResourceService     resourceService;

    @Inject
    private SearchService       searchService;

    @Inject
    private EditorService       editorService;

    @Inject
    private ConsoleService      consoleService;

    @Inject
    private CodeEditingService  codeEditingService;

    @Inject
    private UnitTestService     unitTestService;

    @Inject
    private MavenService        mavenService;

    @Inject
    private OutlineService      outlineService;

    @Inject
    private MarkdownService     markdownService;

    @Inject
    private CodeDiscoveryService codeDiscoveryService;


    @Tool( name = "formatCode", description = "Formats code according to the current Eclipse formatter settings.", type = "object" )
    public String formatCode( @ToolParam( name = "code", description = "The code to be formatted", required = true )
    String code, @ToolParam( name = "projectName", description = "Optional project name to use project-specific formatter settings", required = false )
    String projectName )
    {
        return codeEditingService.formatCode( code, projectName );
    }

    @Tool( name = "getJavaDoc", description = "Gets the JavaDoc of a Java type as Markdown, with each of its members' declarations. "
            + "A member type of class A in package x.y is named x.y.A.B, and a type name must match its compilation unit name to be found. "
            + "status separates the three cases that used to share one sentence: OK, NO_JAVADOC (the type exists and is undocumented - read the "
            + "source instead) and TYPE_NOT_FOUND (no open project resolves the name - fix it). projectName says which project answered.",
            type = "object", outputType = JavaDocResponse.class )
    public JavaDocResponse getJavaDoc( @ToolParam( name = "fullyQualifiedName", description = "A fully qualified name of the compilation unit", required = true )
    String fullyQualifiedClassName )
    {
        return javaDocService.getJavaDoc( fullyQualifiedClassName );
    }

    @Tool( name = "getSource", description = "Get source for a workspace or referenced-library class. Prefers original/attached source and decompiles binary classes when source is unavailable. "
            + "origin says which of the three it is: only WORKSPACE_SOURCE can be edited, and version.modificationStamp is the token an edit passes as expectedModificationStamp.",
            type = "object", outputType = ResourceReadResult.class )
    public ResourceReadResult getSource( @ToolParam( name = "fullyQualifiedClassName", description = "A fully qualified class name of the Java class", required = true )
    String fullyQualifiedClassName )
    {
        return javaDocService.getSourceWithResource( fullyQualifiedClassName );
    }

    @Tool( name = "explainTypeResolution", description = "Explains how a Java type resolves on one Eclipse project's classpath: which classpath root and entry supplied it, "
            + "whether that root is a workspace folder or an external archive, whether source is attached, and where its class file is. "
            + "sourceOrigin is the same enum getSource and readProjectResource report - WORKSPACE_SOURCE, ATTACHED_SOURCE or DECOMPILED_CLASS - and says what getSource would return. "
            + "A type backed by a workspace file also reports projectName and a project-relative filePath the reading and editing tools take. "
            + "status separates a type that is not on the classpath from a project name that does not exist.",
            type = "object", outputType = TypeResolutionResponse.class )
    public TypeResolutionResponse explainTypeResolution(
            @ToolParam( name = "projectName", description = "The exact open Eclipse Java project name", required = true ) String projectName,
            @ToolParam( name = "fullyQualifiedClassName", description = "The fully qualified Java type name", required = true ) String fullyQualifiedClassName )
    {
        return javaDocService.explainTypeResolution( projectName, fullyQualifiedClassName );
    }

    @Tool( name = "getClassOutline", description = "Returns the outline of a Java class: its declaration plus fields, method signatures (no bodies) and inner types. "
            + "Every entry carries a 1-based startLine and endLine, so one member can be read with readProjectResource(projectName, filePath, startLine, endLine) "
            + "instead of fetching the whole file. Much cheaper than getSource; use this first, then getMethodSource or readProjectResource for the member you want. "
            + "status reports TYPE_NOT_FOUND, NO_SOURCE or ACCESS_DENIED rather than an empty outline.",
            type = "object", outputType = ClassOutlineResponse.class )
    public ClassOutlineResponse getClassOutline(
            @ToolParam( name = "fullyQualifiedClassName", description = "A fully qualified class name (e.g. 'com.example.MyClass')", required = true )
            String fullyQualifiedClassName,
            @ToolParam( name = "includeFields", description = "Whether to include field declarations (default: true)", required = false )
            String includeFields )
    {
        boolean fields = Optional.ofNullable( includeFields ).map( Boolean::parseBoolean ).orElse( true );
        return codeAnalysisService.getClassOutline( fullyQualifiedClassName, fields );
    }

    @Tool( name = "getMethodSource", description = "Returns the source of specific method(s) of one class. Accepts comma-separated method names to retrieve several in one call. "
            + "Each method comes back as exact source with its own 1-based range, so its lines can be passed straight to the editing tools; "
            + "a requested name that matches nothing is listed in notFound rather than mentioned in a comment. "
            + "version.modificationStamp is the token an edit passes as expectedModificationStamp. Use after getClassOutline to read only the methods you need.",
            type = "object", outputType = MethodSourceResponse.class )
    public MethodSourceResponse getMethodSource(
            @ToolParam( name = "fullyQualifiedClassName", description = "A fully qualified class name (e.g. 'com.example.MyClass')", required = true )
            String fullyQualifiedClassName,
            @ToolParam( name = "methodNames", description = "Comma-separated method names to retrieve (e.g. 'findById,save,delete')", required = true )
            String methodNames,
            @ToolParam( name = "methodSignature", description = "Optional parameter type hint to disambiguate overloaded methods (e.g. 'String')", required = false )
            String methodSignature, @ToolParam( name = "includeJavadoc", description = "Whether to include Javadoc comments (default: true)", required = false )
            String includeJavadoc )
    {
        boolean javadoc = Optional.ofNullable( includeJavadoc ).map( Boolean::parseBoolean ).orElse( true );
        return outlineService.getMethodSource( fullyQualifiedClassName, methodNames, methodSignature, javadoc );
    }

    @Tool( name = "getFilteredSource", description = "Returns one class's source with the import block and the bodies of the methods you did not ask for left out. "
            + "The content is exact - no line-number prefixes and no '// ... collapsed' comments - and every omission is a range in omittedRanges, "
            + "so a caller that wants one back reads it with readProjectResource(projectName, filePath, startLine, endLine). "
            + "status is PARTIAL whenever anything was omitted.",
            type = "object", outputType = ResourceReadResult.class )
    public ResourceReadResult getFilteredSource(
            @ToolParam( name = "fullyQualifiedClassName", description = "A fully qualified class name (e.g. 'com.example.MyClass')", required = true )
            String fullyQualifiedClassName,
            @ToolParam( name = "excludeImports", description = "Whether to collapse the import block (default: true)", required = false )
            String excludeImports,
            @ToolParam( name = "methodNames", description = "Comma-separated method names to fully expand. Methods not listed are collapsed to signatures. If omitted, all methods are expanded.", required = false )
            String methodNames )
    {
        boolean noImports = Optional.ofNullable( excludeImports ).map( Boolean::parseBoolean ).orElse( true );
        return outlineService.getFilteredSource( fullyQualifiedClassName, noImports, methodNames );
    }

    @Tool( name = "getProjectProperties", description = "Gets how a project is configured: its nature ids, the build descriptors in its root, and for a Java project its "
            + "compiler compliance level, output location and source folders. sourceFolders is the answer to 'where may a new class go?' and, like outputLocation, is "
            + "project-relative - the form the reading and editing tools take. status separates a name that does not exist (fix the name; listProjects has the real ones) "
            + "from a project that is closed (call openProject on its directory).",
            type = "object", outputType = ProjectPropertiesResponse.class )
    public ProjectPropertiesResponse getProjectProperties( @ToolParam( name = "projectName", description = "The name of the project to analyze", required = true )
    String projectName )
    {
        return projectService.getProjectProperties( projectName );
    }

    @Tool( name = "getProjectLayout", description = "Gets the file and folder tree of a project as nested nodes. Every node carries the project-relative filePath the reading and "
            + "editing tools take, and a folder reports childCount even when the walk stopped at it - so 'is there more under here?' is answerable. "
            + "truncated says whether maxDepth cut the listing short, and excludedCount how many entries .aiignore kept out. "
            + "For large projects use scopePath to limit to a subdirectory and/or maxDepth to limit tree depth.",
            type = "object", outputType = ProjectLayoutResponse.class )
    public ProjectLayoutResponse getProjectLayout( @ToolParam( name = "projectName", description = "The name of the project to analyze", required = true )
    String projectName,
            @ToolParam( name = "scopePath", description = "Optional path relative to the project root to limit the listing (e.g., 'src/main/java/com/example'). If omitted, shows the entire project.", required = false )
            String scopePath,
            @ToolParam( name = "maxDepth", description = "Optional maximum depth of the directory tree to display (e.g., '3' for 3 levels deep). If omitted, shows all levels.", required = false )
            String maxDepth )
    {
        Integer depth = Optional.ofNullable( maxDepth ).map( Integer::parseInt ).orElse( null );
        return projectService.getProjectLayout( projectName, scopePath, depth );
    }

    @Tool( name = "getMethodCallHierarchy", longExecution = true, description = "Finds the callers of a method, and what that method calls, to understand how it is used. "
            + "Each node reports projectName, filePath and a 1-based lineNumber - the same location triple findReferences returns - so a caller can be opened "
            + "without a follow-up search. depth is a field: 1 is a direct caller, 2 a caller of one of those. status distinguishes an unknown type from an unknown method.",
            type = "object", outputType = CallHierarchyResponse.class )
    public CallHierarchyResponse getMethodCallHierarchy(
            @ToolParam( name = "fullyQualifiedClassName", description = "The fully qualified name of the class containing the method", required = true )
            String fullyQualifiedClassName, @ToolParam( name = "methodName", description = "The name of the method to analyze", required = true )
            String methodName,
            @ToolParam( name = "methodSignature", description = "The signature of the method (optional, required if method is overloaded)", required = false )
            String methodSignature,
            @ToolParam( name = "maxDepth", description = "Maximum depth of the call hierarchy to retrieve (default: 3)", required = false )
            String maxDepth )
    {
        return codeAnalysisService.getMethodCallHierarchy( fullyQualifiedClassName, methodName, methodSignature,
                Optional.ofNullable( maxDepth ).map( Integer::parseInt ).orElse( 0 ) );
    }

    @Tool( name = "getCompilationErrors", description = "Retrieves compilation errors and problems from the current workspace, a specific project, or one folder or file within it. "
            + "Reports errorCount/warningCount for everything that matched, before any truncation, so 'are there errors?' is answerable "
            + "even from a shortened listing. Each problem carries its markerId and quick-fix indices for executeQuickFix.",
            type = "object", outputType = CompilationProblemsResponse.class )
    public CompilationProblemsResponse getCompilationErrors(
            @ToolParam( name = "projectName", description = "The name of the specific project to check (optional, leave empty for all projects)", required = false )
            String projectName,
            @ToolParam( name = "resourcePath", description = "Project-relative path of a folder or file to restrict the report to, e.g. 'src/com/example' or 'src/com/example/Foo.java' (optional, requires projectName)", required = false )
            String resourcePath,
            @ToolParam( name = "severity", description = "Filter by severity level: 'ERROR', 'WARNING', 'INFO' or 'ALL' (default)", required = false )
            String severity, @ToolParam( name = "maxResults", description = "Maximum number of problems to return (default: 50)", required = false )
            String maxResults )
    {
        return codeAnalysisService.getCompilationErrors( projectName, resourcePath, severity,
                Optional.ofNullable( maxResults ).map( Integer::parseInt ).orElse( 0 ) );
    }

    @Tool( name = "readProjectResource", description = "Read the content of a text resource from a specified project. "
            + "Returns the exact source text with no fence or line-number prefixes: the line the content starts at is returnedRange.startLine. "
            + "version.modificationStamp is the token to pass back as expectedModificationStamp when editing, so a write is rejected if the file "
            + "changed since the read. Supports line ranges and collapsing Java imports, which are reported in omittedRanges.",
            type = "object", outputType = ResourceReadResult.class )
    public ResourceReadResult readProjectResource( @ToolParam( name = "projectName", description = "The name of the project containing the resource", required = true )
    String projectName, @ToolParam( name = "resourcePath", description = "The path to the resource relative to the project root", required = true )
    String resourcePath,
            @ToolParam( name = "startLine", description = "Optional 1-based start line to read from. If omitted, reads from the beginning.", required = false )
            String startLine,
            @ToolParam( name = "endLine", description = "Optional 1-based end line to read to (inclusive). If omitted, reads to the end.", required = false )
            String endLine,
            @ToolParam( name = "excludeImports", description = "If 'true', omits a Java import block to save tokens. The omitted lines are reported in omittedRanges. Default: 'false'", required = false )
            String excludeImports )
    {
        int start = Optional.ofNullable( startLine ).map( Integer::parseInt ).orElse( 0 );
        int end = Optional.ofNullable( endLine ).map( Integer::parseInt ).orElse( 0 );
        boolean noImports = Optional.ofNullable( excludeImports ).map( Boolean::parseBoolean ).orElse( false );
        return resourceService.readResource( projectName, resourcePath, start, end, noImports );
    }

    @Tool( name = "readImageResource", description = "Reads a raster image from an Eclipse workspace project and returns it as MCP image content. Supported extensions: png, jpg, jpeg, gif, bmp, tif, tiff and ico. Maximum size: 20 MiB.", type = "object" )
    public McpSchema.ImageContent readImageResource(
            @ToolParam( name = "projectName", description = "The name of the project containing the image", required = true )
            String projectName, @ToolParam( name = "resourcePath", description = "The image path relative to the project root", required = true )
            String resourcePath )
    {
        ResourceService.ImageResource image = resourceService.readImageResource( projectName, resourcePath );
        String data = Base64.getEncoder().encodeToString( image.data() );
        var annotations = McpSchema.Annotations.builder().audience( List.of( McpSchema.Role.ASSISTANT ) ).build();
        return new McpSchema.ImageContent( annotations, data, image.mimeType(), null );
    }

    @Tool( name = "listProjects", description = "Lists the workspace projects. Each entry reports the projectName every other tool takes, "
            + "whether the project is open (a closed one cannot be read, searched or built until openProject runs), its nature ids "
            + "(org.eclipse.jdt.core.javanature for Java, org.eclipse.m2e.core.maven2Nature for Maven) and its filesystem location.",
            type = "object", outputType = ProjectListResponse.class )
    public ProjectListResponse listProjects()
    {
        return projectService.listProjects();
    }

    @Tool( name = "openProject", description = "Opens or imports a directory into the Eclipse workspace as a project. If the directory contains a .project file it is imported as-is; "
            + "if not, a description is created from the directory name. projectName is the name Eclipse assigned - taken from .project or from the directory name, and not "
            + "necessarily the last segment of directoryPath - and it is the argument every other tool takes next. status says which of three things happened: IMPORTED "
            + "(the workspace did not have it), OPENED (it had it, closed) or ALREADY_OPEN (nothing changed, which is an answer and not a failure).",
            type = "object", outputType = OpenProjectResponse.class )
    public OpenProjectResponse openProject( @ToolParam( name = "directoryPath", description = "The absolute filesystem path to the directory to open as a project" )
    String directoryPath )
    {
        return projectService.openProject( directoryPath );
    }

    @Tool( name = "getCurrentlyOpenedFile", description = "Gets the file the user currently has open in the Eclipse editor, with its exact content. "
            + "projectName and filePath are what the reading and editing tools take, and version.modificationStamp is the token an edit passes as "
            + "expectedModificationStamp. status is FAILED when no workspace file is open - a state of the workbench, not an error.",
            type = "object", outputType = ResourceReadResult.class )
    public ResourceReadResult getCurrentlyOpenedFile()
    {
        return editorService.readCurrentlyOpenedFile();
    }

    @Tool( name = "getEditorSelection", description = "Gets the text the user has selected in the active editor, as a range read of the open file. "
            + "returnedRange gives the exact 1-based start and end line and column of the selection, and totalLines the size of the whole file. "
            + "Nothing selected is an OK result with a zero-width range and empty content; status is FAILED only when no text editor is open.",
            type = "object", outputType = ResourceReadResult.class )
    public ResourceReadResult getEditorSelection()
    {
        return editorService.readEditorSelection();
    }

    @Tool( name = "getConsoleOutput", description = "Retrieves the recent output of Eclipse console(s). A console is read from its end, so returnedRange says which lines came back "
            + "out of totalLines and truncated says whether maxLines left earlier ones out - raise maxLines to reach them, a console has no line-range read. "
            + "totalConsoles says how many consoles exist, so you can tell the only console from one of several.",
            type = "object", outputType = ConsoleOutputResponse.class )
    public ConsoleOutputResponse getConsoleOutput(
            @ToolParam( name = "consoleName", description = "Name of the specific console to retrieve (optional, leave empty for all or most recent console)", required = false )
            String consoleName, @ToolParam( name = "maxLines", description = "Maximum number of lines to retrieve (default: 100)", required = false )
            String maxLines,
            @ToolParam( name = "includeAllConsoles", description = "If 'true', includes output from all available consoles. Default: 'false'", required = false )
            String includeAllConsoles )
    {
        // Every tool argument arrives as a String, so a declared Boolean parameter would
        // fail at Method.invoke with an argument type mismatch. Parse it here instead.
        boolean allConsoles = Optional.ofNullable( includeAllConsoles ).map( Boolean::parseBoolean ).orElse( false );
        return consoleService.getConsoleOutput( consoleName,
                Optional.ofNullable( maxLines ).map( Integer::parseInt ).orElse( null ), allConsoles );
    }


    // Unit Test Service Tools

    @Tool( name = "runJUnitTests",
           description = "Starts a JUnit test run asynchronously and returns an operationId for polling. "
               + "Scope is inferred from parameters: className+methodName=single method, "
               + "className=single class, packageName=package, none=all tests in project. "
               + "Use getOperationStatus to poll progress and results. "
               + "For PDE plug-in tests, use runJUnitPluginTests in the eclipse-pde server instead. "
               + "Publishes typed intermediate results while running: "
               + "'summary' (pass/fail counts) and 'results' (per-test details). "
               + "getOperationStatus will show these automatically while the run is in progress.",
           type = "object",
           longExecution = true,
           outputType = TestRunResponse.class )
    public TestRunResponse runJUnitTests(
            @ToolParam( name = "projectName",
                        description = "The exact Eclipse project name containing the test classes (use listProjects to find it)",
                        required = true )
            String projectName,
            @ToolParam( name = "className",
                        description = "The fully qualified class name (e.g. 'com.example.MyServiceTest'). "
                            + "If omitted, runs all tests or package tests.",
                        required = false )
            String className,
            @ToolParam( name = "methodName",
                        description = "The test method name (e.g. 'testCreate'). Requires className.",
                        required = false )
            String methodName,
            @ToolParam( name = "packageName",
                        description = "The fully qualified package name (e.g. 'com.example.service'). Ignored if className is set.",
                        required = false )
            String packageName,
            @ToolParam( name = "timeout",
                        description = "Maximum time in seconds to wait for test completion (default: 60)",
                        required = false )
            String timeout,
            @ToolParam( name = "withCoverage",
                        description = "If 'true', runs tests with code coverage (requires EclEmma/JaCoCo installed). Default: false",
                        required = false )
            String withCoverage,
            @ToolParam( name = "launcherName",
                        description = "Optional name of a saved launch configuration to use as the base "
                            + "(use (eclipse-runner MCP server).listLaunchConfigurations with typeFilter='junit' to find it). "
                            + "When set, all settings from that config are reused (VM args, classpath, env vars, etc.) "
                            + "and only the test target is overridden.",
                        required = false )
            String launcherName )
    {
        boolean coverage = Optional.ofNullable( withCoverage ).map( Boolean::parseBoolean ).orElse( false );
        int timeoutSeconds = Optional.ofNullable( timeout ).map( Integer::parseInt ).orElse( 60 );

        if ( className != null && !className.isBlank() && methodName != null && !methodName.isBlank() )
        {
            return unitTestService.runTestMethod( projectName, className, methodName, timeoutSeconds, coverage, launcherName );
        }
        else if ( className != null && !className.isBlank() )
        {
            return unitTestService.runClassTests( projectName, className, timeoutSeconds, coverage, launcherName );
        }
        else if ( packageName != null && !packageName.isBlank() )
        {
            return unitTestService.runPackageTests( projectName, packageName, timeoutSeconds, coverage, launcherName );
        }
        else
        {
            return unitTestService.runAllTests( projectName, timeoutSeconds, coverage, launcherName );
        }
    }

    @Tool( name = "findTestClasses", description = "Finds test classes and separates plain JUnit tests from PDE harness tests, which must follow the *PDETest naming convention. Flags likely PDE runtime usage in incorrectly named tests. Each class carries the project-relative path of its source file.", type = "object",
           outputType = TestClassesResponse.class )
    public TestClassesResponse findTestClasses(
            @ToolParam( name = "projectName", description = "The exact Eclipse project name to search (use listProjects to find it)", required = true )
            String projectName )
    {
        return unitTestService.findTestClasses( projectName );
    }

    // Maven Service Tools

    @Tool( name = "runMavenBuild", longExecution = true, type = "object", outputType = MavenBuildResponse.class,
           description = "Runs a Maven build on a project exactly the way the IDE's Run As > Maven build does: through m2e's Maven launch "
            + "configuration, in a separate JVM with the configured Maven runtime, so the complete Maven log - [INFO] and [ERROR] lines, compiler "
            + "and test output, the reactor summary - lands in the Console view under the launch's name. goals takes everything you would type "
            + "after 'mvn', options included, and passes it to Maven verbatim: 'clean verify', 'test -Dtest=FooTest', 'install -DskipTests -pl module -am'. "
            + "The result reports status (SUCCESS, FAILURE, RUNNING when the wait ran out, CANCELLED, FAILED_TO_START), the exit code, mavenCommand "
            + "(the command line as Maven received it), errorLines (every distinct [ERROR] line, in order) and output (the last lines of the log, "
            + "where the verdict and reactor summary are). The whole log is in the console named launchName: read it with "
            + "getConsoleOutput(consoleName=launchName). The launch configuration is saved, so the build can be rerun from the IDE." )
    public MavenBuildResponse runMavenBuild(
            @ToolParam( name = "projectName", description = "The Eclipse project to build (use listMavenProjects to find it). A Maven artifactId or groupId:artifactId is accepted when no project has that name.", required = true )
            String projectName,
            @ToolParam( name = "goals", description = "Everything after 'mvn': phases, goals and any options, passed to Maven verbatim, e.g. 'clean verify' or 'test -Dtest=FooTest -DfailIfNoTests=false'", required = true )
            String goals,
            @ToolParam( name = "profiles", description = "Optional comma-separated profiles to activate (Maven's -P)", required = false )
            String profiles,
            @ToolParam( name = "properties", description = "Optional comma-separated key=value system properties, each passed as -Dkey=value. A value that itself contains a comma goes in goals instead, as -Dkey=value.", required = false )
            String properties,
            @ToolParam( name = "pomDirectory", description = "Optional project-relative directory holding the pom.xml to build - a module of a multi-module project. Default: the project root", required = false )
            String pomDirectory,
            @ToolParam( name = "offline", description = "If 'true', works offline (-o). Default: false", required = false )
            String offline,
            @ToolParam( name = "updateSnapshots", description = "If 'true', forces a check for updated snapshots and releases (-U). Default: false", required = false )
            String updateSnapshots,
            @ToolParam( name = "skipTests", description = "If 'true', neither compiles nor runs tests (-Dmaven.test.skip=true -DskipTests). Default: false", required = false )
            String skipTests,
            @ToolParam( name = "debugOutput", description = "If 'true', asks Maven for debug output and full stack traces (-X -e). Default: false", required = false )
            String debugOutput,
            @ToolParam( name = "timeout", description = "Seconds to wait for the build before this call returns an operationId instead. The build carries on; getOperationStatus reports its output and, once it ends, this same result. Default: 60", required = false )
            String timeout )
    {
        return mavenService.runMavenBuild( projectName, goals, profiles, properties, pomDirectory,
                Boolean.parseBoolean( offline ), Boolean.parseBoolean( updateSnapshots ),
                Boolean.parseBoolean( skipTests ), Boolean.parseBoolean( debugOutput ),
                Optional.ofNullable( timeout ).filter( t -> !t.isBlank() ).map( t -> Integer.parseInt( t.trim() ) ).orElse( 60 ) );
    }

    @Tool( name = "updateMavenProject", longExecution = true, description = "Runs the equivalent of the IDE's 'Maven > Update Project' action: re-reads the pom, re-resolves dependencies and reconfigures the project's classpath. Use this after editing a pom.xml - until it runs, the workspace does not see the change, so a newly added dependency is not on the classpath and code using it still fails to compile.", type = "object" )
    public String updateMavenProject(
            @ToolParam( name = "projectName", description = "The Eclipse project to update (use listMavenProjects to find it); a Maven artifactId or groupId:artifactId is accepted when no project has that name", required = true )
            String projectName,
            @ToolParam( name = "forceDependencyUpdate", description = "If 'true', re-resolves snapshots and releases even when already cached (the 'Force Update of Snapshots/Releases' checkbox). Default: false", required = false )
            String forceDependencyUpdate,
            @ToolParam( name = "offline", description = "If 'true', resolves only from the local repository without reaching the network. Default: false", required = false )
            String offline )
    {
        boolean force = Optional.ofNullable( forceDependencyUpdate ).map( Boolean::parseBoolean ).orElse( false );
        boolean workOffline = Optional.ofNullable( offline ).map( Boolean::parseBoolean ).orElse( false );
        return mavenService.updateMavenProject( projectName, force, workOffline );
    }

    @Tool( name = "getEffectivePom", longExecution = true, description = "Gets the effective POM for a Maven project.", type = "object" )
    public String getEffectivePom( @ToolParam( name = "projectName", description = "The Eclipse project name (use listMavenProjects to find it); a Maven artifactId or groupId:artifactId is accepted when no project has that name", required = true )
    String projectName )
    {
        return mavenService.getEffectivePom( projectName );
    }

    @Tool( name = "listMavenProjects", description = "Lists the Maven projects m2e knows about in the workspace. Each entry reports both names: the Eclipse projectName "
            + "every other tool takes, and the groupId/artifactId/version/packaging a Maven command line takes. The two are frequently different strings.",
            type = "object", outputType = MavenProjectListResponse.class )
    public MavenProjectListResponse listMavenProjects()
    {
        return mavenService.listMavenProjects();
    }

    @Tool( name = "getProjectDependencies", longExecution = true, description = "Lists the dependencies one project's pom declares. These come from the Maven project model - "
            + "what the pom declares after inheritance from its parent - and not from the resolved transitive graph; for the fully resolved form use getEffectivePom. "
            + "version is null when the pom does not state one here, which is the ordinary case for a dependency managed by a parent's dependencyManagement. "
            + "scope is 'compile' when the pom omits it, the default Maven itself applies.",
            type = "object", outputType = MavenDependenciesResponse.class )
    public MavenDependenciesResponse getProjectDependencies( @ToolParam( name = "projectName", description = "The Eclipse project name (use listMavenProjects to find it); a Maven artifactId or groupId:artifactId is accepted when no project has that name", required = true )
    String projectName )
    {
        return mavenService.getProjectDependencies( projectName );
    }

    // Code Analysis Tools

    @Tool( name = "getTypeHierarchy", longExecution = true, description = "Retrieves the type hierarchy of a Java class or interface as three separate lists: "
            + "superclasses (nearest first), implemented interfaces and subtypes. A type whose source is in the workspace also reports the projectName "
            + "and project-relative filePath the reading and editing tools take; one from a JAR or the JRE reports neither. "
            + "status is TYPE_NOT_FOUND when no open Java project knows the name.",
            type = "object", outputType = TypeHierarchyResponse.class )
    public TypeHierarchyResponse getTypeHierarchy(
            @ToolParam( name = "fullyQualifiedClassName", description = "The fully qualified name of the class (e.g., 'com.example.MyClass')", required = true )
            String fullyQualifiedClassName )
    {
        return codeAnalysisService.getTypeHierarchy( fullyQualifiedClassName );
    }

    @Tool( name = "findReferences", longExecution = true, description = "Finds all references/usages of a Java type, method, or field across the entire workspace. "
            + "Essential before renaming or deleting code elements: totalReferences of 0 means nothing uses it. "
            + "Each reference reports projectName, filePath and a 1-based lineNumber.",
            type = "object", outputType = ReferencesResponse.class )
    public ReferencesResponse findReferences(
            @ToolParam( name = "fullyQualifiedClassName", description = "The fully qualified name of the class containing the element", required = true )
            String fullyQualifiedClassName,
            @ToolParam( name = "elementName", description = "Optional method or field name to search for. If omitted, searches for references to the class itself.", required = false )
            String elementName )
    {
        return codeAnalysisService.findReferences( fullyQualifiedClassName, elementName );
    }

    @Tool( name = "executeQuickFix", longExecution = true, description = "Applies one quick fix proposal to a compilation problem. Use getCompilationErrors first for the markerId and the proposal index. "
            + "status is APPLIED, MARKER_NOT_FOUND (the id is stale - re-run getCompilationErrors), NO_PROPOSALS, INVALID_PROPOSAL_INDEX (pick from availableProposals) "
            + "or APPLY_FAILED. On APPLIED, markerResolved says whether the problem actually went away.",
            type = "object", outputType = QuickFixResponse.class )
    public QuickFixResponse executeQuickFix(
            @ToolParam( name = "markerId", description = "The Marker ID of the problem (from getCompilationErrors or getQuickFixes)", required = true )
            String markerId,
            @ToolParam( name = "proposalIndex", description = "The 0-based index of the quick fix proposal to apply (from the quick fixes list)", required = true )
            String proposalIndex )
    {
        return codeAnalysisService.executeQuickFix( Long.parseLong( markerId ), Integer.parseInt( proposalIndex ) );
    }

    @Tool( name = "getImportSuggestions", longExecution = true, description = "Finds import candidates for the unresolved types in a Java file. Each candidate is a bare fully qualified name, ready to use. "
            + "totalUnresolvedTypes of 0 means the file has no unresolved names; totalCandidates of 0 means it has some but the workspace offers nothing for them. "
            + "status separates PROJECT_NOT_FOUND from PROJECT_CLOSED, which is one openProject call away.",
            type = "object", outputType = ImportSuggestionsResponse.class )
    public ImportSuggestionsResponse getImportSuggestions( @ToolParam( name = "projectName", description = "The name of the project containing the file", required = true )
    String projectName, @ToolParam( name = "filePath", description = "The path to the Java file relative to the project root", required = true )
    String filePath )
    {
        return codeAnalysisService.getImportSuggestions( projectName, filePath );
    }

    // Search Service Tools

    @Tool( name = "fileSearch", longExecution = true, description = "Searches for a plain substring in workspace files using Eclipse's text search engine. "
            + "Each match reports projectName, filePath and a 1-based lineNumber, which can be passed straight to the reading and editing tools.",
            type = "object", outputType = SearchResponse.class )
    public SearchResponse fileSearch(
            @ToolParam( name = "containingText", description = "Text that must be contained in a line (plain substring, not regex)", required = true )
            String containingText,
            @ToolParam( name = "fileNamePatterns", description = "Optional comma-separated file name patterns (e.g. \"*.java,*.xml\"). If omitted, all files are searched.", required = false )
            String fileNamePatterns,
            @ToolParam( name = "maxResults", description = "Maximum number of matches to return (default: 200). The response reports whether it was truncated.", required = false )
            String maxResults )
    {
        String[] patterns = splitFileNamePatterns( fileNamePatterns );
        return SearchResponse.from( containingText,
                searchService.fileSearch( containingText, patterns ), searchLimit( maxResults ) );
    }

    @Tool( name = "fileSearchRegExp", longExecution = true, description = "Searches workspace files using a Java regular expression via Eclipse's text search engine. "
            + "Each match reports projectName, filePath and a 1-based lineNumber.",
            type = "object", outputType = SearchResponse.class )
    public SearchResponse fileSearchRegExp( @ToolParam( name = "pattern", description = "Java regular expression", required = true )
    String pattern,
            @ToolParam( name = "fileNamePatterns", description = "Optional comma-separated file name patterns (e.g. \"*.java,*.xml\"). If omitted, all files are searched.", required = false )
            String fileNamePatterns,
            @ToolParam( name = "maxResults", description = "Maximum number of matches to return (default: 200). The response reports whether it was truncated.", required = false )
            String maxResults )
    {
        String[] patterns = splitFileNamePatterns( fileNamePatterns );
        return SearchResponse.from( pattern,
                searchService.fileSearchRegExp( pattern, patterns ), searchLimit( maxResults ) );
    }

    /** Matches are capped by default: a workspace-wide search can return thousands. */
    private static int searchLimit( String maxResults )
    {
        if ( maxResults == null || maxResults.isBlank() )
        {
            return 200;
        }
        try
        {
            return Integer.parseInt( maxResults.trim() );
        }
        catch ( NumberFormatException e )
        {
            return 200;
        }
    }

    @Tool( name = "findFiles", description = "Finds workspace files matching the given glob patterns. "
            + "Each file reports projectName and a project-relative filePath, which is what the reading and editing tools take.",
            type = "object", outputType = FileListResponse.class )
    public FileListResponse findFiles(
            @ToolParam( name = "fileNamePatterns", description = "Comma-separated glob patterns (e.g. \"*.java, pom.xml\"). If omitted, defaults to '*'", required = false )
            String fileNamePatterns, @ToolParam( name = "maxResults", description = "Maximum number of results to return (default: 200)", required = false )
            String maxResults )
    {
        String[] patterns = splitFileNamePatterns( fileNamePatterns );
        int limit = Optional.ofNullable( maxResults ).map( Integer::parseInt ).orElse( 0 );
        return FileListResponse.from( patterns, resourceService.findFiles( patterns, limit ), limit );
    }

    @Tool( name = "searchAndReplace", longExecution = true, description = "Search and replace across multiple files in the workspace using Eclipse's text search engine. "
            + "Reports per file how many occurrences were found and how many were replaced; the two differ when a file could not be fully updated.",
            type = "object", outputType = SearchReplaceResponse.class )
    public SearchReplaceResponse searchAndReplace( @ToolParam( name = "containingText", description = "Plain text to find (not regex)", required = true )
    String containingText, @ToolParam( name = "replacementText", description = "Replacement text (can be empty)", required = true )
    String replacementText,
            @ToolParam( name = "fileNamePatterns", description = "Optional comma-separated file name patterns (e.g. \"*.java,*.xml\"). If omitted, all files are searched.", required = false )
            String fileNamePatterns )
    {
        String[] patterns = splitFileNamePatterns( fileNamePatterns );
        return SearchReplaceResponse.from( containingText, replacementText,
                searchService.searchAndReplace( containingText, replacementText, patterns ) );
    }

    @Tool( name = "getMarkdownOutline", description = "Returns the heading structure (table of contents) of a Markdown file. Each heading carries its level, its 1-based index - which is "
            + "what getMarkdownSection takes, and unambiguous where two sections share a title - and the line range of the section it opens. "
            + "A file with no headings comes back as an empty list, not as a failure. "
            + "Use this to understand a large Markdown document before fetching sections with getMarkdownSection.",
            type = "object", outputType = MarkdownOutlineResponse.class )
    public MarkdownOutlineResponse getMarkdownOutline( @ToolParam( name = "projectName", description = "The name of the project containing the Markdown file", required = true )
    String projectName,
            @ToolParam( name = "resourcePath", description = "The path to the Markdown file relative to the project root (e.g., 'docs/README.md')", required = true )
            String resourcePath )
    {
        return markdownService.getOutline( projectName, resourcePath );
    }

    @Tool( name = "getMarkdownSection", description = "Reads one section of a Markdown file, addressed by heading text or by its 1-based index in the outline. "
            + "Returns the exact section text with no line-number prefixes: returnedRange says which lines of the file it is, out of totalLines, "
            + "and version.modificationStamp is the token an edit passes as expectedModificationStamp. Use getMarkdownOutline first to see available headings.",
            type = "object", outputType = ResourceReadResult.class )
    public ResourceReadResult getMarkdownSection( @ToolParam( name = "projectName", description = "The name of the project containing the Markdown file", required = true )
    String projectName, @ToolParam( name = "resourcePath", description = "The path to the Markdown file relative to the project root", required = true )
    String resourcePath,
            @ToolParam( name = "heading", description = "The heading to find â either a 1-based index from the outline, or a text substring to match (case-insensitive)", required = true )
            String heading,
            @ToolParam( name = "includeSubsections", description = "If 'true', includes all subsections under the matched heading. If 'false', returns only the content up to the next heading of any level. Default: true", required = false )
            String includeSubsections )
    {
        boolean includeSubs = Optional.ofNullable( includeSubsections ).map( Boolean::parseBoolean ).orElse( true );
        return markdownService.getSection( projectName, resourcePath, heading, includeSubs );
    }

    /**
     * Splits the comma-separated pattern list the tool descriptions document, e.g.
     * {@code "*.java, *.xml, test.http"}.
     * <p>
     * Every tool argument reaches this class as a String, so there is nothing else to
     * accept: the previous {@code Object} parameter only ever worked because Jackson
     * happened to hand over a List or a String, and a declared array would have failed
     * at {@code Method.invoke}.
     */
    private static String[] splitFileNamePatterns( String fileNamePatterns )
    {
        if ( fileNamePatterns == null || fileNamePatterns.isBlank() )
        {
            return new String[0];
        }
        return fileNamePatterns.trim().split( "\\s*,\\s*" );
    }

    private static Integer parseOptionalInt( String value )
    {
        if ( value == null || value.isBlank() )
        {
            return null;
        }
        try
        {
            return Integer.parseInt( value.trim() );
        }
        catch ( NumberFormatException e )
        {
            return null;
        }
    }


    @Tool( name = "searchTypes",
           longExecution = true,
           description = "Searches for Java types (classes, interfaces, enums, records, annotations) by name pattern. "
                       + "This is the primary discovery tool — use it FIRST when a user mentions a concept (e.g. 'payment handling', "
                       + "'authentication') and you need to find which classes implement it. "
                       + "Supports wildcards (* and ?), CamelCase matching (e.g. 'PS' finds 'PaymentService'), and prefix matching. "
                       + "Prefer this over fileSearch for finding types: it searches the JDT index (instant) rather than file contents, "
                       + "and supports CamelCase patterns that text search cannot. "
                       + "After finding types, use getClassOutline or getPackageSummary to understand them, "
                       + "then getMethodSource to read specific methods.",
           type = "object",
           outputType = TypeSearchResponse.class )
    public TypeSearchResponse searchTypes(
            @ToolParam( name = "pattern", description = "Type name pattern. Supports: "
                                                      + "wildcards (*Payment*, *Service, Error*), "
                                                      + "CamelCase (PS -> PaymentService, TxH -> TransactionHandler, CC -> CreditCard), "
                                                      + "prefix (Payment -> PaymentService, PaymentProcessor, ...), "
                                                      + "or package-qualified (com.example.*Service). "
                                                      + "Tips: try multiple patterns for a concept — e.g. for 'payment' try '*Payment*', '*Billing*', '*Transaction*'.", required = true )
            String pattern,
            @ToolParam( name = "maxResults", description = "Maximum number of results to return (default: 100)", required = false )
            String maxResults )
    {
        Integer limit = parseOptionalInt( maxResults );
        return codeDiscoveryService.searchTypes( pattern, limit );
    }

    @Tool( name = "searchMethods",
           longExecution = true,
           description = "Searches for methods by name pattern across the entire workspace. "
                       + "Use this when you know (or can guess) a method name but don't know which class contains it. "
                       + "For example, if a user says 'fix the error handling', search for '*error*' or 'handle*' to find relevant methods. "
                       + "Supports wildcards (* and ?), CamelCase matching, and prefix matching. "
                       + "Optionally filter by declaring type to narrow results. "
                       + "Returns the method name, declaring class, package, parameter types, and return type. "
                       + "After finding a method, use getMethodSource to read its implementation.",
           type = "object",
           outputType = MethodSearchResponse.class )
    public MethodSearchResponse searchMethods(
            @ToolParam( name = "pattern", description = "Method name pattern. Supports: "
                                                      + "wildcards (handle*Error, get*, *Payment, process*), "
                                                      + "CamelCase (pP -> processPayment — requires 2+ uppercase letters), "
                                                      + "or prefix (handle -> handleError, handleTimeout, ...). "
                                                      + "Note: CamelCase and prefix patterns are case-sensitive; use wildcards (*foo*) for case-insensitive matching.", required = true )
            String pattern,
            @ToolParam( name = "declaringTypePattern", description = "Optional pattern to filter by declaring type name (e.g. '*Service', 'Payment*'). "
                                                                   + "Useful when the method name is common (e.g. 'get*') and you want to narrow to specific classes.", required = false )
            String declaringTypePattern,
            @ToolParam( name = "maxResults", description = "Maximum number of results to return (default: 100)", required = false )
            String maxResults )
    {
        Integer limit = parseOptionalInt( maxResults );
        return codeDiscoveryService.searchMethods( pattern, declaringTypePattern, limit );
    }

    @Tool( name = "getPackageSummary",
           longExecution = true,
           description = "Returns a table-of-contents for a Java package: every type's name, kind (class/interface/enum/record), "
                       + "Javadoc first sentence, method count, field count, and implemented interfaces — all in one call. "
                       + "Use this after searchTypes or getWorkspaceOverview identifies a relevant package, "
                       + "to understand what the package contains without reading each file individually. "
                       + "The Javadoc summaries help you decide which types are relevant to the user's request. "
                       + "Follow up with getClassOutline or getMethodSource on specific types of interest.",
           type = "object",
           outputType = PackageSummaryResponse.class )
    public PackageSummaryResponse getPackageSummary(
            @ToolParam( name = "packageName", description = "Fully qualified package name (e.g. 'com.example.payment', 'org.acme.auth.service')", required = true )
            String packageName,
            @ToolParam( name = "projectName", description = "Optional project name to narrow the search. Useful in multi-project workspaces.", required = false )
            String projectName )
    {
        return codeDiscoveryService.getPackageSummary( packageName, projectName );
    }

    @Tool( name = "getWorkspaceOverview",
           longExecution = true,
           description = "Returns a high-level architectural map of the workspace: all projects, their source packages, "
                       + "and the type names in each package. Use this as the FIRST tool when orienting in an unfamiliar codebase "
                       + "or when you need to understand the overall project structure before making changes. "
                       + "Typical workflow: getWorkspaceOverview -> identify relevant packages -> getPackageSummary on those packages "
                       + "-> getClassOutline/getMethodSource on specific types. "
                       + "For large workspaces, use projectFilter to focus on specific projects.",
           type = "object",
           outputType = WorkspaceOverviewResponse.class )
    public WorkspaceOverviewResponse getWorkspaceOverview(
            @ToolParam( name = "projectFilter", description = "Optional substring to filter projects by name (e.g. 'payment' shows only payment-related projects, "
                                                            + "'service' shows service projects). Leave empty to see all projects.", required = false )
            String projectFilter,
            @ToolParam( name = "maxPackagesPerProject", description = "Maximum number of packages to show per project (default: 50). "
                                                                    + "Lower this for large projects to get a quick overview.", required = false )
            String maxPackagesPerProject )
    {
        Integer maxPkgs = parseOptionalInt( maxPackagesPerProject );
        return codeDiscoveryService.getWorkspaceOverview( projectFilter, maxPkgs );
    }

}