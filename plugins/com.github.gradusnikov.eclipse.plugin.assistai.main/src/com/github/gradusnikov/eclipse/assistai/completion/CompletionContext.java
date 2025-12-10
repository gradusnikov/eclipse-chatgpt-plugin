package com.github.gradusnikov.eclipse.assistai.completion;

/**
 * Holds context information for code completion at cursor position.
 */
public record CompletionContext(
    String fileName,
    String projectName,
    String fileExtension,
    String codeBeforeCursor,
    String codeAfterCursor,
    int cursorOffset,
    int cursorLine,
    int cursorColumn
) {
    
    /**
     * Returns the number of characters in the code before cursor.
     */
    public int prefixLength() {
        return codeBeforeCursor != null ? codeBeforeCursor.length() : 0;
    }
    
    /**
     * Returns the current line content (before cursor on that line).
     */
    public String currentLinePrefix() {
        if (codeBeforeCursor == null || codeBeforeCursor.isEmpty()) {
            return "";
        }
        int lastNewline = codeBeforeCursor.lastIndexOf('\n');
        return lastNewline >= 0 
            ? codeBeforeCursor.substring(lastNewline + 1) 
            : codeBeforeCursor;
    }
    
    /**
     * Checks if cursor is at the start of a line (only whitespace before).
     */
    public boolean isAtLineStart() {
        return currentLinePrefix().isBlank();
    }
}
