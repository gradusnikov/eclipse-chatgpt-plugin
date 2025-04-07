
package com.github.gradusnikov.eclipse.assistai.prompt;

import java.util.EnumSet;
import java.util.Scanner;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.commons.text.StringEscapeUtils;

/**
 * A utility class for parsing and converting a text prompt to an HTML formatted string.
 */
public class PromptParser {
    
    /**
     * Enum representing the different parsing states
     */
    private enum ParserState {
        CODE_BLOCK, FUNCTION_CALL, TEXT_ATTACHMENT
    }
    
    private static final String TATT_CONTEXTSTART = "<|ContextStart|>";
    private static final String TATT_FILEPREFIX = "File: ";
    private static final String TATT_LINESPREFIX = "Lines: ";
    private static final String TATT_CONTENTSTART = "<|ContentStart|>";
    private static final String TATT_CONTENTEND = "<|ContentEnd|>";
    private static final String TATT_CONTEXTEND = "<|ContextEnd|>";

    // Using EnumSet for clearer state management
    private EnumSet<ParserState> state = EnumSet.noneOf(ParserState.class);
    
    private final String prompt;
    
    public PromptParser(String prompt) {
        this.prompt = prompt;
    }
    
    /**
     * Converts the prompt text to an HTML formatted string.
     *
     * @return An HTML formatted string representation of the prompt text.
     */
    public String parseToHtml() {
        var out = new StringBuilder();
        
        try (var scanner = new Scanner(prompt)) {
            scanner.useDelimiter("\n");
            // Improved regex patterns
            var codeBlockPattern = Pattern.compile("^\\s*```([a-zA-Z0-9]*)\\s*$");
            var functionCallPattern = Pattern.compile("^\"function_call\".*");
            
            while (scanner.hasNext()) {
                var line = scanner.next();
                var codeBlockMatcher = codeBlockPattern.matcher(line);
                var functionBlockMatcher = functionCallPattern.matcher(line);
                
                if (codeBlockMatcher.find()) {
                    var lang = codeBlockMatcher.group(1);
                    handleCodeBlock(out, lang);
                } else if (functionBlockMatcher.find()) {
                    handleFunctionCall(out, line);
                } else if (line.startsWith(TATT_CONTEXTSTART)) {
                    handleTextAttachmentStart(out, line);
                } else {
                    handleContent(out, line, !scanner.hasNext());
                }
            }
        } catch (Exception e) {
            // Add error handling
            out.append("<div class=\"error\">Error parsing content: ").append(e.getMessage()).append("</div>");
        }
        return out.toString();
    }

    private void handleTextAttachmentStart(StringBuilder out, String line) {
        if (!state.contains(ParserState.TEXT_ATTACHMENT)) {
            out.append("""
                    <div class="function-call">
                    <details><summary>""");
            state.add(ParserState.TEXT_ATTACHMENT);
        }
    }

    private void handleFunctionCall(StringBuilder out, String line) {
        if (!state.contains(ParserState.FUNCTION_CALL)) {
            out.append("""
                    <div class="function-call">
                    <details><summary>Function call</summary>
                    <pre>""").append(line);
            state.add(ParserState.FUNCTION_CALL);
        }
    }

    private void handleContent(StringBuilder out, String line, boolean lastLine) {
        if (state.contains(ParserState.CODE_BLOCK)) {
            out.append(StringEscapeUtils.escapeHtml4(escapeBackSlashes(line)));
        } else if (state.contains(ParserState.TEXT_ATTACHMENT)) {
            handleTextAttachmentLine(out, line);
            return;
        } else {
            out.append(markdown(StringEscapeUtils.escapeHtml4(line)));
        }
        
        if (lastLine) {
            // Close any open blocks on the last line
            if (state.contains(ParserState.CODE_BLOCK)) {
                out.append("</code></pre>\n");
                state.remove(ParserState.CODE_BLOCK);
            } else if (state.contains(ParserState.FUNCTION_CALL)) {
                out.append("</pre></details></div>\n");
                state.remove(ParserState.FUNCTION_CALL);
            }
        } else if (state.contains(ParserState.CODE_BLOCK)) {
            out.append("\n");
        } else {
            out.append("<br/>");
        }
    }

