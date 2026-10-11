package com.github.gradusnikov.eclipse.assistai.mcp.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.mcp.McpJson;
import com.github.gradusnikov.eclipse.assistai.mcp.McpPlainText;
import com.github.gradusnikov.eclipse.assistai.mcp.StructuredToolResult;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceVersion;

/**
 * A long-running tool's structured result must survive being collected through
 * getOperationStatus.
 * <p>
 * That path reports the result via {@link Operation#getResultText()}, which used to be
 * {@code result.toString()} unconditionally - so a record returned by a slow tool
 * would reach the caller as {@code EditResult[status=...]} rather than the formatter's
 * output. Lives in this package because it exercises Operation's internals directly.
 */
public class OperationStructuredResultPDETest
{
    private static final String TEXT = "Applied 2 edits in file 'Service.java'.";

    private static EditResult anEditResult()
    {
        return EditResult.versionConflict( "Demo", "src/Service.java", ResourceVersion.UNKNOWN, 99L );
    }

    /**
     * Finishes an operation the way the framework does - through its future - so the
     * test exercises the real completion path rather than a back door.
     */
    private static Operation completedWith( String id, String toolName, Object result )
    {
        Operation operation = new Operation( id, toolName, "" );
        operation.setFuture( java.util.concurrent.CompletableFuture.completedFuture( result ) );
        return operation;
    }

    @Test
    public void reportsTheFormattedTextRatherThanTheRecord()
    {
        Operation operation = completedWith( "op-1", "applyTextEdits",
                StructuredToolResult.of( anEditResult(), TEXT ) );

        assertEquals( TEXT, operation.getResultText() );
    }

    @Test
    public void keepsThePayloadForStructuredCollection()
    {
        Operation operation = completedWith( "op-2", "applyTextEdits",
                StructuredToolResult.of( anEditResult(), TEXT ) );

        assertNotNull( operation.getStructuredResult() );
        assertTrue( operation.getStructuredResult() instanceof EditResult );
    }

    @Test
    public void stillReportsPlainTextResultsUnchanged()
    {
        Operation operation = completedWith( "op-3", "runJUnitTests", "Test Run: 7 passed" );

        assertEquals( "Test Run: 7 passed", operation.getResultText() );
        assertNull( operation.getStructuredResult(),
                "a tool returning prose has no payload to expose" );
    }

    @Test
    public void reportsAnEmptyTextForNoResult()
    {
        Operation operation = new Operation( "op-4", "cancelOperation", "" );

        assertEquals( "", operation.getResultText() );
        assertNull( operation.getStructuredResult() );
    }

    @Test
    public void aRecordIsItsOwnPayload()
    {
        // Every other tool returns its record bare. A long-running one that did the same
        // used to reach a polling caller with no structured content at all and
        // record.toString() as its text, so the same run read differently depending on
        // whether it was collected inline or through getOperationStatus.
        EditResult edit = anEditResult();
        Operation operation = completedWith( "op-5", "runJUnitTests", edit );

        assertSame( edit, operation.getStructuredResult(),
                "a record needs no wrapper to be carried forward" );
        assertEquals( McpPlainText.render( McpJson.toMap( edit ) ), operation.getResultText(),
                "the text is the same rendering the immediate path emits" );
    }
}
