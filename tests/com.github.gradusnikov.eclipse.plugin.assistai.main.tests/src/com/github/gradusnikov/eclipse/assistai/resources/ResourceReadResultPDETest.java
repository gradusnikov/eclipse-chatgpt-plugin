package com.github.gradusnikov.eclipse.assistai.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.eclipse.core.resources.IResource;

import org.eclipse.core.resources.IResource;

import com.github.gradusnikov.eclipse.assistai.mcp.McpJson;
import com.github.gradusnikov.eclipse.assistai.mcp.McpOutputSchemas;
import com.github.gradusnikov.eclipse.assistai.mcp.results.Diagnostic;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.SchemaTypes;

/**
 * The read result, and the recognition the in-IDE chat depends on.
 * <p>
 * The chat used to spot a cacheable read by matching {@code __resourceCache__} inside
 * the tool's text. Converted tools no longer emit that envelope, so recognition moved
 * to {@link ResourceReadResult#fromStructuredContent}. If that stops working nothing
 * fails loudly - the &lt;resources&gt; block just quietly stops filling - so it is
 * tested directly.
 */
public class ResourceReadResultPDETest
{
    private static ResourceReadResult aRead()
    {
        return new ResourceReadResult(
                ResourceReadResult.ReadStatus.OK,
                "workspace:///P/src/A.java",
                "P",
                "src/A.java",
                "java",
                new ResourceVersion( 4711L, 1700000000000L, null, true ),
                new ContentRange( 10, 1, 20, 1 ),
                120,
                "public void execute() {\n}\n",
                SourceOrigin.WORKSPACE_SOURCE,
                false,
                true,
                List.of(),
                List.of() );
    }

    // ---- what the caller needs to act ------------------------------------

    @Test
    public void advertisesTheStampAnEditQuotes()
    {
        @SuppressWarnings( "unchecked" )
        Map<String, Object> version = (Map<String, Object>) ( (Map<String, Object>) McpOutputSchemas
                .forType( ResourceReadResult.class ).get( "properties" ) ).get( "version" );

        @SuppressWarnings( "unchecked" )
        Map<String, Object> stamp = (Map<String, Object>) SchemaTypes.propertiesOf( version ).get( "modificationStamp" );

        // Nullable, and that is the point: a resource with no stamp reports null rather
        // than NULL_STAMP, which matches() would read as "no check requested". The
        // schema has to admit the null or a validating client discards the whole read.
        assertEquals( List.of( "integer", "null" ), SchemaTypes.alternatives( stamp ),
                "modificationStamp is what an edit passes as expectedModificationStamp" );
    }

    @Test
    public void advertisesTheLineTheContentStartsAt()
    {
        @SuppressWarnings( "unchecked" )
        Map<String, Object> fields = (Map<String, Object>) McpOutputSchemas
                .forType( ResourceReadResult.class ).get( "properties" );

        assertTrue( fields.containsKey( "returnedRange" ) );
        assertTrue( fields.containsKey( "totalLines" ) );
        assertTrue( fields.containsKey( "projectName" ) );
        assertTrue( fields.containsKey( "filePath" ) );
    }

    @Test
    public void reportsAFailureAsACodeNotAsContent()
    {
        ResourceReadResult failed = ResourceReadResult.failed( "P", "src/Missing.java",
                Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_FOUND, "no such file" ) );

