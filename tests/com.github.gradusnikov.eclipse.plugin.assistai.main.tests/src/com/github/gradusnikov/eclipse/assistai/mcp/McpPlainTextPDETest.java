package com.github.gradusnikov.eclipse.assistai.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class McpPlainTextPDETest
{
    @Test
    public void rendersCompleteProjectDataWithoutJsonSyntax()
    {
        Map<String, Object> project = new LinkedHashMap<>();
        project.put( "projectName", "HelloMcp" );
        project.put( "open", true );
        Map<String, Object> payload = Map.of(
                "totalProjects", 1,
                "projects", List.of( project ),
                "summaryText", "1 project, 1 open." );

        String text = McpPlainText.render( payload );

        assertTrue( text.startsWith( "1 project, 1 open.\n\n" ), text );
        assertTrue( text.contains( "totalProjects: 1" ), text );
        assertTrue( text.contains( "projects:\n  - projectName: HelloMcp" ), text );
        assertTrue( text.contains( "open: true" ), text );
        assertFalse( text.contains( "\n  -\n" ), text );
        assertFalse( text.contains( "\"totalProjects\"" ), text );
        assertFalse( text.contains( "{" ), text );
    }

    @Test
    public void preservesMultilineContentInAFencedBlock()
    {
        String text = McpPlainText.render( Map.of( "content", "first\nsecond\n" ) );

        assertTrue( text.contains( "content:\n  ```\nfirst\nsecond\n  ```" ), text );
        assertFalse( text.contains( "\\n" ), text );
    }
}
