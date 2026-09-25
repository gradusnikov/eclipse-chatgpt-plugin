package com.github.gradusnikov.eclipse.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
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
import com.github.gradusnikov.eclipse.assistai.mcp.McpJson;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.MarkdownOutlineResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.MethodSourceResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.ProjectLayoutResponse;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceReadResult;

/**
 * The batch 2 conversions, against a real workspace.
 * <p>
 * These five tools all used to return prose with the line numbers baked into it - a
 * {@code // Class.method (lines 40-47)} banner, a {@code %5d\t} prefix, a
 * {@code // ... (lines 51-60)} stand-in for a collapsed body, a Markdown bullet tree.
 * What is asserted here is the property that made those renderings a problem: the text
 * a caller gets back is now exactly what is in the file, and everything else is a
 * field. Nothing here asserts on wording.
 */
public class StructuredReadServicesPDETest
{
    private static final String PACKAGE_NAME = "com.example.batchtwo";

    private static final String TYPE_NAME    = PACKAGE_NAME + ".Sample";

    private static final String SAMPLE_SOURCE = """
            package com.example.batchtwo;

            import java.util.List;
            import java.util.Map;

            public class Sample
            {
                private int count;

                /**
                 * Finds a row.
                 */
                public String findById( String id )
                {
                    return id;
                }

                public void save( List<String> rows )
                {
                    count++;
                }

                public Map<String, String> all()
                {
                    return Map.of();
                }
            }
            """;

    private static final String GUIDE_SOURCE = """
            # Title

            intro paragraph

            ## Alpha

            alpha body

            ### Alpha detail

            detail body

            ## Beta

            beta body
            """;

    private String                projectName;

    private IProject              project;

    private final NullProgressMonitor monitor = new NullProgressMonitor();

    private OutlineService        outlineService;

    private MarkdownService       markdownService;

    private ProjectService        projectService;

    @BeforeEach
    public void beforeEach() throws CoreException, IOException, InterruptedException
    {
        BundleContext bundleContext =
                FrameworkUtil.getBundle( StructuredReadServicesPDETest.class ).getBundleContext();
        ServiceTracker<IWorkspace, IWorkspace> workspaceTracker =
                new ServiceTracker<>( bundleContext, IWorkspace.class, null );
        workspaceTracker.open();
        IWorkspaceRoot root = workspaceTracker.getService().getRoot();

        projectName = "StructuredReadTestProject_" + UUID.randomUUID();
        project = root.getProject( projectName );
        if ( project.exists() )
        {
            project.delete( true, true, monitor );
        }

        IProjectDescription description = project.getWorkspace().newProjectDescription( projectName );
        project.create( description, monitor );
        project.open( monitor );

        IProjectDescription openDescription = project.getDescription();
        openDescription.setNatureIds( new String[] { JavaCore.NATURE_ID } );
        project.setDescription( openDescription, monitor );

        IJavaProject javaProject = JavaCore.create( project );
        javaProject.setOption( JavaCore.COMPILER_COMPLIANCE, "21" );
        javaProject.setOption( JavaCore.COMPILER_SOURCE, "21" );
        javaProject.setOption( JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, "21" );

        IFolder bin = project.getFolder( "bin" );
        // Adding the Java nature above can trigger a build that creates the default "bin"
        // output folder before we get here, so guard like the other PDE tests that create
        // one of these ad-hoc projects do.
        if ( !bin.exists() ) bin.create( true, true, monitor );
        javaProject.setOutputLocation( bin.getFullPath(), monitor );

        IFolder src = project.getFolder( "src" );
        src.create( IResource.NONE, true, monitor );
        javaProject.setRawClasspath( new IClasspathEntry[] {
                JavaCore.newSourceEntry( project.getFullPath().append( "src" ) ),
                JavaRuntime.getDefaultJREContainerEntry() }, monitor );

        IFolder packageFolder = src.getFolder( "com" ).getFolder( "example" ).getFolder( "batchtwo" );
        src.getFolder( "com" ).create( IResource.NONE, true, monitor );
        src.getFolder( "com" ).getFolder( "example" ).create( IResource.NONE, true, monitor );
        packageFolder.create( IResource.NONE, true, monitor );
        write( packageFolder.getFile( "Sample.java" ), SAMPLE_SOURCE );

        IFolder docs = project.getFolder( "docs" );
        docs.create( IResource.NONE, true, monitor );
        write( docs.getFile( "guide.md" ), GUIDE_SOURCE );

        project.build( IncrementalProjectBuilder.FULL_BUILD, monitor );
        Job.getJobManager().join( ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor );
        Job.getJobManager().join( ResourcesPlugin.FAMILY_AUTO_BUILD, monitor );
        Thread.sleep( 500 );

        IEclipseContext context = EclipseContextFactory.create();
        context.set( ILog.class, Activator.getDefault().getLog() );
        outlineService = ContextInjectionFactory.make( OutlineService.class, context );
        markdownService = ContextInjectionFactory.make( MarkdownService.class, context );
        projectService = ContextInjectionFactory.make( ProjectService.class, context );
    }