        assertEquals( ResourceReadResult.ReadStatus.FAILED, failed.status() );
        assertEquals( DiagnosticCode.RESOURCE_NOT_FOUND, failed.diagnostics().get( 0 ).code() );
        assertEquals( "", failed.content(), "a failure must not look like empty content" );
        assertFalse( failed.isCacheable() );
    }

    @Test
    public void aReadOnlyOriginIsNotEditable()
    {
        ResourceReadResult decompiled = new ResourceReadResult(
                ResourceReadResult.ReadStatus.OK, "jdt:///java.lang.String", null, null, "java",
                ResourceVersion.UNKNOWN, new ContentRange( 1, 1, 2, 1 ), 2, "class String {}\n",
                SourceOrigin.DECOMPILED_CLASS, true, false, List.of(), List.of() );

        assertFalse( decompiled.isEditable() );
        assertFalse( ResourceVersion.UNKNOWN.isKnown(),
                "a decompiled class has no workspace version to quote" );
    }

    // ---- the chat's recognition path -------------------------------------

    @Test
    public void survivesTheRoundTripThroughStructuredContent()
    {
        ResourceReadResult recovered = ResourceReadResult.fromStructuredContent( McpJson.toMap( aRead() ) );

        assertNotNull( recovered, "the chat cannot cache a read it cannot recognise" );
        assertEquals( "P", recovered.projectName() );
        assertEquals( "src/A.java", recovered.filePath() );
        assertEquals( 10, recovered.returnedRange().startLine() );
        assertEquals( 4711L, recovered.version().modificationStamp() );
        assertEquals( aRead().content(), recovered.content() );
        assertTrue( recovered.isCacheable() );
    }

    @Test
    public void serializesExactlyTheFieldsItAdvertises()
    {
        // The schema is generated from the record components; the payload comes from
        // Jackson. A derived isXxx() accessor is serialized as a field unless it is
        // ignored, which would make a tool advertise one shape and send another - and
        // then fail to read its own payload back, as this test first caught.
        @SuppressWarnings( "unchecked" )
        Map<String, Object> advertised = (Map<String, Object>) McpOutputSchemas
                .forType( ResourceReadResult.class ).get( "properties" );

        assertEquals( advertised.keySet(), McpJson.toMap( aRead() ).keySet() );
    }

    @Test
    public void ignoresPayloadsThatAreNotReads()
    {
        assertNull( ResourceReadResult.fromStructuredContent( null ) );
        assertNull( ResourceReadResult.fromStructuredContent( "some text" ) );
        assertNull( ResourceReadResult.fromStructuredContent( Map.of( "totalMatches", 3 ) ),
                "a search response must not be mistaken for a read" );
    }

    @Test
    public void neverPutsTheNullStampSentinelOnTheWire()
    {
        // NULL_STAMP is -1, and matches(-1) means "the caller did not ask for the
        // check". Emitting it as a value meant a caller that read a history version and
        // quoted its stamp back as expectedModificationStamp silently disabled the very
        // guard it was trying to use. An absent stamp is null.
        assertNull( ResourceVersion.UNKNOWN.modificationStamp() );
        assertNull( ResourceVersion.ofHistoryState( null ).modificationStamp() );
        assertNull( ResourceVersion.of( null ).modificationStamp() );

        assertFalse( ResourceVersion.UNKNOWN.isKnown() );
        assertFalse( ResourceVersion.UNKNOWN.matches( 4711L ),
                "an unknown version cannot satisfy a specific expectation" );
        assertTrue( ResourceVersion.UNKNOWN.matches( IResource.NULL_STAMP ),
                "omitting the check stays opt-in" );
    }

    @Test
    public void aKnownStampIsTheOneAnEditQuotesBack()
    {
        ResourceVersion version = new ResourceVersion( 4711L, 1700000000000L, null, true );

        assertTrue( version.isKnown() );
        assertTrue( version.matches( 4711L ) );
        assertFalse( version.matches( 4712L ), "a moved-on resource must be refused" );
    }

    @Test
    public void doesNotCacheAnEmptyRead()
    {
        ResourceReadResult empty = new ResourceReadResult(
                ResourceReadResult.ReadStatus.OK, "workspace:///P/src/Empty.java", "P", "src/Empty.java",
                "java", ResourceVersion.UNKNOWN, new ContentRange( 1, 1, 1, 1 ), 0, "",
                SourceOrigin.WORKSPACE_SOURCE, false, false, List.of(), List.of() );

        assertFalse( empty.isCacheable() );
    }
}
