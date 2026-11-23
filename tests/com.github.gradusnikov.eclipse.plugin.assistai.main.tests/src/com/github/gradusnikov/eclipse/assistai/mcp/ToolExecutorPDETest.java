package com.github.gradusnikov.eclipse.assistai.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;

public class ToolExecutorPDETest
{
    @Test
    public void rejectsUnknownAndMissingRequiredParametersBeforeInvocation()
    {
        ReplaceTools tools = new ReplaceTools();
        ToolExecutor executor = new ToolExecutor( tools );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> executor.call(
                        "replaceString",
                        Map.of( "oldString", "old", "new_string", "replacement" ) ) );

        assertFalse( tools.invoked );
        assertTrue( error.getMessage().contains( "missing required parameters [newString]" ) );
        assertTrue( error.getMessage().contains( "unknown parameters [new_string]" ) );
        assertTrue( error.getMessage().contains( "Expected parameters: [oldString, newString, startLine]" ) );
    }

    @Test
    public void rejectsNullForRequiredParameterBeforeInvocation()
    {
        ReplaceTools tools = new ReplaceTools();
        ToolExecutor executor = new ToolExecutor( tools );
        Map<String, Object> args = new HashMap<>();
        args.put( "oldString", "old" );
        args.put( "newString", null );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> executor.call( "replaceString", args ) );

        assertFalse( tools.invoked );
        assertTrue( error.getMessage().contains( "missing required parameters [newString]" ) );
    }

    @Test
    public void acceptsExplicitEmptyStringAndOmittedOptionalParameter() throws Exception
    {
        ReplaceTools tools = new ReplaceTools();
        ToolExecutor executor = new ToolExecutor( tools );

        Object result = executor.call(
                "replaceString",
                Map.of( "oldString", "old", "newString", "" ) ).get();

        assertTrue( tools.invoked );
        assertEquals( "", tools.newString );
        assertEquals( "replaced", result );
    }
    @Test
    public void numberAndBooleanArgumentsReachTheToolAsStrings() throws Exception
    {
        ReplaceTools tools = new ReplaceTools();
        ToolExecutor executor = new ToolExecutor( tools );

        executor.call( "replaceString", Map.of( "oldString", "old", "newString", "new", "startLine", 120 ) ).get();
        assertEquals( "120", tools.startLine );

        executor.call( "replaceString", Map.of( "oldString", "old", "newString", "new", "startLine", 120.0 ) ).get();
        assertEquals( "120", tools.startLine, "a whole decimal is the same request as the integer" );

        executor.call( "replaceString", Map.of( "oldString", "old", "newString", "new", "startLine", "120" ) ).get();
        assertEquals( "120", tools.startLine, "the string form still works" );

        executor.call( "replaceString", Map.of( "oldString", "old", "newString", "new", "startLine", 9_999_999_999L ) ).get();
        assertEquals( "9999999999", tools.startLine );
    }

    /**
     * A misspelt type ("int", "bool") would be advertised to every client as is and
     * rejected by the schema validator, so the allowed set is checked here.
     */
    @Test
    public void everyToolParameterTypeIsOneTheFrameworkConverts()
    {
        java.util.List<String> offenders = new java.util.ArrayList<>();
        for ( Class<?> server : McpServerBuiltins.BUILT_IN_MCP_SERVERS )
        {
            for ( java.lang.reflect.Method method : server.getDeclaredMethods() )
            {
                for ( java.lang.reflect.Parameter parameter : method.getParameters() )
                {
                    ToolParam annotation = parameter.getAnnotation( ToolParam.class );
                    if ( annotation != null && !java.util.Set.of( "string", "integer", "boolean" ).contains( annotation.type() ) )
                    {
                        offenders.add( server.getSimpleName() + "." + method.getName() + "(" + annotation.type() + " "
                                + ToolExecutor.toParamName( parameter ) + ")" );
                    }
                }
            }
        }
        assertEquals( java.util.List.of(), offenders );
    }

    /**
     * The Java parameter of every tool is a String, whatever its schema type: the
     * framework converts a JSON number or boolean to its string form and the tool
     * parses it. A tool declaring an int or a Boolean parameter would bypass that and
     * fail on every call with an argument type mismatch - and no service-level test
     * notices, because those call the service directly. This catches it at build time.
     */
    @Test
    public void everyToolParameterIsAString()
    {
        java.util.List<String> offenders = new java.util.ArrayList<>();
        for ( Class<?> server : McpServerBuiltins.BUILT_IN_MCP_SERVERS )
        {
            for ( java.lang.reflect.Method method : server.getDeclaredMethods() )
            {
                if ( method.getAnnotation( com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool.class ) == null )
                {
                    continue;
                }
                for ( java.lang.reflect.Parameter parameter : method.getParameters() )
                {
                    if ( parameter.getType() != String.class )
                    {
                        offenders.add( server.getSimpleName() + "." + method.getName() + "("
                                + parameter.getType().getSimpleName() + " " + ToolExecutor.toParamName( parameter ) + ")" );
                    }
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals( java.util.List.of(), offenders,
                "tool parameters must be declared as String and parsed in the tool method" );
    }


    static final class ReplaceTools
    {
        private boolean invoked;
        private String newString;
        private String startLine;

        @Tool(name = "replaceString", description = "Test replacement tool", type = "object")
        public String replaceString(
                @ToolParam(name = "oldString", description = "Old text") String oldString,
                @ToolParam(name = "newString", description = "New text") String newString,
                @ToolParam(name = "startLine", type = "integer", description = "Optional start line", required = false) String startLine)
        {
            invoked = true;
            this.newString = newString;
            this.startLine = startLine;
            return "replaced";
        }
    }
}
