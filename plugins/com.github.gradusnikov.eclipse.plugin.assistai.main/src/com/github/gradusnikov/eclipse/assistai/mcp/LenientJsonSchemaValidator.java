package com.github.gradusnikov.eclipse.assistai.mcp;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;

/**
 * Validates tool arguments against their schema, but accepts the string form of an
 * {@code integer}, {@code number} or {@code boolean} argument.
 * <p>
 * Every tool parameter used to be advertised as a string, so clients - and the
 * conversation history of the in-IDE chat - send {@code "120"} and {@code "true"}.
 * Now that the schema says {@code integer} and {@code boolean}, rejecting those would
 * break every one of them. The value itself is not changed: the tool methods take
 * strings either way, and {@link ToolExecutor} converts a JSON number or boolean to
 * one.
 */
final class LenientJsonSchemaValidator implements JsonSchemaValidator
{
    private final JsonSchemaValidator delegate;

    LenientJsonSchemaValidator( JsonSchemaValidator delegate )
    {
        this.delegate = delegate;
    }

    @Override
    public ValidationResponse validate( Map<String, Object> schema, Object structuredContent )
    {
        return delegate.validate( schema, normalize( schema, structuredContent ) );
    }

    @Override
    public ValidationResponse validateSchema( Map<String, Object> schema )
    {
        return delegate.validateSchema( schema );
    }

    @Override
    public void assertConforms( String context, Map<String, Object> schema )
    {
        delegate.assertConforms( context, schema );
    }

    /**
     * Replaces, for validation only, each string argument whose property is declared
     * {@code integer}, {@code number} or {@code boolean} with the value it spells. A
     * string that spells nothing of the kind is left alone, so it still fails.
     */
    static Object normalize( Map<String, Object> schema, Object content )
    {
        if ( schema == null || !( content instanceof Map<?, ?> arguments ) || !( schema.get( "properties" ) instanceof Map<?, ?> properties ) )
        {
            return content;
        }
        Map<Object, Object> normalized = new LinkedHashMap<>( arguments );
        for ( var entry : arguments.entrySet() )
        {
            if ( entry.getValue() instanceof String text && properties.get( entry.getKey() ) instanceof Map<?, ?> property )
            {
                Object value = parse( String.valueOf( property.get( "type" ) ), text.trim() );
                if ( value != null )
                {
                    normalized.put( entry.getKey(), value );
                }
            }
        }
        return normalized;
    }

    private static Object parse( String type, String text )
    {
        try
        {
            return switch ( type )
            {
                case "integer" -> new BigDecimal( text ).toBigIntegerExact();
                case "number" -> new BigDecimal( text );
                case "boolean" -> "true".equalsIgnoreCase( text ) ? Boolean.TRUE : "false".equalsIgnoreCase( text ) ? Boolean.FALSE : null;
                default -> null;
            };
        }
        catch ( NumberFormatException | ArithmeticException e )
        {
            return null;
        }
    }
}
