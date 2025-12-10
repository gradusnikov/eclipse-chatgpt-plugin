package com.github.gradusnikov.eclipse.assistai.completion;

import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.events.VerifyListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.FontMetrics;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;

/**
 * Manages ghost text (inline completion preview) in the editor. Shows
 * grayed-out text that can be accepted with Tab or dismissed with Escape.
 * 
 * The ghost text is rendered using a combination of:
 * 1. Vertical line indent to push down existing content (for multi-line ghost text)
 * 2. Paint listener to render the ghost text overlay
 * 
 * This approach makes the ghost text appear "injected" into the document,
 * pushing existing code down rather than overlaying on top.
 */
public class GhostTextManager
{

    private final ITextViewer textViewer;

    private final StyledText  styledText;

    private String            ghostText;

    private int               ghostOffset;

    private boolean           isShowing;
    
    private boolean           isPainterInstalled;
    
    // Track which line has vertical indent applied
    private int               indentedLineIndex = -1;
    private int               appliedIndent = 0;

    // Listeners
    private KeyListener       keyListener;

    private VerifyListener    verifyListener;
    
    private PaintListener     ghostTextPainter;

    // Ghost text styling
    private Color             ghostColor;

    private Font              italicFont;

    // Callback when completion is accepted
    private Runnable          onAccept;

    private Runnable          onDismiss;

    public GhostTextManager( ITextViewer textViewer )
    {
        this.textViewer = textViewer;
        this.styledText = textViewer.getTextWidget();
        this.isShowing = false;
        this.isPainterInstalled = false;

        initializeStyles();
        initializePainter();
        installListeners();
    }

    /**
     * Initializes colors and fonts for ghost text.
     */
    private void initializeStyles()
    {
        Display display = styledText.getDisplay();

        // Gray color for ghost text
        ghostColor = new Color( display, 128, 128, 128 );

        // Italic font for ghost text
        Font currentFont = styledText.getFont();
        FontData[] fontData = currentFont.getFontData();
        for ( FontData fd : fontData )
        {
            fd.setStyle( fd.getStyle() | SWT.ITALIC );
        }
        italicFont = new Font( display, fontData );
    }
    
    /**
     * Initializes the paint listener that renders ghost text.
     */
    private void initializePainter()
    {
        ghostTextPainter = new PaintListener()
        {
            @Override
            public void paintControl( PaintEvent event )
            {
                paintGhostText( event );
            }
        };
    }

    /**
     * Installs keyboard and verify listeners.
     */
    private void installListeners()
    {
        keyListener = new KeyListener()
        {
            @Override
            public void keyPressed( KeyEvent e )
            {
                if ( !isShowing )
                    return;

                if ( e.keyCode == SWT.TAB )
                {
                    // Accept completion
                    e.doit = false;
                    acceptCompletion();
                }
                else if ( e.keyCode == SWT.ESC )
                {
                    // Dismiss completion
                    e.doit = false;
                    dismissCompletion();
                }
                else if ( e.keyCode == SWT.ARROW_RIGHT && ( e.stateMask & SWT.CTRL ) != 0 )
                {
                    // Ctrl+Right: Accept word
                    e.doit = false;
                    acceptNextWord();
                }
            }

            @Override
            public void keyReleased( KeyEvent e )
            {
                // Not used
            }
        };

        verifyListener = new VerifyListener()
        {
            @Override
            public void verifyText( VerifyEvent e )
            {
                if ( isShowing )
                {
                    // Any typing dismisses ghost text
                    if ( e.text.length() > 0 || e.start != e.end )
                    {
                        Display.getCurrent().asyncExec( () -> dismissCompletion() );
                    }
                }
            }
        };

        styledText.addKeyListener( keyListener );
        styledText.addVerifyListener( verifyListener );
    }

    /**
     * Shows ghost text at the specified offset.
     */
    public void showGhostText( String text, int offset )
    {
        if ( text == null || text.isEmpty() )
        {
            dismissCompletion();
            return;
        }

        Display.getDefault().asyncExec( () -> {
            if ( styledText == null || styledText.isDisposed() )
            {
                return;
            }
            
            try
            {
                // Dismiss any existing ghost text display first
                if ( isShowing )
                {
                    removeGhostTextDisplay();
                }

                this.ghostText = text;
                this.ghostOffset = offset;
                this.isShowing = true;

                displayGhostText();

            }
            catch ( Exception e )
            {
                // Ignore errors - widget may be disposed
            }
        } );
    }

