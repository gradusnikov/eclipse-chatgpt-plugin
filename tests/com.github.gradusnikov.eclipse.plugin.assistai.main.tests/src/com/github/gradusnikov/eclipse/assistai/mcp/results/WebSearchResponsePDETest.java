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
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.servers.DuckDuckSearchMcpServer;

/**
 * The record {@code webSearch} returns, the schema generated from it, and the
 * extraction that fills it.
 * <p>
 * Nothing here touches the network. The tool fetches a DuckDuckGo result page and then
 * reads hits out of it; only the second half is behaviour this project owns, so the
 * extraction is exercised against fixed markup. A test that searched for real would be
 * asserting on whatever DuckDuckGo ranked that day, and would fail on a train.
 */
public class WebSearchResponsePDETest
{
    /**
     * The shape DuckDuckGo's HTML endpoint emits: a {@code .results_links} block per hit,
     * the link inside an {@code h2.result__title}, the extract in a sibling
     * {@code .result__snippet}. The third block deliberately has no snippet, and the
     * second a protocol-relative href, because both occur on real pages.
     */
    private static final String RESULT_PAGE = """
            <html><body>
              <div class="results">
                <div class="result results_links results_links_deep web-result">
                  <div class="links_main result__body">
                    <h2 class="result__title">
                      <a class="result__a" href="https://example.com/one">First   hit</a>
                    </h2>
                    <a class="result__snippet" href="https://example.com/one">A snippet for the first hit.</a>
                  </div>
                </div>
                <div class="result results_links results_links_deep web-result">
                  <div class="links_main result__body">
                    <h2 class="result__title">
                      <a class="result__a" href="//duckduckgo.com/l/?uddg=second">Second hit</a>
                    </h2>
                    <a class="result__snippet">A snippet for the second hit.</a>
                  </div>
                </div>
                <div class="result results_links">
                  <div class="links_main result__body">
                    <h2 class="result__title"><a href="https://example.com/three">Third hit</a></h2>
                  </div>
                </div>
              </div>
            </body></html>
            """;

