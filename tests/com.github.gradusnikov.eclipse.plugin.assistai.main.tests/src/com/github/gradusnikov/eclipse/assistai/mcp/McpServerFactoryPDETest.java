package com.github.gradusnikov.eclipse.assistai.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.mcp.operations.OperationRegistry;

public class McpServerFactoryPDETest
{
    @Test
    public void operationToolsUseAnnotationDrivenDiscoveryAndExecution() throws Exception
    {
        OperationRegistry registry = new OperationRegistry();
        McpServerFactory.OperationTools tools = new McpServerFactory.OperationTools( registry );
        McpServerFactory factory = new McpServerFactory( null, registry );

        assertEquals(
                Set.of( "getOperationStatus", "listOperations", "cancelOperation" ),
                Set.copyOf( factory.listToolNames( tools ) ) );

        ToolExecutor executor = new ToolExecutor( tools );
        String listResult = (String) executor.call( "listOperations", Map.of() ).get();
        assertEquals( "No operations have been started.", listResult );

        String statusResult = (String) executor.call(
                "getOperationStatus",
                Map.of(
                        "operationId", "op-does-not-exist",
                        "outputOffset", "-100",
                        "outputLimit", "50",
                        "waitSeconds", "0" ) ).get();
        assertTrue( statusResult.contains( "no operation with id 'op-does-not-exist'" ) );
    }
}
