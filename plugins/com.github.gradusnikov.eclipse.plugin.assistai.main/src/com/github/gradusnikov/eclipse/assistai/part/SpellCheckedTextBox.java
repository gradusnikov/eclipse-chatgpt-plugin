package com.github.gradusnikov.eclipse.assistai.part;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.DefaultTextHover;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.source.AnnotationModel;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.editors.text.TextSourceViewerConfiguration;
import org.eclipse.ui.texteditor.AnnotationPreference;
import org.eclipse.ui.texteditor.DefaultMarkerAnnotationAccess;
import org.eclipse.ui.texteditor.MarkerAnnotationPreferences;
import org.eclipse.ui.texteditor.SourceViewerDecorationSupport;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.TraverseEvent;
import org.eclipse.swt.events.TraverseListener;
import org.eclipse.swt.layout.GridData;

/**
 * A text input component with spell checking and undo/redo/copy/paste, etc.
 */
public class SpellCheckedTextBox
{

    /**
     * Handler for enter key press events within the text box.
     */
    public interface EnterKeyPressHandler
    {
        /**
         * Handles the enter key press.
         * 
         * @param stateMask
         *            the state mask of the event
         */
        void handleEnterKeyPress( int stateMask );
    }

    private SourceViewer         sourceViewer;

    private EnterKeyPressHandler enterKeyPressHandler;

    /**
     * Constructs a SpellCheckedTextBox with a specified parent and an enter key
     * press handler.
     * 
     * @param parent
     *            the parent composite in which this text box is placed
     * @param handler
     *            the handler for enter key press events
     */
    public SpellCheckedTextBox( Composite parent, EnterKeyPressHandler handler )
    {
        this.enterKeyPressHandler = handler;
        initializeSourceViewer( parent );
        addTraverseListener();
        addKeyListener();
    }

    /**
     * Checks if the text widget is disposed.
     * 
     * @return true if the text widget is disposed, false otherwise
     */
    public boolean isDisposed()
    {
        return ( sourceViewer.getTextWidget() == null || sourceViewer.getTextWidget().isDisposed() );
    }

    /**
     * Retrieves the current text from the text widget.
     * 
     * @return the current text
     */
    public String getText()
    {
        return sourceViewer.getTextWidget().getText();
    }

    /**
     * Checks if the text widget is enabled.
     * 
     * @return true if enabled, false otherwise
     */
    public boolean getEnabled()
    {
        return sourceViewer.getTextWidget().getEnabled();
    }

    /**
     * Sets the text of the text widget.
     * 
     * @param text
     *            the text to set
     */
    public void setText( String text )
    {
        Display.getDefault().asyncExec( () -> sourceViewer.getDocument().set( text ) );
    }

    /**
     * Sets focus to the text widget.
     */
    public void setFocus()
    {
        Display.getDefault().asyncExec( () -> sourceViewer.getTextWidget().setFocus() );
    }

    /**
     * Enables or disables the text widget.
     * 
     * @param enabled
     *            true to enable, false to disable
     */
    public void setEnabled( boolean enabled )
    {
        Display.getDefault().asyncExec( () -> sourceViewer.getTextWidget().setEnabled( enabled ) );
    }

    /**
     * Configures the tooltip for the text widget.
     * 
     * @param tooltipText
     *            the text to display as a tooltip
     */
    public void configureTextToolTip( String tooltipText )
    {
        // if (!Preferences.disableTooltips()) {
        sourceViewer.getTextWidget().setToolTipText( tooltipText );
        // }
    }

    /**
     * Initializes the source viewer component within the specified parent
     * composite. Sets up the text editing environment including syntax
     * highlighting, decorations, and document handling.
     * 
     * @param parent
     *            the parent composite in which the source viewer is placed
     */
    private void initializeSourceViewer( Composite parent )
    {
        DefaultMarkerAnnotationAccess access = new DefaultMarkerAnnotationAccess();
        sourceViewer = new SourceViewer( parent, null, null, true, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL );
        sourceViewer.configure( new CustomSourceViewerConfiguration( EditorsUI.getPreferenceStore() ) );
        sourceViewer.getTextWidget().setLayoutData( createGridData( true, true ) );

        Document document = new Document();
        sourceViewer.setDocument( document, new AnnotationModel() );

        SourceViewerDecorationSupport decorationSupport = new SourceViewerDecorationSupport( sourceViewer, null, access, EditorsUI.getSharedTextColors() );
        new MarkerAnnotationPreferences().getAnnotationPreferences()
                .forEach( pref -> decorationSupport.setAnnotationPreference( (AnnotationPreference) pref ) );
        decorationSupport.install( EditorsUI.getPreferenceStore() );

        initializeContextMenu();
    }