    private void write( IFile file, String content ) throws CoreException
    {
        file.create( new ByteArrayInputStream( content.getBytes( StandardCharsets.UTF_8 ) ), true, monitor );
    }

    @AfterEach
    public void afterEach() throws CoreException, InterruptedException
    {
        Job.getJobManager().join( ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor );
        Job.getJobManager().join( ResourcesPlugin.FAMILY_AUTO_BUILD, monitor );
        Thread.sleep( 300 );

        if ( project != null && project.exists() )
        {
            if ( project.isOpen() )
            {
                project.close( monitor );
            }
            for ( int attempt = 0; attempt < 5; attempt++ )
            {
                try
                {
                    project.delete( true, true, monitor );
                    break;
                }
                catch ( CoreException e )
                {
                    if ( attempt == 4 )
                    {
                        project.delete( false, true, monitor );
                        break;
                    }
                    Thread.sleep( 500 );
                }
            }
        }
    }

    // ---- getMethodSource -------------------------------------------------

    @Test
    public void methodSourceIsExactTextWithTheLinesInARange()
    {
        MethodSourceResponse response =
                outlineService.getMethodSource( TYPE_NAME, "findById", null, false );

        assertEquals( MethodSourceResponse.Status.OK, response.status(),
                () -> String.valueOf( response.diagnostics() ) );
        MethodSourceResponse.MethodSource method = response.methods().get( 0 );

        // Whole lines, indentation included, so the text is literally lines
        // startLine..endLine of the file and can be diffed against it.
        assertEquals( "    public String findById( String id )\n", firstLineOf( method.source() ) );
        assertTrue( method.source().contains( "        return id;" ), method.source() );
        assertFalse( method.source().contains( "/**" ),
                "includeJavadoc was false, and JDT's range starts at the Javadoc" );
        assertFalse( method.source().contains( "// Sample.findById" ),
                "the banner comment is what the range replaced" );
        assertFalse( method.source().contains( "\t" ),
                "the %5d\\t line-number prefix must be gone" );

        // The range says where the text came from, and it has to agree with the file.
        assertTrue( method.range().startLine() > 1 );
        assertEquals( 4, method.lineCount(), "signature, two braces and one statement" );
        assertEquals( "String id", method.parameters() );
    }

    /**
     * {@code includeJavadoc} had never had any effect: JDT's method source range
     * already begins at the Javadoc, and the old code took {@code min()} of the range's
     * own start and an offset inside it. So the flag was documented, accepted, and
     * dead - the Javadoc came back either way.
     */
    @Test
    public void includeJavadocDecidesWhetherTheJavadocIsReturned()
    {
        MethodSourceResponse.MethodSource withDoc =
                outlineService.getMethodSource( TYPE_NAME, "findById", null, true ).methods().get( 0 );
        MethodSourceResponse.MethodSource withoutDoc =
                outlineService.getMethodSource( TYPE_NAME, "findById", null, false ).methods().get( 0 );

        assertTrue( withDoc.source().contains( "Finds a row." ), withDoc.source() );
        assertFalse( withoutDoc.source().contains( "Finds a row." ), withoutDoc.source() );

        assertTrue( withDoc.range().startLine() < withoutDoc.range().startLine(),
                "dropping the Javadoc has to move the reported start line, or the range lies" );
        assertEquals( withDoc.range().endLine(), withoutDoc.range().endLine() );
    }

