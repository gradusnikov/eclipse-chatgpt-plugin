package com.github.gradusnikov.eclipse.assistai.prompt;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PromptParserTest {


    @Test
    void parseToHtml_regularString() {
        PromptParser parser = new PromptParser("Hello, World!");
        String result = parser.parseToHtml();
        assertThat(result).isEqualTo("Hello, World!");
    }

    @Test
    void parseToHtml_codeBlock() {
        String prompt = """
                ```java
                // Comment
                System.out.println( "Hello!\\n <a href=\\"" + "test" + "\\">" );
                ```
                This is a "code snippet".
                """.trim();
        PromptParser  parser = new PromptParser(prompt);
        String result = parser.parseToHtml();
        
        assertThat(result).isEqualTo(
                """
                <pre><code lang="java">
                // Comment
                System.out.println( &quot;Hello!\\\\n &lt;a href=\\\\&quot;&quot; + &quot;test&quot; + &quot;\\\\&quot;&gt;&quot; );
                </code></pre>
                This is a &quot;code snippet&quot;.
                """.trim() );
    }
    @ParameterizedTest
    @CsvSource({
            "'**text**', '<strong>text</strong>'",
            "'*text*', '<em>text</em>'",
            "'`text`', '<i>text</i>'",
            "'[text](url)', '<a href=\"url\">text</a>'"
    })
    void testMarkdown(String input, String expectedOutput) 
    {
        assertThat(PromptParser.markdown(input)).isEqualTo(expectedOutput);
    }
    
    
    @ParameterizedTest
    @CsvSource({
            "'', ''",
            "'Hello', 'Hello<br/>'",
            "'Hello, World!', 'Hello, World!<br/>'",
            "'*Hello*', '<em>Hello</em><br/>'",
            "'**Hello**', '<strong>Hello</strong><br/>'",
            "'`Hello`', '<i>Hello</i><br/>'",
            "'[Hello](https://example.com)', '<a href=\"https://example.com\">Hello</a><br/>'"
    })
    void testParseToHtml(String input, String expected) {
        PromptParser parser = new PromptParser(input);
        String result = parser.parseToHtml();
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void testParseToHtmlWithCodeBlock() {
        String input = "```\nHello\n```";
        String expected = """
                <input type="button" onClick="eclipseCopyCode(document.getElementById('UUID_PLACEHOLDER').innerText)" value="Copy Code" />
                <input type="hidden" onClick="eclipseApplyPatch(document.getElementById('UUID_PLACEHOLDER').innerText)" value="ApplyPatch"/>
                <pre><code lang="" id="UUID_PLACEHOLDER">
                Hello</code></pre>
                """;

        PromptParser parser = new PromptParser(input);
        String result = parser.parseToHtml();

        // Extract UUID from result
        String uuidPattern = "id=\"([^\"]+)\"";
        String uuid = result.replaceAll(uuidPattern, "$1");

        // Replace UUID_PLACEHOLDER in the expected string with the extracted UUID
        expected = expected.replace("UUID_PLACEHOLDER", uuid);

        assertThat(result).isEqualTo(expected);
    }
    
}