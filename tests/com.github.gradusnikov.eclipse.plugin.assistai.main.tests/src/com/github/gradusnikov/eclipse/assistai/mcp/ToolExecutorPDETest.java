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

    static final class ReplaceTools
    {
        private boolean invoked;
        private String newString;

        @Tool(name = "replaceString", description = "Test replacement tool", type = "object")
        public String replaceString(
                @ToolParam(name = "oldString", description = "Old text") String oldString,
                @ToolParam(name = "newString", description = "New text") String newString,
                @ToolParam(name = "startLine", description = "Optional start line", required = false) String startLine)
        {
            invoked = true;
            this.newString = newString;
            return "replaced";
        }
    }
}