    private static String firstLineOf( String text )
    {
        int newline = text.indexOf( '\n' );
        return newline < 0 ? text : text.substring( 0, newline + 1 );
    }

    @Test
    public void methodSourceListsWhatItCouldNotFind()
    {
        MethodSourceResponse response =
                outlineService.getMethodSource( TYPE_NAME, "findById,noSuchMethod", null, false );

        assertEquals( MethodSourceResponse.Status.PARTIAL, response.status() );
        assertEquals( List.of( "noSuchMethod" ), response.notFound() );
        assertEquals( 1, response.methods().size() );
        assertEquals( projectName, response.projectName() );
        assertEquals( "src/com/example/batchtwo/Sample.java", response.filePath(),
                "a project-relative path is what the reading and editing tools take" );
    }

    @Test
    public void methodSourceReportsAnUnknownTypeAsACode()
    {
        MethodSourceResponse response =
                outlineService.getMethodSource( "com.example.batchtwo.NotHere", "any", null, false );

        assertEquals( MethodSourceResponse.Status.FAILED, response.status() );
        assertEquals( DiagnosticCode.RESOURCE_NOT_FOUND, response.diagnostics().get( 0 ).code() );
    }

    @Test
    public void methodSourceRejectsAnEmptyRequestWithACode()
    {
        MethodSourceResponse response = outlineService.getMethodSource( TYPE_NAME, "  ", null, false );

        assertEquals( MethodSourceResponse.Status.FAILED, response.status() );
        assertFalse( response.diagnostics().isEmpty() );
    }

    @Test
    public void methodSourceCarriesTheStampAnEditQuotes()
    {
        MethodSourceResponse response = outlineService.getMethodSource( TYPE_NAME, "save", null, false );

        assertNotNull( response.version() );
        assertTrue( response.version().isKnown(),
                "without a modification stamp there is nothing to pass as expectedModificationStamp" );
    }

    // ---- getFilteredSource -----------------------------------------------

    @Test
    public void filteredSourceReportsOmissionsAsRangesNotComments()
    {
        ResourceReadResult result = outlineService.getFilteredSource( TYPE_NAME, true, "findById" );

        assertEquals( ResourceReadResult.ReadStatus.PARTIAL, result.status(),
                () -> String.valueOf( result.diagnostics() ) );
        assertFalse( result.omittedRanges().isEmpty() );
        assertFalse( result.content().contains( "// ..." ),
                "a collapsed body is a range now, not a comment spliced into the code" );
        assertFalse( result.content().contains( "import java.util.List" ),
                "the import block was asked to be left out" );

        // The expanded method keeps its body; the others lose theirs but keep their
        // signatures, so the file still reads as Java.
        assertTrue( result.content().contains( "return id;" ) );
        assertTrue( result.content().contains( "public void save( List<String> rows )" ) );
        assertFalse( result.content().contains( "count++;" ) );
    }

    @Test
    public void filteredSourceOmitsNothingWhenNothingWasAskedToBeOmitted()
    {
        ResourceReadResult result = outlineService.getFilteredSource( TYPE_NAME, false, null );

        assertEquals( ResourceReadResult.ReadStatus.OK, result.status() );
        assertTrue( result.omittedRanges().isEmpty() );
        assertTrue( result.content().contains( "import java.util.List" ) );
        assertTrue( result.content().contains( "count++;" ) );
        assertFalse( result.truncated(), "the content runs to the last line; it has holes, not an end" );
    }

    @Test
    public void filteredSourceLocatesItselfWhereTheEditingToolsCanUseIt()
    {
        ResourceReadResult result = outlineService.getFilteredSource( TYPE_NAME, true, null );

        assertEquals( projectName, result.projectName() );
        assertEquals( "src/com/example/batchtwo/Sample.java", result.filePath() );
        assertTrue( result.version().isKnown() );
        assertTrue( result.totalLines() > result.omittedRanges().size() );
    }

    // ---- markdown --------------------------------------------------------

