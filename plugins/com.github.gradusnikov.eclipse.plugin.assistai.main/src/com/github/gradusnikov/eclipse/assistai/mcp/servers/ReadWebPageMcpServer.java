package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;

import jakarta.inject.Inject;

@Creatable
@McpServer(name = "webpage-reader")
public class ReadWebPageMcpServer
{
    @Inject
    private ILog logger;

    @Tool(name="readWebPage", description="Reads the content of the given web site and returns its content as a markdown text.", type="object")
    public String readWebPage(
            @ToolParam(name="url", description="A web site URL", required=true) String url)
    {
        try
        {
            logger.info( "Fetching web page: " + url );

            HttpClient client = HttpClient.newBuilder()
                    .followRedirects( HttpClient.Redirect.NORMAL )
                    .connectTimeout( Duration.ofSeconds( 15 ) )
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri( URI.create( url ) )
                    .timeout( Duration.ofSeconds( 30 ) )
                    .header( "User-Agent", "Mozilla/5.0 (compatible; AssistAI/1.0)" )
                    .GET()
                    .build();

            HttpResponse<String> response = client.send( request, HttpResponse.BodyHandlers.ofString() );
            String html = response.body();

            Document document = Jsoup.parse( html );
            StringBuilder content = new StringBuilder();
            var converter = FlexmarkHtmlConverter.builder().build();

            for ( Element body : document.getElementsByTag( "body" ) )
            {
                content.append( converter.convert( body.toString() ) );
            }

            String result = content.toString();
            logger.info( "Web page content " + url + "\n\n" + result );
            return result;
        }
        catch ( Exception e )
        {
            throw new RuntimeException( e );
        }
    }
}
