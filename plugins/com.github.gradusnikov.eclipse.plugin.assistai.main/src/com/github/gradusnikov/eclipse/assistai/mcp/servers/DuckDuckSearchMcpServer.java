package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import java.io.IOException;
import java.net.URL;
import java.net.URLEncoder;

import jakarta.inject.Inject;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.gradusnikov.eclipse.assistai.mcp.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.ToolParam;

@Creatable
@McpServer(name="duck-duck-search")
public class DuckDuckSearchMcpServer
{
    @Inject
    ILog logger;

    @Tool(name="webSearch", description="Performs a search using a Duck Duck Go search engine and returns the search result json.", type="object")
    public String webSearch(
            @ToolParam(name="query", description="A search query", required=true) String query)
    {
        try
        {
            ObjectMapper mapper = new ObjectMapper();
            ArrayNode resultsArray = mapper.createArrayNode();

            String encodedQuery = URLEncoder.encode( query, "UTF-8" );
            String url = "https://html.duckduckgo.com/html/?q=" + encodedQuery;

            logger.info( "Performing web search: " + url );

            Document document = Jsoup.parse( new URL( url ), 0 );
            Elements results = document.select( ".results_links" );

            for ( Element result : results )
            {
                Element titleElement = result.select( ".result__title" ).select( "a" ).first();
                Element snippetElement = result.select( ".result__snippet" ).first();
                if ( titleElement != null && snippetElement != null )
                {
                    String title = titleElement.text();
                    String resultUrl = titleElement.attr( "href" );
                    if ( resultUrl.startsWith( "//" ) )
                    {
                        resultUrl = "https:" + resultUrl;
                    }
                    String snippetText = snippetElement.text();

                    ObjectNode resultNode = mapper.createObjectNode();
                    resultNode.put( "title", title );
                    resultNode.put( "url", resultUrl );
                    resultNode.put( "snippet", snippetText );

                    resultsArray.add( resultNode );
                }
            }

            String jsonResults = mapper.writerWithDefaultPrettyPrinter().writeValueAsString( resultsArray );
            logger.info( "Search results for query \"" + query + "\":\n" + jsonResults );
            return jsonResults;
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }
    }
}
