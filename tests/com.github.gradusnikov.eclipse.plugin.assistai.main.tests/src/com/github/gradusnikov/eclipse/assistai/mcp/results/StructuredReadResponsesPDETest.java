package com.github.gradusnikov.eclipse.assistai.mcp.results;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.mcp.McpJson;
import com.github.gradusnikov.eclipse.assistai.mcp.McpOutputSchemas;
import com.github.gradusnikov.eclipse.assistai.resources.ContentRange;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceVersion;

/**
 * The four records batch 2 added for results that are not a plain file read:
 * {@link MethodSourceResponse}, {@link ConsoleOutputResponse},
 * {@link MarkdownOutlineResponse} and {@link ProjectLayoutResponse}.
 * <p>
 * Every one of them replaced text whose line numbers, nesting or "not found" note were
 * things a caller had to parse back out of prose. What is checked here is that the
 * facts are fields, that the generated schema names them, and - the trap batch 1 hit -
 * that the payload contains exactly the fields the schema advertises, no more.
 */
public class StructuredReadResponsesPDETest
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
        return SchemaTypes.nonNull( array.get( "items" ) );
    }

    @SuppressWarnings( "unchecked" )
    private static Map<String, Object> propertiesOf( Map<String, Object> objectSchema )
    {
        return SchemaTypes.propertiesOf( objectSchema );
    }

    /**
     * The guard that caught a derived accessor leaking into the payload during batch 1.
     * A tool that promises one shape and sends another cannot read its own result back.
     */
    private static void payloadMatchesSchema( Object payload )
    {
        assertEquals( properties( payload.getClass() ).keySet(), McpJson.toMap( payload ).keySet(),
                payload.getClass().getSimpleName() + " must serialize exactly the fields it advertises" );
    }

    // ---- method source ---------------------------------------------------

    private static MethodSourceResponse aMethodRead()
    {
        return MethodSourceResponse.of( "com.example.Repo", "P", "src/com/example/Repo.java",
                new ResourceVersion( 4711L, 1700000000000L, null, true ),
                List.of( new MethodSourceResponse.MethodSource( "findById", "String id",
                        new ContentRange( 40, 1, 47, 1 ), "public Row findById( String id )\n{\n}\n" ) ),
                List.of( "save" ) );
    }

    @Test
    public void methodSourceSerializesExactlyTheFieldsItAdvertises()
    {
        payloadMatchesSchema( aMethodRead() );
    }

    @Test
    public void methodSourceCarriesTheLinesInsteadOfPrintingThem()
    {
        Map<String, Object> method = propertiesOf( itemsOf( properties( MethodSourceResponse.class ), "methods" ) );

        // The banner and the "%5d\t" prefix are gone; the range is where the numbers
        // live now, and the source has to be exact for an edit built on it to apply.
        assertTrue( method.containsKey( "range" ) );
        assertTrue( method.containsKey( "source" ) );
        assertTrue( method.containsKey( "parameters" ),
                "the parameter list is what disambiguates an overload" );

        assertEquals( "public Row findById( String id )\n{\n}\n", aMethodRead().methods().get( 0 ).source(),
                "source must carry no line-number prefixes" );
        assertEquals( 8, aMethodRead().methods().get( 0 ).lineCount() );
    }

    @Test
    public void methodSourceReportsAMissingMethodAsAListNotAComment()
    {
        MethodSourceResponse response = aMethodRead();

        assertEquals( List.of( "save" ), response.notFound() );
        assertEquals( MethodSourceResponse.Status.PARTIAL, response.status(),
                "asking for two methods and getting one is a partial answer, not a success" );
    }

    @Test
    public void methodSourceIsOkOnlyWhenNothingWasMissed()
    {
        MethodSourceResponse response = MethodSourceResponse.of( "com.example.Repo", "P", "src/Repo.java",
                ResourceVersion.UNKNOWN, List.of(), List.of() );

        assertEquals( MethodSourceResponse.Status.OK, response.status() );
        assertTrue( response.notFound().isEmpty() );
    }

    @Test
    public void methodSourceReportsAMissingTypeAsACode()
    {
        MethodSourceResponse failed = MethodSourceResponse.failed( "com.example.Gone",
                Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_FOUND, "no such type" ) );

        assertEquals( MethodSourceResponse.Status.FAILED, failed.status() );
        assertEquals( DiagnosticCode.RESOURCE_NOT_FOUND, failed.diagnostics().get( 0 ).code() );
        assertTrue( failed.methods().isEmpty(),
                "a lookup that could not happen must not look like a type with no methods" );
    }

    // ---- console ---------------------------------------------------------

    private static ConsoleOutputResponse aConsoleRead( int totalConsoles )
    {
        return ConsoleOutputResponse.of( totalConsoles, List.of( new ConsoleOutputResponse.ConsoleOutput(
                "Maven Build", new ContentRange( 901, 1, 1000, 1 ), 1000, true, "[INFO] BUILD SUCCESS\n" ) ) );
    }

    @Test
    public void consoleSerializesExactlyTheFieldsItAdvertises()
    {
        payloadMatchesSchema( aConsoleRead( 3 ) );
    }

    @Test
    public void consoleAdvertisesTruncationAndTheRangeItReturned()
    {
        Map<String, Object> console = propertiesOf( itemsOf( properties( ConsoleOutputResponse.class ), "consoles" ) );

        // A console is read from its end and there is no line-range read to reach the
        // rest, so "there was more" has to be a field.
        assertEquals( "boolean", ( (Map<String, Object>) console.get( "truncated" ) ).get( "type" ) );
        assertEquals( "integer", ( (Map<String, Object>) console.get( "totalLines" ) ).get( "type" ) );
        assertTrue( console.containsKey( "returnedRange" ) );

        ConsoleOutputResponse.ConsoleOutput output = aConsoleRead( 3 ).consoles().get( 0 );
        assertTrue( output.truncated() );
        assertEquals( 901, output.returnedRange().startLine() );
        assertEquals( 1000, output.totalLines() );
    }

    @Test
    public void consoleSaysWhetherItIsTheOnlyOne()
    {
        assertTrue( aConsoleRead( 1 ).onlyConsole() );
        assertFalse( aConsoleRead( 3 ).onlyConsole(),
                "one of nine consoles is not a picture of the build" );
    }

    @Test
    public void consoleReportsHavingNoneAsACode()
    {
        ConsoleOutputResponse failed = ConsoleOutputResponse.failed( 0,
                Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_FOUND, "no consoles" ) );

        assertEquals( ConsoleOutputResponse.Status.FAILED, failed.status() );
        assertEquals( 0, failed.totalConsoles() );
        assertTrue( failed.consoles().isEmpty() );
    }

    // ---- markdown outline ------------------------------------------------

    private static MarkdownOutlineResponse anOutline()
    {
        return MarkdownOutlineResponse.of( "P", "docs/plan.md", 400, List.of(
                new MarkdownOutlineResponse.Heading( 1, 1, "Objective", new ContentRange( 1, 1, 20, 1 ) ),
                new MarkdownOutlineResponse.Heading( 2, 2, "Conventions", new ContentRange( 21, 1, 90, 1 ) ) ) );
    }

    @Test
    public void outlineSerializesExactlyTheFieldsItAdvertises()
    {
        payloadMatchesSchema( anOutline() );
    }

    @Test
    public void outlineAdvertisesTheIndexTheSectionToolTakes()
    {
        Map<String, Object> heading = propertiesOf( itemsOf( properties( MarkdownOutlineResponse.class ), "headings" ) );

        assertEquals( "integer", ( (Map<String, Object>) heading.get( "index" ) ).get( "type" ),
                "the index is what getMarkdownSection takes when two sections share a title" );
        assertEquals( "integer", ( (Map<String, Object>) heading.get( "level" ) ).get( "type" ) );
        assertTrue( heading.containsKey( "range" ) );
    }

    @Test
    public void outlineSizesASectionWithoutCountingText()
    {
        assertEquals( 20, anOutline().headings().get( 0 ).lineCount() );
        assertEquals( 70, anOutline().headings().get( 1 ).lineCount() );
    }

    @Test
    public void outlineOfAFileWithNoHeadingsIsEmptyNotFailed()
    {
        MarkdownOutlineResponse response = MarkdownOutlineResponse.of( "P", "docs/notes.md", 12, List.of() );

        assertEquals( MarkdownOutlineResponse.Status.OK, response.status(),
                "having no headings is a fact about the file, not a failure to read it" );
        assertTrue( response.headings().isEmpty() );
        assertEquals( 12, response.totalLines() );
    }

    // ---- project layout --------------------------------------------------

    private static ProjectLayoutResponse aLayout()
    {
        ProjectLayoutResponse.Node file = new ProjectLayoutResponse.Node(
                "App.java", "src/App.java", ProjectLayoutResponse.NodeType.FILE, 0, List.of() );
        ProjectLayoutResponse.Node stopped = new ProjectLayoutResponse.Node(
                "target", "target", ProjectLayoutResponse.NodeType.FOLDER, 40, List.of() );
        ProjectLayoutResponse.Node src = new ProjectLayoutResponse.Node(
                "src", "src", ProjectLayoutResponse.NodeType.FOLDER, 1, List.of( file ) );
        ProjectLayoutResponse.Node root = new ProjectLayoutResponse.Node(
                "P", "", ProjectLayoutResponse.NodeType.PROJECT, 2, List.of( src, stopped ) );

        return new ProjectLayoutResponse( ProjectLayoutResponse.Status.OK, "P", null, 2, root,
                1, 2, 3, true, List.of() );
    }

    @Test
    public void layoutSerializesExactlyTheFieldsItAdvertises()
    {
        payloadMatchesSchema( aLayout() );
    }

    @Test
    public void layoutAdvertisesTruncationAndExclusion()
    {
        Map<String, Object> fields = properties( ProjectLayoutResponse.class );

        assertEquals( "boolean", ( (Map<String, Object>) fields.get( "truncated" ) ).get( "type" ) );
        assertEquals( "integer", ( (Map<String, Object>) fields.get( "excludedCount" ) ).get( "type" ),
                "an absent target/ should look filtered, not absent" );
        assertTrue( fields.containsKey( "scopePath" ) );
        assertTrue( fields.containsKey( "maxDepth" ) );
    }

    @Test
    public void layoutNodesCarryAProjectRelativePath()
    {
        ProjectLayoutResponse.Node src = aLayout().root().children().get( 0 );

        assertEquals( "src", src.filePath() );
        assertEquals( "src/App.java", src.children().get( 0 ).filePath(),
                "the reading and editing tools take a project-relative path" );
        assertEquals( "", aLayout().root().filePath(), "the project root is the empty path" );
    }

    @Test
    public void layoutSaysWhichFoldersItStoppedAt()
    {
        ProjectLayoutResponse.Node src = aLayout().root().children().get( 0 );
        ProjectLayoutResponse.Node stopped = aLayout().root().children().get( 1 );

        assertFalse( src.isCollapsed() );
        assertTrue( stopped.isCollapsed(), "40 children and none listed is exactly the case to flag" );
        assertEquals( 40, stopped.childCount(), "childCount answers 'is there more under here?'" );
        assertTrue( aLayout().truncated() );
    }

    @Test
    public void layoutReportsAMissingProjectAsACode()
    {
        ProjectLayoutResponse failed = ProjectLayoutResponse.failed( "Nope", null,
                Diagnostic.fatal( DiagnosticCode.PROJECT_NOT_FOUND, "no such project" ) );

        assertEquals( ProjectLayoutResponse.Status.FAILED, failed.status() );
        assertEquals( DiagnosticCode.PROJECT_NOT_FOUND, failed.diagnostics().get( 0 ).code() );
        assertFalse( failed.truncated(), "a failure is not a truncated listing" );
    }
}