    /**
     * Updates existing ghost text (for streaming).
     */
    public void updateGhostText( String text )
    {
        if ( text == null || text.isEmpty() )
        {
            dismissCompletion();
            return;
        }

        Display.getDefault().asyncExec( () -> {
            if ( styledText == null || styledText.isDisposed() )
            {
                return;
            }
            
            try
            {
                String oldGhostText = this.ghostText;
                this.ghostText = text;
                
                // Keep the same offset - just update the text
                if ( !isShowing )
                {
                    this.ghostOffset = styledText.getCaretOffset();
                    this.isShowing = true;
                    displayGhostText();
                }
                else
                {
                    // Update vertical indent if number of lines changed
                    int oldLineCount = oldGhostText != null ? countLines(oldGhostText) : 0;
                    int newLineCount = countLines(text);
                    if (oldLineCount != newLineCount)
                    {
                        updateVerticalIndent();
                    }
                    // Just redraw to show updated text
                    styledText.redraw();
                }
            }
            catch ( Exception e )
            {
                // Ignore errors
            }
        } );
    }

    /**
     * Appends text to existing ghost text (for streaming).
     */
    public void appendGhostText( String additionalText )
    {
        if ( additionalText == null || additionalText.isEmpty() )
        {
            return;
        }

        Display.getDefault().asyncExec( () -> {
            if ( styledText == null || styledText.isDisposed() )
            {
                return;
            }
            
            try
            {
                if ( !isShowing )
                {
                    // First chunk - show as new ghost text
                    int offset = styledText.getCaretOffset();
                    showGhostText( additionalText, offset );
                }
                else
                {
                    // Append to existing
                    String oldGhostText = this.ghostText;
                    this.ghostText = this.ghostText + additionalText;
                    
                    // Update vertical indent if number of lines changed
                    int oldLineCount = countLines(oldGhostText);
                    int newLineCount = countLines(this.ghostText);
                    if (oldLineCount != newLineCount)
                    {
                        updateVerticalIndent();
                    }
                    styledText.redraw();
                }
            }
            catch ( Exception e )
            {
                // Ignore errors
            }
        } );
    }
    
    /**
     * Counts the number of lines in the given text.
     */
    private int countLines(String text)
    {
        if (text == null || text.isEmpty())
        {
            return 0;
        }
        int count = 1;
        for (int i = 0; i < text.length(); i++)
        {
            if (text.charAt(i) == '\n')
            {
                count++;
            }
        }
        return count;
    }
    
    /**
     * Calculates and applies vertical indent to push down content after ghost text.
     */
    private void updateVerticalIndent()
    {
        if (ghostText == null || ghostText.isEmpty() || styledText.isDisposed())
        {
            return;
        }
        
        try
        {
            // Remove any existing indent first
            removeVerticalIndent();
            
            // Count extra lines in ghost text (lines after the first one)
            int extraLines = countLines(ghostText) - 1;
            if (extraLines <= 0)
            {
                return; // No extra lines, no indent needed
            }
            
            // Get the line index where ghost text starts
            int textLength = styledText.getCharCount();
            int effectiveOffset = Math.min(ghostOffset, textLength);
            int currentLine = styledText.getLineAtOffset(effectiveOffset);
            
            // Apply indent to the NEXT line to push it down
            int nextLine = currentLine + 1;
            if (nextLine < styledText.getLineCount())
            {
                // Calculate indent height based on line height
                GC gc = new GC(styledText);
                gc.setFont(styledText.getFont());
                FontMetrics fm = gc.getFontMetrics();
                int lineHeight = fm.getHeight();
                gc.dispose();
                
                int indent = extraLines * lineHeight;
                styledText.setLineVerticalIndent(nextLine, indent);
                indentedLineIndex = nextLine;
                appliedIndent = indent;
            }
        }
        catch (Exception e)
        {
            // Ignore - line may not exist
        }
    }
    
    /**
     * Removes any applied vertical indent.
     */
    private void removeVerticalIndent()
    {
        if (indentedLineIndex >= 0 && styledText != null && !styledText.isDisposed())
        {
            try
            {
                if (indentedLineIndex < styledText.getLineCount())
                {
                    styledText.setLineVerticalIndent(indentedLineIndex, 0);
                }
            }
            catch (Exception e)
            {
                // Ignore
            }
            indentedLineIndex = -1;
            appliedIndent = 0;
        }
    }

