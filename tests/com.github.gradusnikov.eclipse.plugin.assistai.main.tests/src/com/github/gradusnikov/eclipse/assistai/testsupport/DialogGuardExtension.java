package com.github.gradusnikov.eclipse.assistai.testsupport;

import java.util.List;

import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.osgi.framework.FrameworkUtil;

/**
 * JUnit 5 extension that guards PDE plug-in tests against unexpected SWT dialogs.
 * <p>
 * Registered automatically for every test in this bundle via JUnit Jupiter's
 * extension auto-detection (see {@code META-INF/services/org.junit.jupiter.api.extension.Extension}
 * and {@code junit-platform.properties}). It only does anything when the test is
 * actually running inside a live OSGi framework - see {@link #isRunningInOsgi()} -
 * which is exactly the case for {@code *PDETest} classes launched as a "JUnit Plug-in
 * Test". Plain JUnit tests (launched as a plain Java process with no Equinox
 * framework) are left completely untouched: no {@code Display} is created and no
 * SWT class is touched for them.
 * <p>
 * <b>Before each test:</b> activates the {@link TestDialogInterceptor} SWT display
 * filter and clears any leftover state from the previous test.
 * <p>
 * <b>After each test:</b> drains pending SWT {@code asyncExec} callbacks (so dialogs
 * opened in the last moments of the test are processed), then fails the test if any
 * unexpected dialog was detected. The failure message includes the dialog title, the
 * label text found inside the shell, and the UI-thread stack trace captured at the
 * moment the shell became visible - making it easy to identify exactly which
 * production-code callsite opened the dialog, instead of the test run hanging forever
 * on an unanswered modal dialog. This includes dialogs like Eclipse's own "Errors exist
 * in required project(s) - Proceed with launch?" prompt: such a dialog is a real signal
 * that something is broken (a required project has compile errors) and should fail the
 * test loudly rather than be silently dismissed.
 * <p>
 * Tests that intentionally trigger a dialog must push a {@link DialogExpectation} via
 * {@link TestDialogInterceptor#expect(DialogExpectation)} <em>before</em> the call that
 * causes the dialog to appear.
 */
public class DialogGuardExtension implements BeforeEachCallback, AfterEachCallback
{
    @Override
    public void beforeEach( ExtensionContext context )
    {
        if ( !isRunningInOsgi() ) return;
//        if (true) return; // to be uncommented only when debugging unit tests
        TestDialogInterceptor.beginTest();
    }

    @Override
    public void afterEach( ExtensionContext context )
    {
        if ( !isRunningInOsgi() ) return;
//        if (true) return; // to be uncommented only when debugging unit tests

        // Drain any asyncExec callbacks that the filter may have posted during the
        // very last operation of the test. We pump for up to 2 s or until the
        // queue is empty, whichever comes first.
        pumpDisplay( 2000 );

        List<TestDialogInterceptor.UnexpectedDialogFailure> dialogFailures = TestDialogInterceptor.endTest();

        if ( !dialogFailures.isEmpty() )
        {
            StringBuilder msg = new StringBuilder();
            msg.append( dialogFailures.size() ).append( " unexpected dialog(s) appeared during the test:\n\n" );
            for ( TestDialogInterceptor.UnexpectedDialogFailure f : dialogFailures )
            {
                msg.append( f.fullInfoIncludingStackTrace ).append( "\n" );
            }
            // Use AssertionError so JUnit marks the test as FAILED (not ERROR)
            throw new AssertionError( msg.toString() );
        }
    }

    /**
     * True only when a live OSGi framework is backing this class' own bundle - i.e. when
     * running as a real "JUnit Plug-in Test" launch. Plain JUnit launches for a PDE
     * project run as a bare Java process with just a computed classpath and no Equinox
     * framework at all, so {@code FrameworkUtil.getBundle(...)} returns {@code null}
     * there. Guarding on this means this extension never touches {@code Display} or any
     * other SWT class outside a real plug-in test - important since plain unit tests may
     * run headless (e.g. in CI) with no display server available at all.
     */
    private static boolean isRunningInOsgi()
    {
        try
        {
            return FrameworkUtil.getBundle( DialogGuardExtension.class ) != null;
        }
        catch ( Throwable t )
        {
            return false;
        }
    }

    /**
     * Pumps the SWT event loop for up to {@code maxMs} milliseconds, draining any
     * pending {@code asyncExec} runnables posted by the display filter. Safe to call
     * from any thread.
     */
    private static void pumpDisplay( long maxMs )
    {
        Display display = Display.getDefault();
        if ( display == null || display.isDisposed() ) return;

        if ( display.getThread() == Thread.currentThread() )
        {
            long end = System.currentTimeMillis() + maxMs;
            while ( System.currentTimeMillis() < end && display.readAndDispatch() )
            {
                /* keep draining */
            }
        }
        else
        {
            // Block the test thread until a syncExec drains the pending runnables
            // on the UI thread. A no-op syncExec flushes all earlier asyncExecs.
            display.syncExec( () -> { /* no-op - just flush */ } );
        }
    }
}
