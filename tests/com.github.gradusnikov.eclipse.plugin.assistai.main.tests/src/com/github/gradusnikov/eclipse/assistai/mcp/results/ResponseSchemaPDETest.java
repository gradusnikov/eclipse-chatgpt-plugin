package com.github.gradusnikov.eclipse.assistai.mcp.results;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFileState;
import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.mcp.McpJson;
import com.github.gradusnikov.eclipse.assistai.mcp.McpOutputSchemas;

/**
 * The response records the search, history and operation tools advertise.
 * <p>
 * Two things are checked: that the generated schema names the fields a client is told
 * to branch on, and that the empty cases produce a sensible summary rather than an
 * empty string. Runs under the PDE harness only because the record signatures mention
 * Eclipse types.
 */
public class ResponseSchemaPDETest
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

    // ---- search ----------------------------------------------------------

    @Test
    public void searchAdvertisesTheFieldsNeededToActOnAMatch()
    {
        Map<String, Object> match = propertiesOf( itemsOf( properties( SearchResponse.class ), "matches" ) );

        // A match is only useful if it can be fed back into the read/edit tools, which
        // address files by project and project-relative path.
        assertTrue( match.containsKey( "projectName" ), match.keySet().toString() );
        assertTrue( match.containsKey( "filePath" ) );
        assertEquals( "integer", ( (Map<String, Object>) match.get( "lineNumber" ) ).get( "type" ) );
        assertTrue( match.containsKey( "lineContent" ) );
    }

    @Test
    public void searchAdvertisesTruncation()
    {
        Map<String, Object> fields = properties( SearchResponse.class );

        assertEquals( "boolean", ( (Map<String, Object>) fields.get( "truncated" ) ).get( "type" ) );
        assertEquals( "integer", ( (Map<String, Object>) fields.get( "totalMatches" ) ).get( "type" ) );
    }

    @Test
    public void searchSummarizesAnEmptyResult()
    {
        SearchResponse response = SearchResponse.from( "needle", List.of(), 200 );

        assertEquals( 0, response.totalMatches() );
        assertEquals( 0, response.filesMatched() );
        assertFalse( response.truncated() );
        assertEquals( "No matches.", response.summaryText() );
    }

    // ---- search and replace ----------------------------------------------

    @Test
    public void replaceAdvertisesFoundAndReplacedSeparately()
    {
        Map<String, Object> file = propertiesOf( itemsOf( properties( SearchReplaceResponse.class ), "files" ) );

        // The two differ when a file could not be fully updated; collapsing them would
        // hide exactly the case a caller must notice.
        assertTrue( file.containsKey( "matchesFound" ) );
        assertTrue( file.containsKey( "replacementsMade" ) );
    }

    @Test
    public void replaceSummarizesDoingNothing()
    {
        SearchReplaceResponse response = SearchReplaceResponse.from( "a", "b", List.of() );

        assertEquals( 0, response.totalReplacements() );
        assertEquals( 0, response.filesChanged() );
        assertEquals( "No replacements were made.", response.summaryText() );
    }

    @Test
    public void replaceFlagsAFileThatWasNotFullyUpdated()
    {
        SearchReplaceResponse.FileReplacement partial =
                new SearchReplaceResponse.FileReplacement( "P", "src/A.java", 5, 2 );
        SearchReplaceResponse.FileReplacement complete =
                new SearchReplaceResponse.FileReplacement( "P", "src/B.java", 3, 3 );

        assertTrue( partial.isIncomplete() );
        assertFalse( complete.isIncomplete() );
    }

    // ---- local history ---------------------------------------------------

    @Test
    public void historyAdvertisesTheTimestampTheOtherToolsTake()
    {
        Map<String, Object> version = propertiesOf( itemsOf( properties( FileHistoryResponse.class ), "versions" ) );

        assertEquals( "integer", ( (Map<String, Object>) version.get( "historyTimestamp" ) ).get( "type" ),
                "historyTimestamp is the selector for the other history tools and must be a number" );
    }

    @Test
    public void historySummarizesAFileWithNoStoredVersions()
    {
        FileHistoryResponse response =
                FileHistoryResponse.from( "P", "src/A.java", new IFileState[0], 20 );

        assertEquals( 0, response.totalVersions() );
        assertTrue( response.versions().isEmpty() );
        assertFalse( response.truncated() );
        assertTrue( response.summaryText().contains( "No local history" ) );
    }

    @Test
    public void historyToleratesANullHistoryArray()
    {
        FileHistoryResponse response = FileHistoryResponse.from( "P", "src/A.java", null, 20 );

        assertEquals( 0, response.totalVersions() );
    }

    // ---- references ------------------------------------------------------

    @Test
    public void referencesAdvertiseWhereEachUsageIs()
    {
        Map<String, Object> reference =
                propertiesOf( itemsOf( properties( ReferencesResponse.class ), "references" ) );

        assertTrue( reference.containsKey( "projectName" ) );
        assertTrue( reference.containsKey( "filePath" ) );
        assertEquals( "integer", ( (Map<String, Object>) reference.get( "lineNumber" ) ).get( "type" ) );
        assertTrue( reference.containsKey( "enclosingElement" ) );
    }

    @Test
    public void referencesAnswerIsThisUsedAnywhere()
    {
        ReferencesResponse unused = ReferencesResponse.of( "com.example.Dead", List.of(), false );

        assertTrue( unused.isUnused(), "0 references is the answer to 'can I delete this?'" );
        assertEquals( 0, unused.filesAffected() );
        assertTrue( unused.summaryText().contains( "No references" ) );
    }

    @Test
    public void referencesCountDistinctFiles()
    {
        ReferencesResponse response = ReferencesResponse.of( "com.example.Used", List.of(
                new ReferencesResponse.Reference( "P", "src/A.java", 10, "doWork", "  used();" ),
                new ReferencesResponse.Reference( "P", "src/A.java", 20, "doMore", "  used();" ),
                new ReferencesResponse.Reference( "P", "src/B.java", 5, "run", "  used();" ) ), false );

        assertEquals( 3, response.totalReferences() );
        assertEquals( 2, response.filesAffected(), "three references across two files" );
        assertFalse( response.isUnused() );
    }

    // ---- file listing ----------------------------------------------------

    @Test
    public void fileListSplitsWorkspacePathsIntoProjectAndPath()
    {
        FileListResponse response = FileListResponse.from( new String[] { "*.java" },
                List.of( "/MyProject/src/com/example/A.java" ), 0 );

        FileListResponse.WorkspaceFile file = response.files().get( 0 );
        assertEquals( "MyProject", file.projectName() );
        assertEquals( "src/com/example/A.java", file.filePath(),
                "the editing tools take a project-relative path, not a workspace path" );
    }

    @Test
    public void fileListReportsTruncation()
    {
        FileListResponse response = FileListResponse.from( new String[] { "*" },
                List.of( "/P/a.txt", "/P/b.txt", "/P/c.txt" ), 2 );

        assertEquals( 3, response.totalFiles() );
        assertEquals( 2, response.files().size() );
        assertTrue( response.truncated() );
    }

    @Test
    public void fileListSummarizesNoMatches()
    {
        FileListResponse response = FileListResponse.from( new String[] { "*.xyz" }, List.of(), 0 );

        assertEquals( 0, response.totalFiles() );
        assertFalse( response.truncated() );
        assertEquals( "No matching files.", response.summaryText() );
    }

    // ---- compilation problems --------------------------------------------

    @Test
    public void problemsAdvertiseCountsSeparateFromTheListing()
    {
        Map<String, Object> fields = properties( CompilationProblemsResponse.class );

        // Counts are of everything that matched, before truncation, so "are there
        // errors?" stays answerable from a shortened listing.
        assertEquals( "integer", ( (Map<String, Object>) fields.get( "errorCount" ) ).get( "type" ) );
        assertEquals( "integer", ( (Map<String, Object>) fields.get( "warningCount" ) ).get( "type" ) );
        assertEquals( "integer", ( (Map<String, Object>) fields.get( "totalProblems" ) ).get( "type" ) );
        assertEquals( "boolean", ( (Map<String, Object>) fields.get( "truncated" ) ).get( "type" ) );
    }

    @Test
    public void problemsAdvertiseWhatIsNeededToFixThem()
    {
        Map<String, Object> file = propertiesOf( itemsOf( properties( CompilationProblemsResponse.class ), "files" ) );
        assertTrue( file.containsKey( "projectName" ) );
        assertTrue( file.containsKey( "filePath" ) );

        Map<String, Object> problem = propertiesOf( itemsOf( file, "problems" ) );
        assertEquals( "integer", ( (Map<String, Object>) problem.get( "lineNumber" ) ).get( "type" ) );
        assertEquals( "integer", ( (Map<String, Object>) problem.get( "markerId" ) ).get( "type" ),
                "markerId is what executeQuickFix takes" );

        List<String> severities = SchemaTypes.enumOf( problem.get( "severity" ) );
        assertTrue( severities.contains( "ERROR" ), severities.toString() );
        assertTrue( severities.contains( "WARNING" ) );
    }

    @Test
    public void problemsAdvertiseQuickFixIndices()
    {
        Map<String, Object> problem =
                propertiesOf( itemsOf( propertiesOf( itemsOf( properties( CompilationProblemsResponse.class ), "files" ) ),
                                       "problems" ) );
        Map<String, Object> fix = propertiesOf( itemsOf( problem, "quickFixes" ) );

        assertEquals( "integer", ( (Map<String, Object>) fix.get( "index" ) ).get( "type" ),
                "the index is the second half of what executeQuickFix needs" );
        assertTrue( fix.containsKey( "label" ) );
    }

    @Test
    public void problemsReportACleanProject()
    {
        CompilationProblemsResponse response = new CompilationProblemsResponse(
                "Project: P", 0, 0, 0, 0, List.of(), false, "Found 0 problems." );

        assertFalse( response.hasErrors() );
        assertEquals( 0, response.totalProblems() );
        assertTrue( response.files().isEmpty() );
    }

    @Test
    public void problemsReportErrorsPresent()
    {
        CompilationProblemsResponse response = new CompilationProblemsResponse(
                "Project: P", 3, 2, 1, 0, List.of(), true, "Showing 1 of 3 problems found." );

        assertTrue( response.hasErrors() );
        assertTrue( response.truncated() );
    }

    // ---- operation status ------------------------------------------------

    @Test
    public void operationStatusAdvertisesStateAndResult()
    {
        Map<String, Object> fields = properties( OperationStatusResponse.class );

        assertTrue( fields.containsKey( "state" ) );
        assertEquals( "boolean", ( (Map<String, Object>) fields.get( "terminal" ) ).get( "type" ) );
        assertTrue( fields.containsKey( "result" ),
                "the wrapped tool's payload must survive collection by polling" );
        assertTrue( fields.containsKey( "resultText" ) );
    }

    @Test
    public void operationStatusDescribesAnUnknownId()
    {
        OperationStatusResponse response = OperationStatusResponse.unknown( "op-404" );

        assertEquals( "op-404", response.operationId() );
        assertEquals( "UNKNOWN", response.state() );
        assertTrue( response.terminal() );
        assertTrue( response.summaryText().contains( "op-404" ) );
    }

    // ---- quick fix -------------------------------------------------------

    private static QuickFixResponse anAppliedFix()
    {
        return QuickFixResponse.applied( 4711L, "P", "src/A.java", 0, "Import 'ArrayList'", true,
                List.of( new CompilationProblemsResponse.QuickFixOption( 0, "Import 'ArrayList'", null ) ) );
    }

    @Test
    public void quickFixSerializesExactlyTheFieldsItAdvertises()
    {
        // The payload-equals-schema guard. A derived accessor serialized as a field
        // would make the tool promise one shape and send another.
        assertEquals( properties( QuickFixResponse.class ).keySet(), McpJson.toMap( anAppliedFix() ).keySet() );
    }

    @Test
    public void quickFixAdvertisesTheFourWaysTheLoopCanEnd()
    {
        List<String> statuses = SchemaTypes.enumOf( properties( QuickFixResponse.class ).get( "status" ) );

        // Each of these needs a different next move, which is why they are not one
        // "Error: ..." sentence with five wordings.
        assertTrue( statuses.contains( "APPLIED" ), statuses.toString() );
        assertTrue( statuses.contains( "MARKER_NOT_FOUND" ), statuses.toString() );
        assertTrue( statuses.contains( "NO_PROPOSALS" ), statuses.toString() );
        assertTrue( statuses.contains( "INVALID_PROPOSAL_INDEX" ), statuses.toString() );
        assertTrue( statuses.contains( "APPLY_FAILED" ), statuses.toString() );
    }

    @Test
    public void quickFixAdvertisesTheProposalsAsAListRatherThanARangeInASentence()
    {
        Map<String, Object> fields = properties( QuickFixResponse.class );
        Map<String, Object> proposal = propertiesOf( itemsOf( fields, "availableProposals" ) );

        assertEquals( "integer", ( (Map<String, Object>) proposal.get( "index" ) ).get( "type" ),
                "the index is what executeQuickFix takes" );
        assertTrue( proposal.containsKey( "label" ) );
        assertTrue( fields.containsKey( "markerResolved" ),
                "whether the problem went away used to be a parenthetical in the success line" );
    }

    @Test
    public void quickFixDistinguishesNotAppliedFromAppliedAndUnresolved()
    {
        assertNull( QuickFixResponse.markerNotFound( 1L, 0 ).markerResolved() );
        assertFalse( QuickFixResponse.markerNotFound( 1L, 0 ).changedResource() );

        assertEquals( Boolean.FALSE,
                QuickFixResponse.applied( 1L, "P", "src/A.java", 0, "fix", false, List.of() ).markerResolved(),
                "applied, and the problem is still there - not the same as never applied" );
    }

    @Test
    public void quickFixReportsAnEmptyProposalListWithoutInventingAFault()
    {
        QuickFixResponse response = QuickFixResponse.noProposals( 1L, "P", "src/A.java", 0 );

        assertEquals( QuickFixResponse.Status.NO_PROPOSALS, response.status() );
        assertTrue( response.availableProposals().isEmpty() );
        assertTrue( response.diagnostics().isEmpty(),
                "a problem with no quick fix is an ordinary state, not an INTERNAL_ERROR" );
    }

    // ---- call hierarchy --------------------------------------------------

    private static CallHierarchyResponse aHierarchy()
    {
        return CallHierarchyResponse.of( "com.example.A.run", "run", "com.example.A", 3,
                List.of( new CallHierarchyResponse.CallNode( 1, "main", "com.example.B", "String[] args",
                        "P", "src/com/example/B.java", 12 ) ),
                List.of(), List.of() );
    }

    @Test
    public void callHierarchySerializesExactlyTheFieldsItAdvertises()
    {
        assertEquals( properties( CallHierarchyResponse.class ).keySet(), McpJson.toMap( aHierarchy() ).keySet() );
    }

    @Test
    public void callHierarchyAdvertisesWhereEachCallerCanBeOpened()
    {
        Map<String, Object> node = propertiesOf( itemsOf( properties( CallHierarchyResponse.class ), "callers" ) );

        // The whole point: "who calls this" used to be answerable and "where" not, so
        // every use cost a follow-up findReferences.
        assertTrue( node.containsKey( "projectName" ), node.keySet().toString() );
        assertTrue( node.containsKey( "filePath" ) );
        assertEquals( "integer", ( (Map<String, Object>) node.get( "lineNumber" ) ).get( "type" ) );
        assertEquals( "integer", ( (Map<String, Object>) node.get( "depth" ) ).get( "type" ),
                "depth was two spaces of indentation" );
    }

    @Test
    public void callHierarchySeparatesAnUnknownTypeFromAnUnknownMethod()
    {
        List<String> statuses = SchemaTypes.enumOf( properties( CallHierarchyResponse.class ).get( "status" ) );

        assertTrue( statuses.contains( "TYPE_NOT_FOUND" ), statuses.toString() );
        assertTrue( statuses.contains( "METHOD_NOT_FOUND" ), statuses.toString() );
    }

    @Test
    public void callHierarchyCountsWhatItListed()
    {
        assertEquals( 1, aHierarchy().totalCallers() );
        assertEquals( 0, aHierarchy().totalCallees() );
        assertTrue( aHierarchy().hasCallers() );

        CallHierarchyResponse missing = CallHierarchyResponse.failed( "com.example.A.run", "run", "com.example.A", 3,
                CallHierarchyResponse.Status.METHOD_NOT_FOUND,
                Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_FOUND, "no such method" ) );

        assertFalse( missing.hasCallers() );
        assertTrue( missing.callers().isEmpty(), "'not found' must not look like 'found, and nothing calls it'" );
    }

    // ---- import suggestions ----------------------------------------------

    private static ImportSuggestionsResponse someSuggestions()
    {
        return ImportSuggestionsResponse.of( "P", "src/A.java", List.of(
                new ImportSuggestionsResponse.UnresolvedType( "ArrayList", 5, "ArrayList cannot be resolved to a type",
                        List.of( "java.util.ArrayList" ) ) ) );
    }

    @Test
    public void importSuggestionsSerializeExactlyTheFieldsTheyAdvertise()
    {
        assertEquals( properties( ImportSuggestionsResponse.class ).keySet(),
                McpJson.toMap( someSuggestions() ).keySet() );
    }

    @Test
    public void importSuggestionsAdvertiseTheBareFullyQualifiedName()
    {
        Map<String, Object> type =
                propertiesOf( itemsOf( properties( ImportSuggestionsResponse.class ), "unresolvedTypes" ) );

        Map<String, Object> candidates = (Map<String, Object>) type.get( "candidates" );
        assertEquals( "array", candidates.get( "type" ) );
        assertEquals( "string", SchemaTypes.carriedBy( (Map<String, Object>) candidates.get( "items" ) ),
                "a candidate is the name itself, not an import statement inside a bullet" );
        assertEquals( "integer", SchemaTypes.carriedBy( (Map<String, Object>) type.get( "lineNumber" ) ) );
    }

    @Test
    public void importSuggestionsSeparateNoTypesFromNoCandidates()
    {
        ImportSuggestionsResponse clean = ImportSuggestionsResponse.of( "P", "src/A.java", List.of() );
        assertEquals( 0, clean.totalUnresolvedTypes() );
        assertEquals( ImportSuggestionsResponse.Status.OK, clean.status(),
                "a file with nothing unresolved is not a failure" );

        ImportSuggestionsResponse noMatches = ImportSuggestionsResponse.of( "P", "src/A.java", List.of(
                new ImportSuggestionsResponse.UnresolvedType( "Nope", 3, "Nope cannot be resolved", List.of() ) ) );
        assertEquals( 1, noMatches.totalUnresolvedTypes() );
        assertEquals( 0, noMatches.totalCandidates() );
        assertFalse( noMatches.hasCandidates() );

        assertEquals( 1, someSuggestions().totalCandidates() );
    }

    @Test
    public void importSuggestionsSeparateAMissingProjectFromAClosedOne()
    {
        List<String> statuses = SchemaTypes.enumOf( properties( ImportSuggestionsResponse.class ).get( "status" ) );

        assertTrue( statuses.contains( "PROJECT_NOT_FOUND" ), statuses.toString() );
        assertTrue( statuses.contains( "PROJECT_CLOSED" ), statuses.toString() );
        assertTrue( statuses.contains( "FILE_NOT_FOUND" ), statuses.toString() );
    }
}
