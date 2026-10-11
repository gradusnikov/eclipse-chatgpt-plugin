package com.github.gradusnikov.eclipse.assistai.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.resources.EditResult;

/**
 * Plain JUnit tests for output-schema generation.
 * <p>
 * These guard the promise a tool makes to its clients: if the schema stops matching
 * the record it was generated from, a client that trusted it starts reading fields
 * that are not there. Pure reflection, so no Eclipse runtime is needed.
 */
public class McpOutputSchemasTest
{
    enum Colour
    {
        RED, GREEN
    }

    /**
     * Every Java reference can be null, so its schema admits null; only a primitive
     * gets a bare type. Advertising the bare type for a reference made a payload fail
     * a client's own validation the moment the field was null - which is precisely
     * when the null carried information, such as "this resource has no modification
     * stamp" or "no source location could be resolved". A list is exempt: convention 7
     * makes an empty result an empty list, never null.
     */
    private static final List<Object> NULLABLE_STRING = List.of( "string", "null" );
    private static final List<Object> NULLABLE_INTEGER = List.of( "integer", "null" );
    private static final List<Object> NULLABLE_OBJECT = List.of( "object", "null" );

    record Leaf( String name, int count )
    {
    }

    record Branch( Leaf leaf, List<Leaf> leaves, Colour colour, boolean flag, double ratio, long stamp )
    {
    }

    record SelfReferencing( String name, SelfReferencing child )
    {
    }

    private static Map<String, Object> properties( Map<String, Object> schema )
    {
        return schemaValue( branchWithType( schema, "object" ), "properties" );
    }

    private static Map<String, Object> property( Map<String, Object> schema, String name )
    {
        return schemaValue( properties( schema ), name );
    }

    private static Map<String, Object> schemaValue( Map<String, Object> schema, String name )
    {
        return schema( schema.get( name ) );
    }

    private static List<String> stringValues( Map<String, Object> schema, String name )
    {
        return ( (Collection<?>) schema.get( name ) ).stream().map( String::valueOf ).toList();
    }

    private static Map<String, Object> branchWithType( Map<String, Object> schema, String jsonType )
    {
        if ( jsonType.equals( schema.get( "type" ) ) )
        {
            return schema;
        }
        return ( (Collection<?>) schema.get( "anyOf" ) ).stream()
                .map( McpOutputSchemasTest::schema )
                .filter( branch -> jsonType.equals( branch.get( "type" ) ) )
                .findFirst()
                .orElseThrow();
    }

    private static List<Object> alternativeTypes( Map<String, Object> schema )
    {
        return ( (Collection<?>) schema.get( "anyOf" ) ).stream()
                .map( McpOutputSchemasTest::schema )
                .map( branch -> branch.get( "type" ) )
                .toList();
    }

    private static Map<String, Object> schema( Object value )
    {
        Map<String, Object> result = new LinkedHashMap<>();
        ( (Map<?, ?>) value ).forEach( ( key, entryValue ) -> result.put( String.valueOf( key ), entryValue ) );
        return result;
    }

    private static void assertOnlyStringValuedTypes( Object node )
    {
        if ( node instanceof Map<?, ?> map )
        {
            Object type = map.get( "type" );
            assertTrue( type == null || type instanceof String, () -> "Non-portable type declaration: " + type );
            map.values().forEach( McpOutputSchemasTest::assertOnlyStringValuedTypes );
        }
        else if ( node instanceof Collection<?> collection )
        {
            collection.forEach( McpOutputSchemasTest::assertOnlyStringValuedTypes );
        }
    }

    // ---- types that carry no structure -----------------------------------

    @Test
    public void describesNothingForVoid()
    {
        assertNull( McpOutputSchemas.forType( Void.class ) );
        assertNull( McpOutputSchemas.forType( void.class ) );
    }

    @Test
    public void describesNothingForPlainText()
    {
        // A tool returning prose must not advertise a schema promising structure.
        assertNull( McpOutputSchemas.forType( String.class ) );
        assertNull( McpOutputSchemas.forType( Object.class ) );
        assertNull( McpOutputSchemas.forType( null ) );
    }

    // ---- records ---------------------------------------------------------

    @Test
    public void describesARecordAsAnObject()
    {
        Map<String, Object> schema = McpOutputSchemas.forType( Leaf.class );

        assertEquals( "object", schema.get( "type" ) );
        assertEquals( NULLABLE_STRING, alternativeTypes( property( schema, "name" ) ) );
        assertEquals( "integer", property( schema, "count" ).get( "type" ) );
    }

    @Test
    public void keepsRecordComponentOrder()
    {
        Map<String, Object> schema = McpOutputSchemas.forType( Leaf.class );

        assertEquals( List.of( "name", "count" ), List.copyOf( properties( schema ).keySet() ) );
    }