    @Test
    public void markdownOutlineNumbersItsHeadings()
    {
        MarkdownOutlineResponse outline = markdownService.getOutline( projectName, "docs/guide.md" );

        assertEquals( MarkdownOutlineResponse.Status.OK, outline.status(),
                () -> String.valueOf( outline.diagnostics() ) );
        assertEquals( 4, outline.headings().size() );

        assertEquals( 1, outline.headings().get( 0 ).index() );
        assertEquals( 1, outline.headings().get( 0 ).level() );
        assertEquals( "Title", outline.headings().get( 0 ).text() );
        assertEquals( 3, outline.headings().get( 2 ).level(), "### Alpha detail" );
        assertEquals( "docs/guide.md", outline.filePath() );
    }

    @Test
    public void markdownSectionIsTheExactLinesOfOneSection()
    {
        MarkdownOutlineResponse outline = markdownService.getOutline( projectName, "docs/guide.md" );
        MarkdownOutlineResponse.Heading alpha = outline.headings().get( 1 );

        ResourceReadResult section =
                markdownService.getSection( projectName, "docs/guide.md", "2", true );

        assertEquals( ResourceReadResult.ReadStatus.PARTIAL, section.status() );
        assertEquals( alpha.range().startLine(), section.returnedRange().startLine(),
                "the outline's index and the section's range must agree" );
        assertTrue( section.content().startsWith( "## Alpha" ), section.content() );
        assertTrue( section.content().contains( "### Alpha detail" ),
                "includeSubsections was true" );
        assertFalse( section.content().contains( "## Beta" ) );
        assertFalse( section.content().contains( "\t" ), "no line-number prefixes" );
        assertEquals( outline.totalLines(), section.totalLines() );
    }

    @Test
    public void markdownSectionStopsAtTheNextHeadingWhenSubsectionsAreExcluded()
    {
        ResourceReadResult section =
                markdownService.getSection( projectName, "docs/guide.md", "Alpha", false );

        assertTrue( section.content().startsWith( "## Alpha" ), section.content() );
        assertFalse( section.content().contains( "### Alpha detail" ) );
    }

    @Test
    public void markdownSectionReportsAnUnknownHeadingAsACode()
    {
        ResourceReadResult section =
                markdownService.getSection( projectName, "docs/guide.md", "Gamma", true );

        assertEquals( ResourceReadResult.ReadStatus.FAILED, section.status() );
        assertEquals( DiagnosticCode.RESOURCE_NOT_FOUND, section.diagnostics().get( 0 ).code() );
        assertEquals( "", section.content(), "a failure must not look like an empty section" );
    }

    @Test
    public void markdownOutlineReportsAMissingFileAsACode()
    {
        MarkdownOutlineResponse outline = markdownService.getOutline( projectName, "docs/absent.md" );

        assertEquals( MarkdownOutlineResponse.Status.FAILED, outline.status() );
        assertEquals( DiagnosticCode.RESOURCE_NOT_FOUND, outline.diagnostics().get( 0 ).code() );
    }

    // ---- getProjectLayout ------------------------------------------------

    @Test
    public void layoutIsATreeOfProjectRelativePaths()
    {
        ProjectLayoutResponse layout = projectService.getProjectLayout( projectName, null, null );

        assertEquals( ProjectLayoutResponse.Status.OK, layout.status(),
                () -> String.valueOf( layout.diagnostics() ) );
        assertEquals( ProjectLayoutResponse.NodeType.PROJECT, layout.root().type() );
        assertEquals( "", layout.root().filePath() );
        assertFalse( layout.truncated(), "an unlimited walk cuts nothing" );

        ProjectLayoutResponse.Node docs = child( layout.root(), "docs" );
        assertEquals( ProjectLayoutResponse.NodeType.FOLDER, docs.type() );
        assertEquals( "docs", docs.filePath() );

        ProjectLayoutResponse.Node guide = child( docs, "guide.md" );
        assertEquals( ProjectLayoutResponse.NodeType.FILE, guide.type() );
        assertEquals( "docs/guide.md", guide.filePath(),
                "this path has to be usable by readProjectResource without a second lookup" );
        assertEquals( 0, guide.childCount() );
    }

    @Test
    public void layoutSaysWhereADepthLimitStoppedIt()
    {
        ProjectLayoutResponse layout = projectService.getProjectLayout( projectName, null, 1 );

        assertTrue( layout.truncated(), "docs and src both have contents that were not listed" );
        assertEquals( 1, layout.maxDepth() );

        ProjectLayoutResponse.Node docs = child( layout.root(), "docs" );
        assertTrue( docs.children().isEmpty() );
        assertEquals( 1, docs.childCount(), "the folder still says how much is behind it" );
        assertTrue( docs.isCollapsed() );
    }

