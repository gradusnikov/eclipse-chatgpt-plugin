package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import java.net.URI;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;
import com.github.gradusnikov.eclipse.assistai.mcp.results.CacheStatsResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.CachedResourcesResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiffResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.FileHistoryResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.services.LocalHistoryService;
import com.github.gradusnikov.eclipse.assistai.resources.CachedResource;
import com.github.gradusnikov.eclipse.assistai.mcp.results.Diagnostic;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceCache;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceDescriptor;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceReadResult;

import jakarta.inject.Inject;

@Creatable
@McpServer(name = "eclipse-context")
public class EclipseContextMcpServer
{
    @Inject
    private ResourceCache resourceCache;

    @Inject
    private LocalHistoryService localHistoryService;

    @Tool(name = "listCachedResources",
          description = "Lists all resources currently cached in the Eclipse workspace context. "
                      + "Each entry gives the URI getCachedResource takes, the resource type, the "
                      + "project and project-relative filePath when it is a workspace file, when it "
                      + "was cached, its modificationStamp and an estimated token count. "
                      + "Use this to see what files, classes, and data the user has been working with.",
          type = "object", outputType = CachedResourcesResponse.class)
    public CachedResourcesResponse listCachedResources()
    {
        return CachedResourcesResponse.from( resourceCache.getAll() );
    }

    @Tool(name = "getCachedResource",
          description = "Gets the content of a specific cached resource by URI without re-reading from disk. "
                      + "Use listCachedResources first to see available URIs. "
                      + "Returns the cached version - fast, no I/O - and says whether it is still "
                      + "what the workspace holds.",
          type = "object", outputType = ResourceReadResult.class)
    public ResourceReadResult getCachedResource(
            @ToolParam(name = "resourceUri",
                       description = "The URI of the cached resource (e.g. 'workspace:///ProjectName/src/File.java' or 'jdt:///com.example.MyClass')",
                       required = true) String resourceUri )
    {
        try
        {
            // Tolerates a raw space in the URI: a caller echoing back a path it saw
            // elsewhere would otherwise just get "Invalid URI".
            URI uri = ResourceDescriptor.parseUri( resourceUri );
            return resourceCache.get( uri )
                    .map( CachedResource::toReadResult )
                    .orElseGet( () -> ResourceReadResult.failed( null, null, Diagnostic.fatal(
                            DiagnosticCode.RESOURCE_NOT_FOUND,
                            "Nothing cached under " + resourceUri
                                    + ". Use listCachedResources to see the available URIs." ) ) );
        }
        catch ( Exception e )
        {
            return ResourceReadResult.failed( null, null, Diagnostic.fatal(
                    DiagnosticCode.RESOURCE_NOT_FOUND, "Invalid URI '" + resourceUri + "': " + e.getMessage() ) );
        }
    }

    @Tool(name = "getCacheStats",
          description = "Gets resource cache statistics: the number of cached resources and the "
                      + "estimated tokens they occupy, each against the limit at which the cache "
                      + "starts evicting the least recently used entry. "
                      + "Use listCachedResources for what is actually in there.",
          type = "object", outputType = CacheStatsResponse.class)
    public CacheStatsResponse getCacheStats()
    {
        return CacheStatsResponse.of(
                resourceCache.size(),
                resourceCache.maxResources(),
                resourceCache.estimateTotalTokens(),
                resourceCache.maxTotalTokens() );
    }

    // --- Local History tools ---

    @Tool(name = "getFileHistory",
          description = "Lists the Local History versions of a file maintained by Eclipse. "
                      + "Shows the historyTimestamp, date and size of each stored version. "
                      + "Eclipse saves file history on every modification through the IDE. "
                      + "Pass a historyTimestamp from this listing to the other history tools.",
          type = "object", outputType = FileHistoryResponse.class)
    public FileHistoryResponse getFileHistory(
            @ToolParam(name = "projectName", description = "The name of the project", required = true) String projectName,
            @ToolParam(name = "filePath", description = "Path to the file relative to the project root", required = true) String filePath,
            @ToolParam(name = "maxEntries", type = "integer", description = "Maximum number of history entries to show (default: 20)", required = false) String maxEntries )
    {
        return localHistoryService.getFileHistory( projectName, filePath, maxEntries );
    }

    @Tool(name = "getFileHistoryContent",
          description = "Gets the content of a specific Local History version of a file. "
                      + "Returns the exact stored content, the range it covers and the version "
                      + "that addresses it again. Use getFileHistory first to see the available "
                      + "historyTimestamp values.",
          type = "object", outputType = ResourceReadResult.class)
    public ResourceReadResult getFileHistoryContent(
            @ToolParam(name = "projectName", description = "The name of the project", required = true) String projectName,
            @ToolParam(name = "filePath", description = "Path to the file relative to the project root", required = true) String filePath,
            @ToolParam(name = "historyTimestamp", type = "integer", description = "The historyTimestamp of the version, from getFileHistory. "
                      + "Identifies the same content even after further saves, which a positional index does not.", required = true) String historyTimestamp )
    {
        return localHistoryService.getFileHistoryContent( projectName, filePath, historyTimestamp );
    }

    @Tool(name = "restoreFileVersion",
          description = "Restores a file to a specific Local History version. "
                      + "The current content becomes a new history entry first, so the restore is itself "
                      + "undoable: the returned undoHistoryTimestamp addresses it. "
                      + "Use getFileHistory to find the historyTimestamp.",
          type = "object", outputType = EditResult.class)
    public EditResult restoreFileVersion(
            @ToolParam(name = "projectName", description = "The name of the project", required = true) String projectName,
            @ToolParam(name = "filePath", description = "Path to the file relative to the project root", required = true) String filePath,
            @ToolParam(name = "historyTimestamp", type = "integer", description = "The historyTimestamp of the version to restore, from getFileHistory", required = true) String historyTimestamp )
    {
        return localHistoryService.restoreFileVersion( projectName, filePath, historyTimestamp );
    }

    @Tool(name = "compareWithHistory",
          description = "Shows a unified diff between the current file content and a Local History version, "
                      + "with the line counts and both versions compared. "
                      + "Use getFileHistory to find the historyTimestamp.",
          type = "object", outputType = DiffResponse.class)
    public DiffResponse compareWithHistory(
            @ToolParam(name = "projectName", description = "The name of the project", required = true) String projectName,
            @ToolParam(name = "filePath", description = "Path to the file relative to the project root", required = true) String filePath,
            @ToolParam(name = "historyTimestamp", type = "integer", description = "The historyTimestamp to compare against, from getFileHistory", required = true) String historyTimestamp )
    {
        return localHistoryService.compareWithHistory( projectName, filePath, historyTimestamp );
    }
}