    @Test
    public void describesNestedRecords()
    {
        Map<String, Object> schema = McpOutputSchemas.forType( Branch.class );
        Map<String, Object> leaf = property( schema, "leaf" );

        assertEquals( NULLABLE_OBJECT, alternativeTypes( leaf ) );
        assertEquals( NULLABLE_STRING, alternativeTypes( property( leaf, "name" ) ) );
    }

    @Test
    public void describesGenericListsWithItemTypes()
    {
        Map<String, Object> leaves = property( McpOutputSchemas.forType( Branch.class ), "leaves" );

        assertEquals( "array", leaves.get( "type" ) );
        Map<String, Object> items = schemaValue( leaves, "items" );
        assertEquals( NULLABLE_OBJECT, alternativeTypes( items ) );
        assertTrue( properties( items ).containsKey( "count" ) );
    }

    @Test
    public void describesEnumsWithTheirConstants()
    {
        Map<String, Object> colour = property( McpOutputSchemas.forType( Branch.class ), "colour" );

        assertEquals( NULLABLE_STRING, alternativeTypes( colour ) );
        assertEquals( List.of( "RED", "GREEN" ), branchWithType( colour, "string" ).get( "enum" ) );
    }

    @Test
    public void mapsJavaPrimitivesToJsonTypes()
    {
        Map<String, Object> schema = McpOutputSchemas.forType( Branch.class );

        assertEquals( "boolean", property( schema, "flag" ).get( "type" ) );
        assertEquals( "number", property( schema, "ratio" ).get( "type" ) );
        assertEquals( "integer", property( schema, "stamp" ).get( "type" ) );
    }

    @Test
    public void terminatesOnASelfReferencingRecord()
    {
        Map<String, Object> schema = McpOutputSchemas.forType( SelfReferencing.class );

        assertEquals( "object", schema.get( "type" ) );
        assertEquals( NULLABLE_OBJECT, alternativeTypes( property( schema, "child" ) ) );
    }

    // ---- the shape actually shipped --------------------------------------

    @Test
    public void describesTheEditResultToolsAdvertise()
    {
        Map<String, Object> schema = McpOutputSchemas.forType( EditResult.class );

        assertNotNull( schema );
        assertEquals( "object", schema.get( "type" ) );

        Map<String, Object> fields = properties( schema );
        assertTrue( fields.containsKey( "status" ), fields.keySet().toString() );
        assertTrue( fields.containsKey( "versionBefore" ) );
        assertTrue( fields.containsKey( "versionAfter" ) );
        assertTrue( fields.containsKey( "unifiedDiff" ) );
        assertTrue( fields.containsKey( "undoHistoryTimestamp" ) );
        assertTrue( fields.containsKey( "diagnostics" ) );
    }

    @Test
    public void advertisesTheEditStatusValuesAClientBranchesOn()
    {
        Map<String, Object> status = property( McpOutputSchemas.forType( EditResult.class ), "status" );

        List<String> values = stringValues( branchWithType( status, "string" ), "enum" );
        assertTrue( values.contains( "APPLIED" ), values.toString() );
        assertTrue( values.contains( "REJECTED" ), values.toString() );
        assertTrue( values.contains( "PREVIEW" ), values.toString() );
    }

    @Test
    public void advertisesTheModificationStampUsedForConcurrency()
    {
        Map<String, Object> versionAfter =
                property( McpOutputSchemas.forType( EditResult.class ), "versionAfter" );

        assertEquals( NULLABLE_INTEGER,
                alternativeTypes( property( versionAfter, "modificationStamp" ) ) );
    }

    @Test
    public void advertisesDiagnosticsAsAnArrayOfCodedObjects()
    {
        Map<String, Object> diagnostics =
                property( McpOutputSchemas.forType( EditResult.class ), "diagnostics" );

        assertEquals( "array", diagnostics.get( "type" ) );
        Map<String, Object> item = schemaValue( diagnostics, "items" );
        Map<String, Object> code = property( item, "code" );
        assertTrue( stringValues( branchWithType( code, "string" ), "enum" ).contains( "VERSION_CONFLICT" ) );
    }

    @Test
    public void doesNotClaimFieldsAreRequired()
    {
        // Optional sections are routinely null; declaring them required would make a
        // validating client reject responses that are perfectly correct.
        assertFalse( McpOutputSchemas.forType( EditResult.class ).containsKey( "required" ) );
    }

    @Test
    public void alwaysUsesAStringValuedTypeForMcpClientCompatibility()
    {
        assertOnlyStringValuedTypes( McpOutputSchemas.forType( Branch.class ) );
        assertOnlyStringValuedTypes( McpOutputSchemas.forType( EditResult.class ) );
    }
}