    @Test
    public void layoutScopesToASubdirectoryWithoutCallingItTruncation()
    {
        ProjectLayoutResponse layout = projectService.getProjectLayout( projectName, "docs", null );

        assertEquals( "docs", layout.scopePath() );
        assertEquals( "docs", layout.root().name() );
        assertFalse( layout.truncated(), "a scope the caller chose is reported as a scope, not as a cut" );
        assertEquals( 1, layout.listedFiles() );
    }

    @Test
    public void layoutReportsAMissingProjectAsACode()
    {
        ProjectLayoutResponse layout =
                projectService.getProjectLayout( "NoSuchProject_" + UUID.randomUUID(), null, null );

        assertEquals( ProjectLayoutResponse.Status.FAILED, layout.status() );
        assertEquals( DiagnosticCode.PROJECT_NOT_FOUND, layout.diagnostics().get( 0 ).code() );
    }

    @Test
    public void layoutReportsAMissingScopeAsACode()
    {
        ProjectLayoutResponse layout = projectService.getProjectLayout( projectName, "nowhere", null );

        assertEquals( ProjectLayoutResponse.Status.FAILED, layout.status() );
        assertEquals( DiagnosticCode.RESOURCE_NOT_FOUND, layout.diagnostics().get( 0 ).code() );
    }

    // ---- the chat's <resources> block ------------------------------------

    /**
     * The coupling this whole batch exists for.
     * <p>
     * {@code ExecuteFunctionCallJob} decides whether a tool result is a cacheable read
     * by pushing {@code structuredContent} - already flattened to a map by the time it
     * arrives - through {@link ResourceReadResult#fromStructuredContent}, then
     * resolving {@code projectName} and {@code filePath} to an {@link IFile}. If any of
     * that stops matching, nothing fails: the &lt;resources&gt; block simply stops
     * filling. So the hop is exercised here on the real output of the converted tools.
     */
    @Test
    public void aConvertedReadIsStillRecognisedAsCacheableByTheChat()
    {
        for ( ResourceReadResult read : List.of(
                outlineService.getFilteredSource( TYPE_NAME, true, "findById" ),
                markdownService.getSection( projectName, "docs/guide.md", "Alpha", true ) ) )
        {
            ResourceReadResult recovered =
                    ResourceReadResult.fromStructuredContent( McpJson.toMap( read ) );

            assertNotNull( recovered, "the chat cannot cache a read it cannot recognise" );
            assertTrue( recovered.isCacheable() );
            assertEquals( read.content(), recovered.content(),
                    "the cached content must be what the tool returned, exactly" );

            // And the location has to resolve, because that is the next thing the job
            // does with it.
            IFile file = ResourcesPlugin.getWorkspace().getRoot()
                    .getProject( recovered.projectName() ).getFile( recovered.filePath() );
            assertTrue( file.exists(), recovered.projectName() + "/" + recovered.filePath() );
        }
    }

    @Test
    public void aResultThatIsNotAReadIsNotMistakenForOne()
    {
        // These three replaced tools that used to be cached through the
        // __resourceCache__ envelope. They are not file reads and must not be
        // recognised as such - a console has no path and a tree has no content.
        assertNull( ResourceReadResult.fromStructuredContent( McpJson.toMap(
                outlineService.getMethodSource( TYPE_NAME, "findById", null, false ) ) ) );
        assertNull( ResourceReadResult.fromStructuredContent( McpJson.toMap(
                projectService.getProjectLayout( projectName, null, null ) ) ) );
        assertNull( ResourceReadResult.fromStructuredContent( McpJson.toMap(
                markdownService.getOutline( projectName, "docs/guide.md" ) ) ) );
    }

    private static ProjectLayoutResponse.Node child( ProjectLayoutResponse.Node parent, String name )
    {
        return parent.children().stream()
                .filter( node -> name.equals( node.name() ) )
                .findFirst()
                .orElseThrow( () -> new AssertionError(
                        "no child named '" + name + "' in " + parent.children().stream()
                                .map( ProjectLayoutResponse.Node::name ).toList() ) );
    }
}
