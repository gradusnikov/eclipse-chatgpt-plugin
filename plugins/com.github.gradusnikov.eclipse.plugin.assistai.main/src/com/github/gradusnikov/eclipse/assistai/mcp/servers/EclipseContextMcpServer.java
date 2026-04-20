package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;
import com.github.gradusnikov.eclipse.assistai.mcp.services.LocalHistoryService;
import com.github.gradusnikov.eclipse.assistai.resources.CachedResource;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceCache;

import jakarta.inject.Inject;

@Creatable
@McpServer(name = "eclipse-context")
public class EclipseContextMcpServer
{
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter
            .ofPattern( "yyyy-MM-dd HH:mm:ss" )
            .withZone( ZoneId.systemDefault() );

    @Inject
    private ResourceCache resourceCache;

    @Inject
    private LocalHistoryService localHistoryService;

    @Tool(name = "listCachedResources",
          description = "Lists all resources currently cached in the Eclipse workspace context. "
                      + "Shows URIs, types, version numbers, timestamps, and token estimates. "
                      + "Use this to see what files, classes, and data the user has been working with.",
          type = "object")
    public String listCachedResources()
    {
        Map<URI, CachedResource> all = resourceCache.getAll();
        if ( all.isEmpty() )
        {
            return "No resources cached. Use eclipse-ide tools (getSource, readProjectResource, "
                 + "getCurrentlyOpenedFile, etc.) to load resources into the cache.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append( "# Cached Resources\n\n" );
        sb.append( resourceCache.getStats() ).append( "\n\n" );
        sb.append( String.format( "%-50s  %-15s  %-5s  %-20s  %s\n",
                "URI", "Type", "Ver", "Cached At", "Tokens" ) );
        sb.append( "-".repeat( 110 ) ).append( "\n" );

        for ( var entry : all.entrySet() )
        {
            CachedResource r = entry.getValue();
            String uri = truncate( entry.getKey().toString(), 50 );
            sb.append( String.format( "%-50s  %-15s  v%-4d  %-20s  ~%d\n",
                    uri,
                    r.descriptor().type(),
                    r.version(),
                    TIMESTAMP_FMT.format( r.cachedAt() ),
                    r.estimateTokens() ) );
        }

        return sb.toString();
    }

    @Tool(name = "getCachedResource",
          description = "Gets the content of a specific cached resource by URI without re-reading from disk. "
                      + "Use listCachedResources first to see available URIs. "
                      + "Returns the cached version â fast, no I/O.",
          type = "object")
    public String getCachedResource(
            @ToolParam(name = "resourceUri",
                       description = "The URI of the cached resource (e.g. 'workspace:///ProjectName/src/File.java' or 'jdt:///com.example.MyClass')",
                       required = true) String resourceUri )
    {
        try
        {
            URI uri = URI.create( resourceUri );
            return resourceCache.get( uri )
                    .map( r -> {
                        StringBuilder sb = new StringBuilder();
                        sb.append( "# " ).append( r.descriptor().displayName() )
                          .append( " (v" ).append( r.version() )
                          .append( ", cached " ).append( TIMESTAMP_FMT.format( r.cachedAt() ) )
                          .append( ")\n\n" );
                        sb.append( r.content() );
                        return sb.toString();
                    } )
                    .orElse( "Resource not found in cache: " + resourceUri
                           + "\nUse listCachedResources to see available URIs." );
        }
        catch ( Exception e )
        {
            return "Invalid URI: " + resourceUri + " â " + e.getMessage();
        }
    }

    @Tool(name = "getCacheStats",
          description = "Gets resource cache statistics: number of resources, token usage, and limits.",
          type = "object")
    public String getCacheStats()
    {
        StringBuilder sb = new StringBuilder();
        sb.append( "# Resource Cache Statistics\n\n" );
        sb.append( resourceCache.getStats() ).append( "\n\n" );

        if ( !resourceCache.isEmpty() )
        {
            sb.append( "## Summary\n" );
            sb.append( resourceCache.toSummary() );
        }

        return sb.toString();
    }

    // --- Local History tools ---

    @Tool(name = "getFileHistory",
          description = "Lists the Local History versions of a file maintained by Eclipse. "
                      + "Shows timestamps and sizes for each historical version. "
                      + "Eclipse automatically saves file history on every modification through the IDE.",
          type = "object")
    public String getFileHistory(
            @ToolParam(name = "projectName", description = "The name of the project", required = true) String projectName,
            @ToolParam(name = "filePath", description = "Path to the file relative to the project root", required = true) String filePath,
            @ToolParam(name = "maxEntries", description = "Maximum number of history entries to show (default: 20)", required = false) String maxEntries )
    {
        return localHistoryService.getFileHistory( projectName, filePath, maxEntries );
    }

    @Tool(name = "getFileHistoryContent",
          description = "Gets the content of a specific Local History version of a file. "
                      + "Use getFileHistory first to see available versions and their indices.",
          type = "object")
    public String getFileHistoryContent(
            @ToolParam(name = "projectName", description = "The name of the project", required = true) String projectName,
            @ToolParam(name = "filePath", description = "Path to the file relative to the project root", required = true) String filePath,
            @ToolParam(name = "index", description = "The history index (0 = most recent, from getFileHistory)", required = true) String index )
    {
        return localHistoryService.getFileHistoryContent( projectName, filePath, index );
    }

    @Tool(name = "restoreFileVersion",
          description = "Restores a file to a specific Local History version. "
                      + "The current content becomes a new history entry before the restore. "
                      + "Use getFileHistory to find the version index.",
          type = "object")
    public String restoreFileVersion(
            @ToolParam(name = "projectName", description = "The name of the project", required = true) String projectName,
            @ToolParam(name = "filePath", description = "Path to the file relative to the project root", required = true) String filePath,
            @ToolParam(name = "index", description = "The history index to restore (0 = most recent, from getFileHistory)", required = true) String index )
    {
        return localHistoryService.restoreFileVersion( projectName, filePath, index );
    }

    @Tool(name = "compareWithHistory",
          description = "Shows a unified diff between the current file content and a Local History version. "
                      + "Use getFileHistory to find the version index.",
          type = "object")
    public String compareWithHistory(
            @ToolParam(name = "projectName", description = "The name of the project", required = true) String projectName,
            @ToolParam(name = "filePath", description = "Path to the file relative to the project root", required = true) String filePath,
            @ToolParam(name = "index", description = "The history index to compare against (0 = most recent, from getFileHistory)", required = true) String index )
    {
        return localHistoryService.compareWithHistory( projectName, filePath, index );
    }

    // --- helpers ---

    private static String truncate( String s, int maxLen )
    {
        if ( s.length() <= maxLen ) return s;
        return "..." + s.substring( s.length() - maxLen + 3 );
    }
}
