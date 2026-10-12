package com.github.gradusnikov.eclipse.assistai.mcp.results;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.mcp.McpJson;
import com.github.gradusnikov.eclipse.assistai.mcp.McpOutputSchemas;
import com.github.gradusnikov.eclipse.assistai.resources.CachedResource;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceDescriptor;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceDescriptor.ResourceType;

/**
 * The responses the eclipse-context cache tools advertise.
 * <p>
 * The listing used to be a fixed-width table, so what is checked here is that the
 * fields a caller must act on survive as fields: the URI whole rather than truncated,
 * the location split the way the editing tools take it, and the modification stamp
 * kept distinct from the cache's own refresh counter. Runs under the PDE harness
 * because the fixtures mention Eclipse resource types.
 */
@SuppressWarnings( "unchecked" )
public class CacheResponsesPDETest
{
    private static Map<String, Object> properties( Class<?> type )
    {
        Map<String, Object> schema = McpOutputSchemas.forType( type );
        assertNotNull( schema, type.getSimpleName() + " must advertise a schema" );
        return (Map<String, Object>) schema.get( "properties" );
    }

    private static Map<String, Object> itemsOf( Map<String, Object> properties, String field )
    {
        Map<String, Object> array = (Map<String, Object>) properties.get( field );
        assertEquals( "array", array.get( "type" ), field + " should be an array" );
        return SchemaTypes.propertiesOf( array.get( "items" ) );
    }

    private static String typeOf( Map<String, Object> properties, String field )
    {
        Map<String, Object> schema = (Map<String, Object>) properties.get( field );
        assertNotNull( schema, field + " must be advertised, saw " + properties.keySet() );
        return SchemaTypes.carriedBy( schema );
    }

    // ---- fixtures --------------------------------------------------------

    private static CachedResource workspaceFile( String workspacePath, String content,
                                                 int cacheRevision, long modificationStamp )
    {
        IPath path = IPath.fromPortableString( workspacePath );
        ResourceDescriptor descriptor = new ResourceDescriptor(
                URI.create( "workspace://" + workspacePath ),
                ResourceType.WORKSPACE_FILE,
                path.lastSegment(),
                path,
                "readProjectResource" );
        return CachedResource.create( descriptor, content, cacheRevision, 0L, modificationStamp );
    }

    private static CachedResource console( String name, String content )
    {
        ResourceDescriptor descriptor = new ResourceDescriptor(
                URI.create( "console:///" + name ),
                ResourceType.CONSOLE_OUTPUT,
                name,
                null,
                "getConsoleOutput" );
        return CachedResource.create( descriptor, content, 1, 0L, IResource.NULL_STAMP );
    }

    private static Map<URI, CachedResource> cache( CachedResource... resources )
    {
        Map<URI, CachedResource> map = new LinkedHashMap<>();
        for ( CachedResource resource : resources )
        {
            map.put( resource.descriptor().uri(), resource );
        }
        return map;
    }

    private static CachedResourcesResponse.CachedEntry firstEntry( CachedResource resource )
    {
        return CachedResourcesResponse.from( cache( resource ) ).resources().get( 0 );
    }

    // ---- listing schema --------------------------------------------------

    @Test
    public void listingAdvertisesTheFieldsNeededToActOnAnEntry()
    {
        Map<String, Object> entry = itemsOf( properties( CachedResourcesResponse.class ), "resources" );

        // The URI is what getCachedResource takes; project and filePath are what every
        // reading and editing tool takes. Without them a listing is only readable.
        assertEquals( "string", typeOf( entry, "uri" ) );
        assertEquals( "string", typeOf( entry, "projectName" ) );
        assertEquals( "string", typeOf( entry, "filePath" ) );
        assertEquals( "string", typeOf( entry, "displayName" ) );
        assertEquals( "string", typeOf( entry, "cachedAt" ) );
        assertEquals( "integer", typeOf( entry, "estimatedTokens" ) );
    }

    @Test
    public void listingAdvertisesTheModificationStampAsANumber()
    {
        Map<String, Object> entry = itemsOf( properties( CachedResourcesResponse.class ), "resources" );

        assertEquals( "integer", typeOf( entry, "modificationStamp" ),
                "modificationStamp is quoted back as expectedModificationStamp and must be a number" );
        assertEquals( "integer", typeOf( entry, "cachedAtEpochMilli" ) );
    }

