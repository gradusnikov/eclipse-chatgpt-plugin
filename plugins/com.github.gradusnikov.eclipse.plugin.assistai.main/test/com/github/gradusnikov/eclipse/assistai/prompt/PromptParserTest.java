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
    
}