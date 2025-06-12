package com.github.gradusnikov.eclipse.assistai.prompt;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


/**
 * Test cases for the PromptParser class, focusing on LaTeX rendering functionality.
 */
public class MarkdownParserTest {

    @Test
    public void testInlineLatexRendering() {
        // Test inline LaTeX with $ syntax
        String input = "This is an inline equation $E=mc^2$ in a sentence.";
        MarkdownParser parser = new MarkdownParser(input);
        String result = parser.parseToHtml();
        
        // Verify the LaTeX is properly encoded in a span
        assertTrue(result.contains("<span class=\"inline-latex\">"));
        
        // Decode the Base64 content to verify it contains the original LaTeX
        String encodedContent = extractBase64Content(result, "inline-latex");
        String decodedContent = new String(Base64.getDecoder().decode(encodedContent));
        assertEquals("E=mc^2", decodedContent);
    }
    
    @Test
    public void testInlineLatexWithBackslashSyntax() {
        // Test inline LaTeX with \( \) syntax
        String input = "This is another inline equation \\(a^2 + b^2 = c^2\\) using backslash syntax.";
        MarkdownParser parser = new MarkdownParser(input);
        String result = parser.parseToHtml();
        
        assertTrue(result.contains("<span class=\"inline-latex\">"));
        
        String encodedContent = extractBase64Content(result, "inline-latex");
        String decodedContent = new String(Base64.getDecoder().decode(encodedContent));
        assertEquals("a^2 + b^2 = c^2", decodedContent);
    }
    
    @Test
    public void testSingleLineBlockLatex() {
        // Test single-line block LaTeX
        String input = "$$\\int_{a}^{b} f(x) dx$$";
        MarkdownParser parser = new MarkdownParser(input);
        String result = parser.parseToHtml();
        
        assertTrue(result.contains("<span class=\"block-latex\">"));
        
        String encodedContent = extractBase64Content(result, "block-latex");
        String decodedContent = new String(Base64.getDecoder().decode(encodedContent));
        assertEquals("\\int_{a}^{b} f(x) dx", decodedContent);
    }
    
    @Test
    public void testMultiLineBlockLatex() {
        // Test multi-line block LaTeX
        String input = "$$\n\\begin{align}\na &= b + c \\\\\n&= d + e\n\\end{align}\n$$";
        MarkdownParser parser = new MarkdownParser(input);
        String result = parser.parseToHtml();
        
        assertTrue(result.contains("<span class=\"block-latex\">"));
        
        String encodedContent = extractBase64Content(result, "block-latex");
        String decodedContent = new String(Base64.getDecoder().decode(encodedContent));
        assertTrue(decodedContent.contains("\\begin{align}"));
        assertTrue(decodedContent.contains("a &= b + c \\\\"));
    }
    
    @Test
    public void testLatexWithBackslashBracketSyntax() {
        // Test LaTeX with \[ \] syntax
        String input = "\\[\n\\frac{d}{dx}\\left( \\int_{0}^{x} f(u)\\,du\\right)=f(x)\n\\]";
        MarkdownParser parser = new MarkdownParser(input);
        String result = parser.parseToHtml();
        
        assertTrue(result.contains("<span class=\"block-latex\">"));
        
        String encodedContent = extractBase64Content(result, "block-latex");
        String decodedContent = new String(Base64.getDecoder().decode(encodedContent));
        assertTrue(decodedContent.contains("\\frac{d}{dx}"));
    }
    