    private void handleTextAttachmentLine(StringBuilder out, String line) {
        if (line.startsWith(TATT_FILEPREFIX)) {
            out.append("Context: ").append(line.substring(TATT_FILEPREFIX.length())).append(", ");
        } else if (line.startsWith(TATT_LINESPREFIX)) {
            out.append(line).append("</summary>");
        } else if (line.startsWith(TATT_CONTENTSTART)) {
            out.append("<pre>");
        } else if (line.startsWith(TATT_CONTENTEND)) {
            out.append("</pre>");
        } else if (line.startsWith(TATT_CONTEXTEND)) {
            out.append("</details></div>\n");
            state.remove(ParserState.TEXT_ATTACHMENT);
        } else {
            out.append(StringEscapeUtils.escapeHtml4(line)).append("<br/>");
        }
    }

    private void handleCodeBlock(StringBuilder out, String lang) {
        if (!state.contains(ParserState.CODE_BLOCK)) {
            String codeBlockId = UUID.randomUUID().toString();
            String blockClass = "diff".equals(lang) ? "diff-block" : "code-block";
            
            // Removed newline after <pre><code> tag to fix the extra line issue
            out.append("""
                    <div class="codeBlock %s">
                    <div class="codeBlockButtons"> 
                    <input type="button" onClick="eclipseCopyCode(document.getElementById('%s').innerText)" value="Copy" />
                    <input class="code-only" type="button" onClick="eclipseInsertCode(document.getElementById('%s').innerText)" value="Insert" />
                    <input class="code-only" type="button" onClick="eclipseNewFile(document.getElementById('%s').innerText, '%s')" value="New File" />
                    <input class="code-only" type="button" onClick="eclipseDiffCode(document.getElementById('%s').innerText)" value="Diff" />
                    <input class="diff-only" type="button" onClick="eclipseApplyPatch(document.getElementById('%s').innerText)" value="Apply"/>
                    </div>
                    <pre><code lang="%s" id="%s">"""
                    .formatted(
                        blockClass, 
                        codeBlockId, 
                        codeBlockId, 
                        codeBlockId, 
                        lang, 
                        codeBlockId, 
                        codeBlockId, 
                        lang, 
                        codeBlockId
                    ));
            state.add(ParserState.CODE_BLOCK);
        } else {
            out.append("</code></pre></div>\n");
            state.remove(ParserState.CODE_BLOCK);
        }
    }
    
    /**
     * Escapes backslashes in the input string to prevent issues in HTML rendering.
     * 
     * @param input The input string
     * @return The input string with backslashes escaped
     */
    public static String escapeBackSlashes(String input) {
        return input.replace("\\", "\\\\");
    }
    
    /**
     * Converts markdown syntax to HTML.
     * 
     * @param input The input string containing markdown
     * @return The HTML representation of the markdown
     */
    public static String markdown(String input) {
        // Headers
        input = input.replaceAll("^# (.*?)$", "<h1>$1</h1>");
        input = input.replaceAll("^## (.*?)$", "<h2>$1</h2>");
        input = input.replaceAll("^### (.*?)$", "<h3>$1</h3>");
        input = input.replaceAll("^#### (.*?)$", "<h4>$1</h4>");
        input = input.replaceAll("^##### (.*?)$", "<h5>$1</h5>");
        input = input.replaceAll("^###### (.*?)$", "<h6>$1</h6>");
        
        // Bold and italic
        input = input.replaceAll("\\*\\*(.*?)\\*\\*", "<strong>$1</strong>");
        input = input.replaceAll("\\*(.*?)\\*", "<em>$1</em>");
        
        // Strikethrough (added)
        input = input.replaceAll("~~(.*?)~~", "<del>$1</del>");
        
        // Inline code
        input = input.replaceAll("`(.*?)`", "<code>$1</code>");
        
        // Images
        input = input.replaceAll("!\\[(.*?)\\]\\((.*?)\\)", "<img src=\"$2\" alt=\"$1\" />");
        
        // Links
        input = input.replaceAll("\\[(.*?)\\]\\((.*?)\\)", "<a href=\"$2\" target=\"_blank\">$1</a>");
        
        // Blockquotes
        input = input.replaceAll("^> (.*?)$", "<blockquote>$1</blockquote>");
        
        // Unordered lists
        input = input.replaceAll("^\\* (.*?)$", "<li>$1</li>");
        input = input.replaceAll("^- (.*?)$", "<li>$1</li>");
        input = input.replaceAll("^\\+ (.*?)$", "<li>$1</li>");
        
        // Task lists (added)
        input = input.replaceAll("^- \\[ \\] (.*?)$", "<li><input type=\"checkbox\" disabled> $1</li>");
        input = input.replaceAll("^- \\[x\\] (.*?)$", "<li><input type=\"checkbox\" checked disabled> $1</li>");
        
        // Horizontal Rule
        input = input.replaceAll("^(\\*\\*\\*|---)$", "<hr>");
        
        return input;
    }
}
