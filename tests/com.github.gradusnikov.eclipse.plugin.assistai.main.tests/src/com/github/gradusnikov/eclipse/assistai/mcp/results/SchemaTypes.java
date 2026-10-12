package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Reads a generated output schema with the null alternative set aside.
 * <p>
 * Most fields are advertised as {@code {"anyOf": [{"type": "string"}, {"type": "null"}]}}
 * rather than {@code {"type": "string"}}, because a Java reference can be absent and a
 * strict client discards a whole payload that contradicts the schema it was given. The
 * nullable form is spelled with {@code anyOf} rather than {@code ["string","null"]}
 * because several MCP clients only accept a string-valued {@code type}. A test asking
 * only <em>which</em> type a field carries, or which properties or constants a nested
 * record or enum declares, should not have to spell that out, and should not break the
 * next time the nullability rule is retuned - as it was when arrays went back to a bare
 * type, and again when nullability moved from a type list to {@code anyOf}.
 * <p>
 * Where the null itself is the point - a modification stamp that is absent rather than
 * a sentinel, a Javadoc body that is missing rather than empty - the test asserts
 * {@link #alternatives(Map)} instead.
 */
public final class SchemaTypes
{
    private SchemaTypes()
    {
    }

    /**
     * @param schema the schema of a single field, or {@code null}
     * @return the branch that describes a present value: the schema itself when it is
     *         not nullable, otherwise its non-null {@code anyOf} branch
     */
    @SuppressWarnings( "unchecked" )
    public static Map<String, Object> nonNull( Object schema )
    {
        if ( !( schema instanceof Map<?, ?> map ) )
        {
            return null;
        }
        if ( map.get( "anyOf" ) instanceof Collection<?> branches )
        {
            return branches.stream()
                    .filter( Map.class::isInstance )
                    .map( branch -> (Map<String, Object>) branch )
                    .filter( branch -> !"null".equals( branch.get( "type" ) ) )
                    .findFirst()
                    .orElse( null );
        }
        return (Map<String, Object>) map;
    }

    /**
     * @param fieldSchema the schema of a single field
     * @return its type, ignoring the {@code "null"} alternative
     */
    public static String carriedBy( Map<String, Object> fieldSchema )
    {
        Map<String, Object> present = nonNull( fieldSchema );
        return present == null ? "null" : (String) present.get( "type" );
    }

    /**
     * @param fieldSchema the schema of a single field
     * @return every type it admits, {@code "null"} included when it is nullable
     */
    public static List<Object> alternatives( Map<String, Object> fieldSchema )
    {
        if ( fieldSchema.get( "anyOf" ) instanceof Collection<?> branches )
        {
            return branches.stream()
                    .<Object>map( branch -> ( (Map<?, ?>) branch ).get( "type" ) )
                    .toList();
        }
        return List.of( fieldSchema.get( "type" ) );
    }

    /**
     * @param objectSchema the schema of a nested record, nullable or not
     * @return the properties it declares
     */
    @SuppressWarnings( "unchecked" )
    public static Map<String, Object> propertiesOf( Object objectSchema )
    {
        return (Map<String, Object>) nonNull( objectSchema ).get( "properties" );
    }

    /**
     * @param enumSchema the schema of an enum field, nullable or not
     * @return the constants it declares
     */
    @SuppressWarnings( "unchecked" )
    public static List<String> enumOf( Object enumSchema )
    {
        return (List<String>) nonNull( enumSchema ).get( "enum" );
    }
}