    @Test
    public void testMixedContent() {
        // Test mix of markdown, code blocks and LaTeX
        String input = "# Test Document\n\n" +
                       "Here's an **equation**: $E=mc^2$\n\n" +
                       "```java\npublic class Test {}\n```\n\n" +
                       "And a block equation:\n" +
                       "$$\n\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}\n$$\n\n" +
                       "End of document.";
        
        MarkdownParser parser = new MarkdownParser(input);
        String result = parser.parseToHtml();
        
        // Verify markdown was processed
        assertTrue(result.contains("<h1>Test Document</h1>"));
        assertTrue(result.contains("<strong>equation</strong>"));
        
        // Verify code block was processed
        assertTrue(result.contains("<code lang=\"java\""));
        assertTrue(result.contains("public class Test {}"));
        
        // Verify LaTeX was processed
        assertTrue(result.contains("<span class=\"inline-latex\">"));
        assertTrue(result.contains("<span class=\"block-latex\">"));
        
        // Check LaTeX content
        String inlineLatex = extractBase64Content(result, "inline-latex");
        assertEquals("E=mc^2", new String(Base64.getDecoder().decode(inlineLatex)));
        
        String blockLatex = extractBase64Content(result, "block-latex");
        String decodedBlockLatex = new String(Base64.getDecoder().decode(blockLatex));
        assertTrue(decodedBlockLatex.contains("\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}"));
    }
    
    @Test
    public void testLatexWithSpecialCharacters() {
        // Test LaTeX with special HTML characters that need escaping
        String input = "This has special chars $x < y & z > w$";
        MarkdownParser parser = new MarkdownParser(input);
        String result = parser.parseToHtml();
        // But the LaTeX should preserve the original
        String encodedContent = extractBase64Content(result, "inline-latex");
        String decodedContent = new String(Base64.getDecoder().decode(encodedContent));
        assertEquals("x < y & z > w", decodedContent);
    }
    
    /**
     * Helper method to extract Base64 encoded content from HTML spans
     */
    private String extractBase64Content(String html, String spanClass) {
        Pattern pattern = Pattern.compile("<span class=\"" + spanClass + "\">(.*?)</span>");
        java.util.regex.Matcher matcher = pattern.matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }
    @Test
    public void testCodeBlock()
    {
        String prompt = """
            ```java
            package com.example.app;
            /**
 * Hello world!
 */
            public class App
            {
    public static void main( String[] args )
    {
        System.out.println( "H3ll0 W0rld!" );
    }
            }
            ```				
				""";
        
        MarkdownParser parser = new MarkdownParser(prompt);
        System.out.println(parser.parseToHtml());
        
    }
    
    @Test
    public void testCodeText()
    {
        String prompt = """
                Hi!
                """;
        
        MarkdownParser parser = new MarkdownParser(prompt);
        System.out.println(parser.parseToHtml());
        
    }
    

    
    @Test
    void testTableInCodeBlockRendering()
    {
        String content = """
```markdown                
| Tables   |      Are      |  Cool |
|----------|:-------------:|------:|
| col 1 is |  left-aligned | $1600 |
| col 2 is |    centered   |   $12 |
| col 3 is | right-aligned |    $1 |
```
""";
        MarkdownParser parser = new MarkdownParser( content );
        var out = parser.parseToHtml();
        System.out.println( out );
    }

    
    @Test
    void testTableRendering()
    {
        String content = """
| Tables   |      Are      |  Cool |
|----------|:-------------:|------:|
| col 1 is |  left-aligned | $1600 |
| col 2 is |    centered   |   $12 |
| col 3 is | right-aligned |    $1 |""";
        
        String expected = """
<table class="markdown-table">
<thead>
<tr>
<th style="text-align: left;"> Tables   </th>
<th style="text-align: center;">      Are      </th>
<th style="text-align: right;">  Cool </th>
</tr>
</thead>
<tbody>
<tr>
<td style="text-align: left;"> col 1 is </td>
<td style="text-align: center;">  left-aligned </td>
<td style="text-align: right;"> $1600 </td>
</tr>
<tr>
<td style="text-align: left;"> col 2 is </td>
<td style="text-align: center;">    centered   </td>
<td style="text-align: right;">   $12 </td>
</tr>
<tr>
<td style="text-align: left;"> col 3 is </td>
<td style="text-align: center;"> right-aligned </td>
<td style="text-align: right;">    $1 </td>
</tr>
</tbody>
</table>                
                """;
        
        MarkdownParser parser = new MarkdownParser( content );
        var out = parser.parseToHtml();
        Assertions.assertTrue( out.contains( expected ) );
    }
        
}



	