    /**
     * Displays the ghost text in the editor by installing the paint listener.
     */
    private void displayGhostText()
    {
        if ( ghostText == null || ghostText.isEmpty() || styledText.isDisposed() )
        {
            return;
        }

        try
        {
            // Apply vertical indent for multi-line ghost text
            updateVerticalIndent();
            
            if ( !isPainterInstalled )
            {
                styledText.addPaintListener( ghostTextPainter );
                isPainterInstalled = true;
            }
            styledText.redraw();
        }
        catch ( Exception e )
        {
            // Ignore
        }
    }

    /**
     * Paints ghost text as an overlay at the ghost offset position.
     */
    private void paintGhostText( PaintEvent event )
    {
        if ( !isShowing || ghostText == null || ghostText.isEmpty() )
        {
            return;
        }

        StyledText st = (StyledText) event.widget;
        if ( st.isDisposed() )
        {
            return;
        }

        try
        {
            GC gc = event.gc;
            
            // Save original settings
            Color originalForeground = gc.getForeground();
            Font originalFont = gc.getFont();
            int originalAlpha = gc.getAlpha();

            // Validate ghost offset is still valid
            int textLength = st.getCharCount();
            int effectiveOffset = Math.min( ghostOffset, textLength );
            
            // Get location at the ghost offset
            Point ghostLocation;
            try
            {
                ghostLocation = st.getLocationAtOffset( effectiveOffset );
            }
            catch ( IllegalArgumentException e )
            {
                // Offset no longer valid
                return;
            }

            // Set ghost text style
            gc.setForeground( ghostColor );
            gc.setFont( italicFont );
            gc.setAlpha( 180 );

            // Get font metrics for line height calculation
            FontMetrics fm = gc.getFontMetrics();
            int lineHeight = fm.getHeight();

            // Handle multi-line ghost text
            String[] lines = ghostText.split( "\n", -1 );
            int y = ghostLocation.y;
            int x = ghostLocation.x;

            for ( int i = 0; i < lines.length; i++ )
            {
                String line = lines[i];
                
                // Check if line is visible in viewport
                if ( y + lineHeight < 0 )
                {
                    // Line is above viewport
                    y += lineHeight;
                    continue;
                }
                if ( y > st.getClientArea().height )
                {
                    // Line is below viewport
                    break;
                }
                
                if ( i == 0 )
                {
                    // First line continues from ghost position
                    gc.drawString( line, x, y, true );
                }
                else
                {
                    // Subsequent lines start at the beginning + indent
                    y += lineHeight;
                    
                    // Try to match indentation of the line where ghost text starts
                    try
                    {
                        int ghostLine = st.getLineAtOffset( effectiveOffset );
                        int lineStartOffset = st.getOffsetAtLine( ghostLine );
                        String currentLineText = st.getLine( ghostLine );
                        
                        // Calculate indent (spaces/tabs at beginning)
                        int indentChars = 0;
                        for ( char c : currentLineText.toCharArray() )
                        {
                            if ( c == ' ' || c == '\t' )
                            {
                                indentChars++;
                            }
                            else
                            {
                                break;
                            }
                        }
                        
                        // Get x position at the start of the line
                        Point lineStartLoc = st.getLocationAtOffset( lineStartOffset );
                        x = lineStartLoc.x;
                        
                        // Add indent if the ghost line has leading whitespace
                        if (line.length() > 0)
                        {
                            int ghostIndent = 0;
                            for (char c : line.toCharArray())
                            {
                                if (c == ' ' || c == '\t')
                                {
                                    ghostIndent++;
                                }
                                else
                                {
                                    break;
                                }
                            }
                            String ghostIndentStr = line.substring(0, ghostIndent);
                            x += gc.textExtent(ghostIndentStr).x;
                            line = line.substring(ghostIndent);
                        }
                    }
                    catch ( Exception e )
                    {
                        // Fallback: use left margin
                        x = st.getLeftMargin();
                    }
                    
                    gc.drawString( line, x, y, true );
                }
            }

            // Restore original settings
            gc.setForeground( originalForeground );
            gc.setFont( originalFont );
            gc.setAlpha( originalAlpha );
        }
        catch ( Exception e )
        {
            // Ignore paint errors - widget state may have changed
        }
    }

    /**
     * Removes ghost text display by removing the paint listener and vertical indent.
     */
    private void removeGhostTextDisplay()
    {
        // Remove vertical indent first
        removeVerticalIndent();
        
        if ( styledText != null && !styledText.isDisposed() && isPainterInstalled )
        {
            styledText.removePaintListener( ghostTextPainter );
            isPainterInstalled = false;
            styledText.redraw();
        }
    }

