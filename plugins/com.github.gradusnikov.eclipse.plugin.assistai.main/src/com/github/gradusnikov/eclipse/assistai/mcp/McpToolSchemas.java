package com.github.gradusnikov.eclipse.assistai.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Adapts the raw {@code Map<String, Object>} that {@link McpSchema.Tool#inputSchema()}
 * returns since MCP SDK 2.0.0 back into the typed pieces the provider clients need.
 * <p>
 * In 1.1.x {@code inputSchema()} returned a structured {@code McpSchema.JsonSchema}
 * exposing {@code type()}, {@code properties()} and {@code required()}; 2.0.0 hands back
 * the underlying JSON map instead. These accessors read the same fields off that map and
 * never return {@code null}, so callers can drop the old null guards.
 */
public final class McpToolSchemas
{
    private McpToolSchemas()
    {
    }

    /** The schema {@code "type"}, defaulting to {@code "object"} when unset. */
    public static String type( McpSchema.Tool tool )
    {
        Object type = tool.inputSchema().get( "type" );
        return type instanceof String value ? value : "object";
    }

    /** The schema {@code "properties"} map, or an empty map when unset. */
    @SuppressWarnings( "unchecked" )
    public static Map<String, Object> properties( McpSchema.Tool tool )
    {
        Object properties = tool.inputSchema().get( "properties" );
        return properties instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    /** The schema {@code "required"} list, or an empty list when unset. */
    @SuppressWarnings( "unchecked" )
    public static List<String> required( McpSchema.Tool tool )
    {
        Object required = tool.inputSchema().get( "required" );
        return required instanceof List<?> list ? (List<String>) list : List.of();
    }
}