    /**
     * Initializes the context menu for the source viewer. Adds standard text
     * editing operations such as undo, redo, cut, copy, and paste.
     */
    private void initializeContextMenu()
    {
        MenuManager menuManager = new MenuManager();
        Object[][] operations = { { "Undo", ITextOperationTarget.UNDO }, { "Redo", ITextOperationTarget.REDO }, { "", -1 }, // separator
                { "Cut", ITextOperationTarget.CUT }, { "Copy", ITextOperationTarget.COPY }, { "Paste", ITextOperationTarget.PASTE }, { "", -1 }, // separator
                { "Select All", ITextOperationTarget.SELECT_ALL } };

        for ( Object[] operation : operations )
        {
            String label = (String) operation[0];
            int actionCode = (Integer) operation[1];

            if ( label.isEmpty() )
            {
                menuManager.add( new Separator() );
            }
            else
            {
                Action action = new Action( label )
                {
                    @Override
                    public void run()
                    {
                        if ( actionCode != -1 )
                        { // Ensure it's not a separator placeholder
                            executeEditorOperation( actionCode );
                        }
                    }
                };
                menuManager.add( action );
            }
        }

        Menu menu = menuManager.createContextMenu( sourceViewer.getTextWidget() );
        sourceViewer.getTextWidget().setMenu( menu );
    }

    /**
     * Executes a specified text operation on the source viewer.
     * 
     * @param operationCode
     *            the code of the operation to execute
     */
    private void executeEditorOperation( int operationCode )
    {
        Display.getDefault().asyncExec( () -> sourceViewer.doOperation( operationCode ) );
    }

    /**
     * Adds a listener to handle key traversal events in the text widget. This
     * method specifically handles the Enter key press to trigger custom actions
     * when the text widget is enabled.
     */
    private void addTraverseListener()
    {
        sourceViewer.getTextWidget().addTraverseListener( new TraverseListener()
        {
            public void keyTraversed( TraverseEvent e )
            {
                if ( getEnabled() )
                {
                    if ( e.detail == SWT.TRAVERSE_RETURN )
                    {
                        enterKeyPressHandler.handleEnterKeyPress( e.stateMask );
                    }
                }
            }
        } );
    }

    /**
     * Adds a key listener to the text widget to handle specific key events for
     * undo and redo operations. This method listens for 'z' key presses
     * combined with modifier keys to execute undo and redo.
     */
    private void addKeyListener()
    {
        sourceViewer.getTextWidget().addKeyListener( new KeyAdapter()
        {
            @Override
            public void keyPressed( KeyEvent e )
            {
                if ( getEnabled() )
                {
                    if ( e.keyCode == 'z' )
                    {
                        if ( ( e.stateMask & SWT.MODIFIER_MASK ) == SWT.MOD1 )
                        {
                            executeEditorOperation( ITextOperationTarget.UNDO );
                        }
                        else if ( ( e.stateMask & SWT.MODIFIER_MASK ) == ( SWT.MOD1 | SWT.MOD2 ) )
                        {
                            executeEditorOperation( ITextOperationTarget.REDO );
                        }
                    }
                }
            }
        } );
    }

    /**
     * Custom configuration for the source viewer, handling preferences and text
     * hovers.
     */
    class CustomSourceViewerConfiguration extends TextSourceViewerConfiguration
    {

        public CustomSourceViewerConfiguration( IPreferenceStore preferenceStore )
        {
            super( preferenceStore );
        }

        @Override
        public ITextHover getTextHover( ISourceViewer sourceViewer, String contentType )
        {
            return new QuickAssistTextHover( sourceViewer );
        }

        /**
         * Provides quick assist support by showing relevant suggestions or
         * corrections at the cursor's position.
         */
        class QuickAssistTextHover extends DefaultTextHover
        {
            private ISourceViewer sourceViewer;

            public QuickAssistTextHover( ISourceViewer sourceViewer )
            {
                super( sourceViewer );
                this.sourceViewer = sourceViewer;
            }

            @Override
            public IRegion getHoverRegion( ITextViewer textViewer, int offset )
            {
                Display.getDefault().asyncExec( () -> ( (SourceViewer) sourceViewer ).doOperation( ISourceViewer.QUICK_ASSIST ) );
                return null;
            }

        }

    }

    public static GridData createGridData( boolean grabExcessHorizontalSpace, boolean grabExcessVerticalSpace )
    {
        return new GridData( SWT.FILL, SWT.FILL, grabExcessHorizontalSpace, grabExcessVerticalSpace );
    }

}