    private static final String EMPTY_PAGE = """
            <html><body>
              <div class="results">
                <div class="no-results">No results found for this query.</div>
              </div>
            </body></html>
            """;

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
        return SchemaTypes.propertiesOf( array.get( "items" ) );
    }

    // ---- the schema ------------------------------------------------------

    @Test
    public void advertisesTheUrlAsAFieldOfEveryResult()
    {
        Map<String, Object> result = itemsOf( properties( WebSearchResponse.class ), "results" );

        // The caller's next act is to fetch one of these pages, so the URL has to be a
        // field it can read - not something to be scraped back out of a rendered list.
        assertEquals( "string", SchemaTypes.carriedBy( (Map<String, Object>) result.get( "url" ) ),
                result.keySet().toString() );
        assertTrue( result.containsKey( "title" ) );
        assertTrue( result.containsKey( "snippet" ) );
    }

    @Test
    public void advertisesTheQueryAndACount()
    {
        Map<String, Object> fields = properties( WebSearchResponse.class );

        assertTrue( fields.containsKey( "query" ) );
        assertEquals( "integer", ( (Map<String, Object>) fields.get( "totalResults" ) ).get( "type" ),
                "a count answers 'did this find anything' without parsing prose" );
    }

    @Test
    public void theToolAdvertisesTheRecordItReturns() throws Exception
    {
        Tool annotation = DuckDuckSearchMcpServer.class
                .getMethod( "webSearch", String.class )
                .getAnnotation( Tool.class );

        assertNotNull( annotation );
        assertEquals( WebSearchResponse.class, annotation.outputType(),
                "the generated outputSchema is what a client is told to expect" );
        assertEquals( WebSearchResponse.class,
                DuckDuckSearchMcpServer.class.getMethod( "webSearch", String.class ).getReturnType() );
    }

    // ---- extraction ------------------------------------------------------

    @Test
    public void readsTitleUrlAndSnippetFromAResultPage()
    {
        WebSearchResponse response = DuckDuckSearchMcpServer.parseSearchResults( "eclipse jdt", RESULT_PAGE );

        assertEquals( "eclipse jdt", response.query() );
        assertEquals( 2, response.totalResults() );
        assertEquals( 2, response.results().size() );

        WebSearchResponse.Result first = response.results().get( 0 );
        assertEquals( "First hit", first.title(), "the link text, with whitespace normalized" );
        assertEquals( "https://example.com/one", first.url() );
        assertEquals( "A snippet for the first hit.", first.snippet() );
    }

    @Test
    public void keepsTheEnginesRanking()
    {
        WebSearchResponse response = DuckDuckSearchMcpServer.parseSearchResults( "q", RESULT_PAGE );

        assertEquals( List.of( "First hit", "Second hit" ),
                response.results().stream().map( WebSearchResponse.Result::title ).toList() );
    }

    @Test
    public void makesProtocolRelativeLinksAbsolute()
    {
        WebSearchResponse response = DuckDuckSearchMcpServer.parseSearchResults( "q", RESULT_PAGE );

        // "//duckduckgo.com/..." is not fetchable on its own; every caller would
        // otherwise have to patch it up before handing it to a page reader.
        assertEquals( "https://duckduckgo.com/l/?uddg=second", response.results().get( 1 ).url() );
    }

    @Test
    public void skipsABlockThatIsNotAUsableHit()
    {
        WebSearchResponse response = DuckDuckSearchMcpServer.parseSearchResults( "q", RESULT_PAGE );

        assertFalse( response.results().stream().anyMatch( r -> "Third hit".equals( r.title() ) ),
                "a block with no snippet is a placeholder or an advert, not a result" );
    }

    // ---- nothing found ---------------------------------------------------

    @Test
    public void reportsFindingNothingAsAnEmptyListAndACount()
    {
        WebSearchResponse response = DuckDuckSearchMcpServer.parseSearchResults( "no such thing", EMPTY_PAGE );

        assertEquals( 0, response.totalResults() );
        assertTrue( response.results().isEmpty() );
        assertTrue( response.isEmpty() );
        assertEquals( "no such thing", response.query(),
                "the query survives so an empty result is still identifiable" );
    }

    @Test
    public void anEmptyResultIsStillAWellFormedPayload()
    {
        Map<String, Object> map = McpJson.toMap(
                DuckDuckSearchMcpServer.parseSearchResults( "no such thing", EMPTY_PAGE ) );

        // The empty case must serialize to the same fields as any other, so a client
        // never has to tell "found nothing" from "went wrong" by reading a sentence.
        assertEquals( 0, map.get( "totalResults" ) );
        assertEquals( List.of(), map.get( "results" ) );
        assertEquals( "no such thing", map.get( "query" ) );
    }

    // ---- serialization ---------------------------------------------------

    @Test
    public void roundTripsThroughTheStructuredContentMapper()
    {
        // No JavaTimeModule is registered on that mapper, and a field it cannot handle
        // only fails once a real call is made; this catches it here instead.
        Map<String, Object> map = McpJson.toMap(
                DuckDuckSearchMcpServer.parseSearchResults( "eclipse jdt", RESULT_PAGE ) );

        assertEquals( 2, map.get( "totalResults" ) );

        @SuppressWarnings( "unchecked" )
        List<Map<String, Object>> results = (List<Map<String, Object>>) map.get( "results" );
        assertEquals( 2, results.size() );
        assertEquals( "https://example.com/one", results.get( 0 ).get( "url" ) );
        assertEquals( "First hit", results.get( 0 ).get( "title" ) );
        assertEquals( "A snippet for the first hit.", results.get( 0 ).get( "snippet" ) );
    }

    @Test
    public void doesNotClaimAResultSetWasTruncated()
    {
        // Nothing here cuts the listing short, so there is no truncated flag to read as
        // a guarantee the tool is not making.
        assertFalse( properties( WebSearchResponse.class ).containsKey( "truncated" ) );

        WebSearchResponse response = DuckDuckSearchMcpServer.parseSearchResults( "q", RESULT_PAGE );
        assertEquals( response.results().size(), response.totalResults() );
    }
}
