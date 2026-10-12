package com.github.gradusnikov.eclipse.assistai.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.mcp.McpJson;
import com.github.gradusnikov.eclipse.assistai.mcp.McpOutputSchemas;
import com.github.gradusnikov.eclipse.assistai.mcp.results.Diagnostic;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.SchemaTypes;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult.AffectedResource;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult.ChangeKind;

/**
 * The wire shape of the record every editing tool returns.
 * <p>
 * There was no such test. Every existing assertion on an {@link EditResult} was on
 * {@code status()}, which is why nobody noticed that its location was a
 * {@link ResourceDescriptor} holding an {@code IPath} - a type with no serializer
 * registered, so it went out as the path's private fields. A record that describes
 * where an edit landed has to survive the trip to the client that will act on it.
 */
public class EditResultPDETest
{
    private static final AffectedResource MODIFIED = new AffectedResource(
            "Demo", "src/Service.java", ChangeKind.MODIFIED,
            new ResourceVersion( 4712L, 1700000000001L, null, true ) );

    private static EditResult anApplied()
    {
        return new EditResult(
                EditResult.EditStatus.APPLIED,
                "Demo",
                "src/Service.java",
                new ResourceVersion( 4711L, 1700000000000L, null, true ),
                new ResourceVersion( 4712L, 1700000000001L, null, true ),
                List.of( new EditResult.AppliedEdit(
                        new ContentRange( 10, 1, 10, 20 ),
                        new ContentRange( 10, 1, 11, 5 ), 24, 19 ) ),
                "--- a\n+++ b\n",
                List.of( MODIFIED ),
                EditResult.EditorReveal.none(),
                1700000000002L,
                new EditResult.WorkspaceSync( true, true, "true" ),
                Diagnostic.none() );
    }

    @SuppressWarnings( "unchecked" )
    private static Map<String, Object> properties( Map<String, Object> schema )
    {
        return (Map<String, Object>) schema.get( "properties" );
    }

    @Test
    public void serializesExactlyTheFieldsItAdvertises()
    {
        assertEquals( properties( McpOutputSchemas.forType( EditResult.class ) ).keySet(),
                McpJson.toMap( anApplied() ).keySet() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    public void anAffectedResourceSerializesExactlyTheFieldsItAdvertises()
    {
        Map<String, Object> affected = (Map<String, Object>) properties(
                McpOutputSchemas.forType( EditResult.class ) ).get( "affectedResources" );

        assertEquals( "array", affected.get( "type" ) );
        assertEquals( properties( SchemaTypes.nonNull( affected.get( "items" ) ) ).keySet(),
                McpJson.toMap( MODIFIED ).keySet() );
    }

    @Test
    public void theLocationIsThePairEveryOtherToolAccepts()
    {
        Map<String, Object> payload = McpJson.toMap( anApplied() );

        assertEquals( "Demo", payload.get( "projectName" ) );
        assertEquals( "src/Service.java", payload.get( "filePath" ) );
        assertFalse( payload.containsKey( "resource" ),
                "the descriptor that used to serialize as an IPath's private fields is gone" );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    public void anAffectedResourceCarriesAProjectAPathAKindAndAVersion()
    {
        Map<String, Object> entry =
                ( (List<Map<String, Object>>) McpJson.toMap( anApplied() ).get( "affectedResources" ) ).get( 0 );

        assertEquals( "Demo", entry.get( "projectName" ) );
        assertEquals( "src/Service.java", entry.get( "filePath" ) );
        assertEquals( "MODIFIED", entry.get( "kind" ) );
        assertEquals( 4712L,
                ( (Number) ( (Map<String, Object>) entry.get( "version" ) ).get( "modificationStamp" ) ).longValue(),
                "the version an edit to that file must now quote back" );
    }

    @Test
    public void aResourceThatIsGoneCarriesNoVersion()
    {
        AffectedResource deleted = new AffectedResource( "Demo", "src/Old.java", ChangeKind.DELETED, null );

        assertEquals( ChangeKind.DELETED, deleted.kind() );
        assertNull( deleted.version(), "there is no version of a file that no longer exists" );
    }

    @Test
    public void aRejectionCarriesTheReasonAsACodeAndAffectsNothing()
    {
        EditResult rejected = EditResult.versionConflict(
                "Demo", "src/Service.java", new ResourceVersion( 4712L, 0L, null, true ), 4711L );

        assertEquals( EditResult.EditStatus.REJECTED, rejected.status() );
        assertEquals( DiagnosticCode.VERSION_CONFLICT, rejected.diagnostics().get( 0 ).code() );
        assertTrue( rejected.diagnostics().get( 0 ).retryable(),
                "the caller fixes a version conflict by re-reading and recomputing" );
        assertFalse( rejected.changedResource(), "a rejection must not look like a write" );
        assertEquals( "", rejected.unifiedDiff() );
        assertTrue( rejected.affectedResources().isEmpty(),
                "nothing was written, so nothing was affected" );
    }

    @Test
    public void anAppliedEditReportsTheStampTheNextEditQuotes()
    {
        EditResult applied = anApplied();

        assertTrue( applied.changedResource() );
        assertEquals( 4712L, applied.versionAfter().modificationStamp(),
                "versionAfter is what the next expectedModificationStamp must be" );
        assertTrue( applied.versionAfter().matches( 4712L ) );
        assertFalse( applied.versionAfter().matches( 4711L ),
                "the stamp read before the edit must no longer satisfy the guard" );

        // The resource the result is addressed to appears in the list as well, so a
        // caller iterates one field instead of remembering to union it with another.
        assertEquals( applied.filePath(), applied.affectedResources().get( 0 ).filePath() );
        assertEquals( applied.versionAfter(), applied.affectedResources().get( 0 ).version() );
    }

    @Test
    public void theUndoTimestampIsAHistoryStateNotAnInventedToken()
    {
        // It is the IFileState.getModificationTime() of the content the edit displaced,
        // so it survives a restart and names the same entry the user sees under
        // Compare With > Local History.
        assertEquals( 1700000000002L, anApplied().undoHistoryTimestamp() );
        assertEquals( EditResult.NO_UNDO_STATE, EditResult.rejected(
                "Demo", "src/Service.java", ResourceVersion.UNKNOWN,
                Diagnostic.fatal( DiagnosticCode.TEXT_NOT_FOUND, "no match" ) ).undoHistoryTimestamp() );
    }
}