    @Test
    public void listingDoesNotAdvertiseTheCacheCounterAsAVersion()
    {
        Map<String, Object> entry = itemsOf( properties( CachedResourcesResponse.class ), "resources" );

        // The counter is cache bookkeeping. Calling it "version" beside a real
        // modification stamp invites a caller to guard a write with the wrong one.
        assertFalse( entry.containsKey( "version" ), entry.keySet().toString() );
        assertEquals( "integer", typeOf( entry, "cacheRevision" ) );
    }

    @Test
    public void listingAdvertisesTheResourceTypesItCanReport()
    {
        Map<String, Object> entry = itemsOf( properties( CachedResourcesResponse.class ), "resources" );
        List<String> types = SchemaTypes.enumOf( entry.get( "type" ) );

        assertNotNull( types, "type should be advertised as an enumeration" );
        assertTrue( types.contains( ResourceType.WORKSPACE_FILE.name() ), types.toString() );
        assertTrue( types.contains( ResourceType.JAVA_TYPE.name() ), types.toString() );
    }

    @Test
    public void listingAdvertisesTotalsBesideTheEntries()
    {
        Map<String, Object> fields = properties( CachedResourcesResponse.class );

        assertEquals( "integer", typeOf( fields, "totalResources" ) );
        assertEquals( "integer", typeOf( fields, "totalEstimatedTokens" ) );
    }

    // ---- listing values --------------------------------------------------

    @Test
    public void listingSplitsTheWorkspacePathIntoProjectAndRelativePath()
    {
        CachedResourcesResponse.CachedEntry entry =
                firstEntry( workspaceFile( "/MyProject/src/com/example/A.java", "x", 1, 7L ) );

        assertEquals( "MyProject", entry.projectName() );
        assertEquals( "src/com/example/A.java", entry.filePath(),
                "the editing tools take a project-relative path, not a workspace path" );
    }

    @Test
    public void listingKeepsTheWholeUri()
    {
        String path = "/MyProject/src/com/example/deeply/nested/package/name/AVeryLongClassName.java";
        CachedResourcesResponse.CachedEntry entry = firstEntry( workspaceFile( path, "x", 1, 7L ) );

        assertEquals( "workspace://" + path, entry.uri(),
                "the URI is getCachedResource's only argument, so it cannot be abbreviated" );
    }

    @Test
    public void listingReportsTheModificationStampAnEditQuotes()
    {
        CachedResourcesResponse.CachedEntry entry =
                firstEntry( workspaceFile( "/P/src/A.java", "x", 3, 4242L ) );

        assertEquals( Long.valueOf( 4242L ), entry.modificationStamp() );
        assertEquals( 3, entry.cacheRevision() );
    }

    @Test
    public void listingReportsNoStampWhenTheResourceHasNone()
    {
        CachedResourcesResponse.CachedEntry entry = firstEntry( console( "Build", "output" ) );

        assertNull( entry.modificationStamp(),
                "absent is not the same as some sentinel a caller might pass back" );
    }

    @Test
    public void listingLeavesTheLocationEmptyForANonWorkspaceResource()
    {
        CachedResourcesResponse.CachedEntry entry = firstEntry( console( "Build", "output" ) );

        assertNull( entry.projectName() );
        assertNull( entry.filePath() );
        assertEquals( ResourceType.CONSOLE_OUTPUT, entry.type() );
    }

    @Test
    public void listingReportsAProjectWithNoFilePath()
    {
        ResourceDescriptor layout = ResourceDescriptor.forProjectLayout( "MyProject", "getProjectLayout" );
        CachedResource resource = CachedResource.create( layout, "tree", 1, 0L, IResource.NULL_STAMP );

        CachedResourcesResponse.CachedEntry entry = firstEntry( resource );

        assertEquals( "MyProject", entry.projectName() );
        assertNull( entry.filePath(), "a project layout is not a file in the project" );
    }

    @Test
    public void listingTotalsTokensAcrossEntries()
    {
        CachedResource a = workspaceFile( "/P/src/A.java", "12345678", 1, 1L );
        CachedResource b = workspaceFile( "/P/src/B.java", "1234", 1, 2L );

        CachedResourcesResponse response = CachedResourcesResponse.from( cache( a, b ) );

        assertEquals( 2, response.totalResources() );
        assertEquals( a.estimateTokens() + b.estimateTokens(), response.totalEstimatedTokens() );
        assertEquals( response.resources().size(), response.totalResources() );
    }