    /**
     * Accepts the full completion.
     */
    public void acceptCompletion()
    {
        if ( !isShowing || ghostText == null )
        {
            return;
        }

        String textToInsert = ghostText;
        int insertOffset = ghostOffset;
        
        // Clear state first to prevent re-entrancy issues
        removeGhostTextDisplay();
        this.ghostText = null;
        this.isShowing = false;

        // Insert the text
        try
        {
            if ( styledText != null && !styledText.isDisposed() )
            {
                // Validate offset is still valid
                int textLength = styledText.getCharCount();
                if ( insertOffset > textLength )
                {
                    insertOffset = textLength;
                }
                
                styledText.replaceTextRange( insertOffset, 0, textToInsert );
                styledText.setCaretOffset( insertOffset + textToInsert.length() );
            }
        }
        catch ( Exception e )
        {
            // Ignore - document may have changed
        }

        if ( onAccept != null )
        {
            onAccept.run();
        }
    }

    /**
     * Accepts just the next word from the completion.
     */
    public void acceptNextWord()
    {
        if ( !isShowing || ghostText == null || ghostText.isEmpty() )
        {
            return;
        }

        // Find next word boundary
        int wordEnd = 0;
        boolean foundNonWhitespace = false;

        for ( int i = 0; i < ghostText.length(); i++ )
        {
            char c = ghostText.charAt( i );

            if ( Character.isWhitespace( c ) )
            {
                if ( foundNonWhitespace )
                {
                    wordEnd = i;
                    break;
                }
            }
            else
            {
                foundNonWhitespace = true;
            }

            // Handle special characters as word boundaries
            if ( foundNonWhitespace && !Character.isLetterOrDigit( c ) && c != '_' )
            {
                wordEnd = i + 1;
                break;
            }

            wordEnd = i + 1;
        }

        if ( wordEnd > 0 )
        {
            String word = ghostText.substring( 0, wordEnd );
            String remaining = ghostText.substring( wordEnd );

            // Insert the word
            try
            {
                if ( styledText != null && !styledText.isDisposed() )
                {
                    int insertOffset = ghostOffset;
                    int textLength = styledText.getCharCount();
                    if ( insertOffset > textLength )
                    {
                        insertOffset = textLength;
                    }
                    
                    styledText.replaceTextRange( insertOffset, 0, word );
                    int newOffset = insertOffset + word.length();
                    styledText.setCaretOffset( newOffset );
                    
                    // Update ghost state
                    ghostOffset = newOffset;
                }
            }
            catch ( Exception e )
            {
                // Ignore
            }

            // Update ghost text with remaining
            if ( remaining.isEmpty() )
            {
                dismissCompletion();
            }
            else
            {
                ghostText = remaining;
                updateVerticalIndent();
                styledText.redraw();
            }
        }
    }

    /**
     * Dismisses the completion without accepting.
     */
    public void dismissCompletion()
    {
        if ( !isShowing )
        {
            return;
        }

        removeGhostTextDisplay();

        this.ghostText = null;
        this.isShowing = false;

        if ( onDismiss != null )
        {
            onDismiss.run();
        }
    }

    /**
     * Checks if ghost text is currently showing.
     */
    public boolean isShowing()
    {
        return isShowing;
    }
    
    /**
     * Gets the current ghost text content.
     */
    public String getGhostText()
    {
        return ghostText;
    }
    
    /**
     * Gets the offset where ghost text is displayed.
     */
    public int getGhostOffset()
    {
        return ghostOffset;
    }

    /**
     * Sets callback for when completion is accepted.
     */
    public void setOnAccept( Runnable onAccept )
    {
        this.onAccept = onAccept;
    }

    /**
     * Sets callback for when completion is dismissed.
     */
    public void setOnDismiss( Runnable onDismiss )
    {
        this.onDismiss = onDismiss;
    }

    /**
     * Disposes resources.
     */
    public void dispose()
    {
        if ( styledText != null && !styledText.isDisposed() )
        {
            styledText.removeKeyListener( keyListener );
            styledText.removeVerifyListener( verifyListener );
            if ( isPainterInstalled )
            {
                styledText.removePaintListener( ghostTextPainter );
                isPainterInstalled = false;
            }
            removeVerticalIndent();
        }

        if ( ghostColor != null && !ghostColor.isDisposed() )
        {
            ghostColor.dispose();
        }

        if ( italicFont != null && !italicFont.isDisposed() )
        {
            italicFont.dispose();
        }
    }
}
