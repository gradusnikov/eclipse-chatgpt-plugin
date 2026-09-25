package com.github.gradusnikov.eclipse.assistai.testsupport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Global interceptor that intercepts SWT dialog shells during PDE plug-in tests.
 * <p>
 * Tests push {@link DialogExpectation}s before triggering code that shows a dialog;
 * the Display filter pops the first matching expectation and satisfies it automatically
 * (filling fields and clicking the configured button). If a dialog appears with no
 * matching expectation, the shell is closed and the failure is recorded so that
 * {@link DialogGuardExtension} can fail the test at the end of the test method with the
 * UI-thread stack trace captured at dialog-open time.
 * <p>
 * A dialog that is not really asking anyone anything - platform noise such as the
 * "blocked jobs" progress dialog Eclipse pops up while the UI thread waits on a build -
 * can be registered once via {@link #ignoreAlways(DialogExpectation)} instead. An
 * ignored dialog is left completely alone: it is neither closed nor treated as a
 * failure, so it dismisses itself the moment the platform is done with it (e.g. once the
 * build it was reporting on finishes), exactly as it would outside a test.
 * <p>
 * <b>Threading note:</b> PDE tests run on a background thread; dialogs open on the SWT
 * UI thread. Expectations are therefore stored in a plain {@link ConcurrentLinkedDeque}
 * (not a {@code ThreadLocal}), and tests are assumed to run sequentially within a suite.
 * <p>
 * <b>Lifecycle:</b>
 * <ol>
 *   <li>The filter is installed lazily the first time {@link #beginTest()} is called and
 *       is never removed during the test run (it is a no-op when {@code active == false}).</li>
 *   <li>{@link #beginTest()} activates the guard and clears per-test state.</li>
 *   <li>{@link #endTest()} deactivates the guard (so non-test code is never intercepted)
 *       and returns any recorded failures.</li>
 * </ol>
 */
public class TestDialogInterceptor
{
    // -------------------------------------------------------------------------
    // State shared across test thread and UI thread
    // -------------------------------------------------------------------------

    /** Whether the guard is currently active (i.e. a test is running). */
    private static volatile boolean active = false;

    /** Expectations pushed by the test thread, consumed by the UI thread. */
    private static final ConcurrentLinkedDeque<DialogExpectation> expectations = new ConcurrentLinkedDeque<>();

    /**
     * Dialog patterns that are never a failure and never touched, across every test in the
     * JVM - see {@link #ignoreAlways(DialogExpectation)}. Unlike {@link #expectations}, these
     * are not consumed on match and not cleared between tests.
     */
    private static final CopyOnWriteArrayList<DialogExpectation> permanentIgnores = new CopyOnWriteArrayList<>();

    /** Failures recorded by the UI thread, read by the test thread in endTest(). */
    private static final CopyOnWriteArrayList<UnexpectedDialogFailure> failures = new CopyOnWriteArrayList<>();

    /** The installed Display filter - non-null once {@link #ensureInstalled()} has run. */
    private static volatile Listener shellShowFilter = null;

    /** Guards one-time installation of the filter. */
    private static final Object INSTALL_LOCK = new Object();

    // -------------------------------------------------------------------------
    // Public API used by tests
    // -------------------------------------------------------------------------

    /**
     * Push an expectation. The next dialog whose title and message match this
     * expectation (substring, case-insensitive) will be satisfied automatically.
     * Expectations are consumed in FIFO order but only the first one that matches
     * a given dialog is used.
     */
    public static void expect( DialogExpectation.Builder builder )
    {
        expect( builder.build() );
    }

    /** @see #expect(DialogExpectation.Builder) */
    public static void expect( DialogExpectation expectation )
    {
        expectations.addLast( expectation );
    }

    /**
     * Registers a dialog pattern that should always be left alone: not closed, not filled
     * in, not counted as a failure - for every test in this JVM, not just the current one.
     * Use this for platform dialogs that are not really prompting anyone for anything, such
     * as Eclipse's "blocked jobs" progress dialog that appears while the UI thread waits on
     * a build or other job. Matching (substring, case-insensitive on title and/or message)
     * is the same as for {@link #expect(DialogExpectation)}.
     */
    public static void ignoreAlways( DialogExpectation.Builder builder )
    {
        ignoreAlways( builder.build() );
    }

    /** @see #ignoreAlways(DialogExpectation.Builder) */
    public static void ignoreAlways( DialogExpectation expectation )
    {
        permanentIgnores.add( expectation );
    }

    // -------------------------------------------------------------------------
    // Lifecycle - called by DialogGuardExtension
    // -------------------------------------------------------------------------

    /** Called by {@link DialogGuardExtension} before each test method. */
    static void beginTest()
    {
        ensureInstalled();
        expectations.clear();
        failures.clear();
        active = true;
    }

    /**
     * Called by {@link DialogGuardExtension} after each test method.
     * Returns a snapshot of any recorded unexpected-dialog failures.
     */
    static List<UnexpectedDialogFailure> endTest()
    {
        active = false;
        List<UnexpectedDialogFailure> snapshot = new ArrayList<>( failures );
        failures.clear();
        expectations.clear();
        return Collections.unmodifiableList( snapshot );
    }

    // -------------------------------------------------------------------------
    // Filter installation
    // -------------------------------------------------------------------------

    private static void ensureInstalled()
    {
        if ( shellShowFilter != null ) return;
        synchronized ( INSTALL_LOCK )
        {
            if ( shellShowFilter != null ) return;
            Display display = Display.getDefault();
            if ( display.getThread() == Thread.currentThread() )
            {
                doInstall( display );
            }
            else
            {
                display.syncExec( () -> doInstall( display ) );
            }
            registerDefaultIgnores();
        }
    }

    /**
     * Registers the platform dialogs that are never a real failure, regardless of which
     * test triggers them. Currently just the "blocked jobs" progress dialog
     * ({@code org.eclipse.ui.internal.progress.BlockedJobsDialog}) that Eclipse pops up
     * whenever the UI thread blocks on a {@code Job.join()} (e.g. waiting for a workspace
     * build): it is informational, dismisses itself once the job it names completes, and
     * takes no input - not the kind of dialog a test failing on it would ever be pointing
     * at a real bug.
     */
    private static void registerDefaultIgnores()
    {
        ignoreAlways( DialogExpectation.withTitleContaining( "User Operation is Waiting" ) );
    }

    private static void doInstall( Display display )
    {
        Listener filter = event -> {
            if ( !(event.widget instanceof Shell) ) return;
            Shell shell = (Shell) event.widget;

            // Skip non-modal shells - workbench windows, progress monitors without modality, etc.
            int style = shell.getStyle();
            boolean isModal = (style & (SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL | SWT.SYSTEM_MODAL)) != 0;
            if ( !isModal ) return;

            if ( !active ) return;

            // Capture the UI-thread stack at dialog-open time for diagnostics
            StackTraceElement[] openStack = Thread.currentThread().getStackTrace();

            String title = shell.getText();
            String message = collectMessage( shell );

            DialogExpectation match = findAndRemoveExpectation( title, message );
            if ( match != null )
            {
                applyExpectation( shell, match );
            }
            else if ( isPermanentlyIgnored( title, message ) )
            {
                // Not a failure and not ours to answer - leave it exactly as the platform
                // made it, so it dismisses itself on its own schedule (e.g. once the build
                // it is reporting on finishes).
            }
            else
            {
                UnexpectedDialogFailure udf = new UnexpectedDialogFailure( title, message, openStack );
                failures.add( udf ); // just in case the throw below does not end up failing the JUnit test - remember it for later

                // make sure it gets closed
                display.asyncExec( () -> {
                    if ( shell.isDisposed() || !shell.isVisible() ) return;
                    shell.close();
                } );

                // try to fail fast
                StringBuilder sb = new StringBuilder();
                udf.addTitleAndMessageToDescription( sb );
                throw new AssertionError( sb.toString() ); // this might close it as well
            }
        };

        display.addFilter( SWT.Show, filter );
        shellShowFilter = filter;
    }

    // -------------------------------------------------------------------------
    // Expectation matching
    // -------------------------------------------------------------------------

    private static DialogExpectation findAndRemoveExpectation( String title, String message )
    {
        for ( DialogExpectation e : expectations )
        {
            if ( matches( e, title, message ) )
            {
                expectations.remove( e );
                return e;
            }
        }
        return null;
    }

    private static boolean matches( DialogExpectation e, String title, String message )
    {
        if ( e.titleContains != null && !containsIgnoreCase( title, e.titleContains ) ) return false;
        if ( e.messageContains != null && !containsIgnoreCase( message, e.messageContains ) ) return false;
        return true;
    }

    private static boolean isPermanentlyIgnored( String title, String message )
    {
        for ( DialogExpectation e : permanentIgnores )
        {
            if ( matches( e, title, message ) ) return true;
        }
        return false;
    }

    private static boolean containsIgnoreCase( String haystack, String needle )
    {
        if ( haystack == null ) return false;
        return haystack.toLowerCase().contains( needle.toLowerCase() );
    }

    // -------------------------------------------------------------------------
    // Expectation application - runs on UI thread
    // -------------------------------------------------------------------------

    private static void applyExpectation( Shell shell, DialogExpectation e )
    {
        // 1. Fill the first editable Text widget (if requested)
        if ( e.inputText != null )
        {
            Text textWidget = findFirstText( shell );
            if ( textWidget != null && !textWidget.isDisposed() )
            {
                textWidget.setText( e.inputText );
                // fire ModifyEvent so validators update
                textWidget.notifyListeners( SWT.Modify, new Event() );
            }
        }

        // 2. Select item in the first Combo (if requested)
        if ( e.comboSelectionContains != null )
        {
            Combo combo = findFirstCombo( shell );
            if ( combo != null && !combo.isDisposed() )
            {
                String[] items = combo.getItems();
                for ( int i = 0; i < items.length; i++ )
                {
                    if ( containsIgnoreCase( items[i], e.comboSelectionContains ) )
                    {
                        combo.select( i );
                        combo.notifyListeners( SWT.Selection, new Event() );
                        break;
                    }
                }
            }
        }

        // 3. Set the first SWT.CHECK button (if requested)
        if ( e.checkboxState != null )
        {
            Button checkbox = findFirstCheckbox( shell );
            if ( checkbox != null && !checkbox.isDisposed() )
            {
                checkbox.setSelection( e.checkboxState.booleanValue() );
                checkbox.notifyListeners( SWT.Selection, new Event() );
            }
        }

        // 4. Click the named button, or just close the shell
        if ( e.buttonTextContains != null )
        {
            Button btn = findButtonByText( shell, e.buttonTextContains );
            if ( btn != null && !btn.isDisposed() && btn.isEnabled() )
            {
                btn.notifyListeners( SWT.Selection, new Event() );
            }
            else
            {
                // Named button not found / disabled - fall back to closing
                shell.close();
            }
        }
        else
        {
            shell.close();
        }
    }

    // -------------------------------------------------------------------------
    // Widget traversal helpers - all run on UI thread
    // -------------------------------------------------------------------------

    /**
     * Collects all visible, non-empty Label texts from the shell's widget tree,
     * joining them with " | " as a diagnostic message string.
     */
    static String collectMessage( Shell shell )
    {
        List<String> texts = new ArrayList<>();
        collectLabels( shell, texts );
        return String.join( " | ", texts );
    }

    private static void collectLabels( Composite composite, List<String> out )
    {
        for ( Control child : composite.getChildren() )
        {
            if ( child instanceof Label )
            {
                String t = ((Label) child).getText();
                if ( t != null && !t.isBlank() && t.length() > 1 )
                {
                    out.add( t.trim() );
                }
            }
            else if ( child instanceof Composite )
            {
                collectLabels( (Composite) child, out );
            }
        }
    }

    private static Text findFirstText( Composite composite )
    {
        for ( Control child : composite.getChildren() )
        {
            if ( child instanceof Text )
            {
                Text t = (Text) child;
                // Skip read-only text widgets
                if ( (t.getStyle() & SWT.READ_ONLY) == 0 ) return t;
            }
            else if ( child instanceof Composite )
            {
                Text found = findFirstText( (Composite) child );
                if ( found != null ) return found;
            }
        }
        return null;
    }

    private static Combo findFirstCombo( Composite composite )
    {
        for ( Control child : composite.getChildren() )
        {
            if ( child instanceof Combo ) return (Combo) child;
            if ( child instanceof Composite )
            {
                Combo found = findFirstCombo( (Composite) child );
                if ( found != null ) return found;
            }
        }
        return null;
    }

    private static Button findFirstCheckbox( Composite composite )
    {
        for ( Control child : composite.getChildren() )
        {
            if ( child instanceof Button && (child.getStyle() & SWT.CHECK) != 0 )
            {
                return (Button) child;
            }
            if ( child instanceof Composite )
            {
                Button found = findFirstCheckbox( (Composite) child );
                if ( found != null ) return found;
            }
        }
        return null;
    }

    private static Button findButtonByText( Composite composite, String text )
    {
        for ( Control child : composite.getChildren() )
        {
            if ( child instanceof Button )
            {
                Button btn = (Button) child;
                if ( (btn.getStyle() & SWT.PUSH) != 0 && containsIgnoreCase( btn.getText(), text ) )
                {
                    return btn;
                }
            }
            if ( child instanceof Composite )
            {
                Button found = findButtonByText( (Composite) child, text );
                if ( found != null ) return found;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Failure record
    // -------------------------------------------------------------------------

    /**
     * Records an unexpected dialog that appeared during a test.
     * Carries the title, collected message text, and the UI-thread stack trace
     * at the moment the shell became visible - so the callsite in production code
     * that opened the dialog is easily identified.
     */
    public static class UnexpectedDialogFailure
    {
        public final String title;
        public final String message;
        public final String fullInfoIncludingStackTrace;

        UnexpectedDialogFailure( String title, String message, StackTraceElement[] uiThreadStack )
        {
            this.title = title;
            this.message = message;
            this.fullInfoIncludingStackTrace = describe( uiThreadStack );
        }

        /** Formats a human-readable description for use in assertion failure messages. */
        private String describe( StackTraceElement[] uiThreadStack )
        {
            StringBuilder sb = new StringBuilder();
            addTitleAndMessageToDescription( sb );
            sb.append( "  Opened from (UI thread):\n" );
            for ( StackTraceElement frame : uiThreadStack )
            {
                sb.append( "    at " ).append( frame ).append( "\n" );
            }
            return sb.toString();
        }

        public void addTitleAndMessageToDescription( StringBuilder sb )
        {
            sb.append( "Unexpected dialog (use TestDialogInterceptor.expect(...) if you expect one):\n" );
            sb.append( "  Title  : " ).append( title ).append( "\n" );
            sb.append( "  Message: " ).append( message ).append( "\n" );
        }
    }
}