    @Test
    public void listingPreservesTheCacheOrder()
    {
        CachedResource a = workspaceFile( "/P/src/A.java", "a", 1, 1L );
        CachedResource b = workspaceFile( "/P/src/B.java", "b", 1, 2L );

        CachedResourcesResponse response = CachedResourcesResponse.from( cache( a, b ) );

        // Least recently used first, which is the order eviction takes them in.
        assertEquals( "src/A.java", response.resources().get( 0 ).filePath() );
        assertEquals( "src/B.java", response.resources().get( 1 ).filePath() );
    }

    @Test
    public void listingSummarizesAnEmptyCache()
    {
        CachedResourcesResponse response = CachedResourcesResponse.from( Map.of() );

        assertEquals( 0, response.totalResources() );
        assertEquals( 0, response.totalEstimatedTokens() );
        assertTrue( response.resources().isEmpty() );
        assertNotNull( response.summaryText() );
        assertFalse( response.summaryText().isBlank() );
    }

    @Test
    public void listingToleratesANullMap()
    {
        CachedResourcesResponse response = CachedResourcesResponse.from( null );

        assertEquals( 0, response.totalResources() );
        assertTrue( response.resources().isEmpty() );
    }

    // ---- stats -----------------------------------------------------------

    @Test
    public void statsAdvertiseEachCountAgainstItsLimit()
    {
        Map<String, Object> fields = properties( CacheStatsResponse.class );

        assertEquals( "integer", typeOf( fields, "resourceCount" ) );
        assertEquals( "integer", typeOf( fields, "maxResources" ) );
        assertEquals( "integer", typeOf( fields, "totalEstimatedTokens" ) );
        assertEquals( "integer", typeOf( fields, "maxTotalTokens" ) );
    }

    @Test
    public void statsReportAnEmptyCache()
    {
        CacheStatsResponse stats = CacheStatsResponse.of( 0, 20, 0, 100_000 );

        assertEquals( 0, stats.resourceCount() );
        assertEquals( 20, stats.maxResources() );
        assertEquals( 100_000, stats.maxTotalTokens() );
        assertFalse( stats.atCapacity() );
    }

    @Test
    public void statsFlagCapacityReachedByResourceCount()
    {
        assertTrue( CacheStatsResponse.of( 20, 20, 10, 100_000 ).atCapacity(),
                "the next read evicts the least recently used resource" );
        assertFalse( CacheStatsResponse.of( 19, 20, 10, 100_000 ).atCapacity() );
    }

    @Test
    public void statsFlagCapacityReachedByTokenBudget()
    {
        assertTrue( CacheStatsResponse.of( 2, 20, 100_000, 100_000 ).atCapacity() );
        assertFalse( CacheStatsResponse.of( 2, 20, 99_999, 100_000 ).atCapacity() );
    }

    // ---- serialization ---------------------------------------------------

    @Test
    public void listingSerializesToStructuredContent()
    {
        // The same serialization becomes both structuredContent and the text block, so
        // a field that does not survive Jackson does not reach the caller at all.
        Map<String, Object> map = McpJson.toMap(
                CachedResourcesResponse.from( cache( workspaceFile( "/P/src/A.java", "1234", 2, 4242L ) ) ) );

        List<Map<String, Object>> resources = (List<Map<String, Object>>) map.get( "resources" );
        Map<String, Object> entry = resources.get( 0 );

        assertEquals( ResourceType.WORKSPACE_FILE.name(), entry.get( "type" ) );
        assertEquals( "P", entry.get( "projectName" ) );
        assertEquals( "src/A.java", entry.get( "filePath" ) );
        assertEquals( 4242L, ( (Number) entry.get( "modificationStamp" ) ).longValue() );
        assertEquals( 2, ( (Number) entry.get( "cacheRevision" ) ).intValue() );
        assertNotNull( entry.get( "cachedAt" ) );
    }

    @Test
    public void statsSerializeToStructuredContent()
    {
        Map<String, Object> map = McpJson.toMap( CacheStatsResponse.of( 3, 20, 4000, 100_000 ) );

        assertEquals( 3, ( (Number) map.get( "resourceCount" ) ).intValue() );
        assertEquals( 20, ( (Number) map.get( "maxResources" ) ).intValue() );
        assertEquals( 4000, ( (Number) map.get( "totalEstimatedTokens" ) ).intValue() );
        assertEquals( 100_000, ( (Number) map.get( "maxTotalTokens" ) ).intValue() );
    }
}
