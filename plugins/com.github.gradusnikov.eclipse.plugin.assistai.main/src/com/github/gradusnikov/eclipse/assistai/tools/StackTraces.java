package com.github.gradusnikov.eclipse.assistai.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A failure trace cut down to what locates the fault.
 * <p>
 * A JUnit failure trace is mostly frames nobody acts on: the assertion library, the
 * test runner, reflection, and the library the exception passed through on its way to
 * the test. The lines that matter are the exception with its message and the frames in
 * code the caller can open. Everything else is collapsed to a count, so a failure reads
 * in a few lines instead of forty, and a run with twenty failures stays readable.
 * <p>
 * The pure-text side lives here so it can be tested without a workspace; the caller
 * decides what counts as own code, typically "resolves to a compilation unit".
 */
public final class StackTraces
{
    /**
     * Own-code frames kept before the rest is counted. A stack overflow inside the test
     * project repeats one frame a thousand times, and the first few dozen say it all.
     */
    public static final int MAX_FRAMES = 40;

    /**
     * A trace split into its two useful parts.
     *
     * @param message the exception and its message - every header line before the first
     *            frame, so a multi-line assertion message stays whole. Null for no trace
     * @param frames the own-code frames, one per line without the {@code at} prefix,
     *            with each run of other frames collapsed to {@code ... N frames in <pkg>
     *            omitted}; a {@code Caused by:} or {@code Suppressed:} header starts a new
     *            section. Null when the trace has no frames at all
     * @param truncated whether own-code frames were dropped past {@link #MAX_FRAMES}
     */
    public record Abridged( String message, String frames, boolean truncated )
    {
        public static final Abridged NONE = new Abridged( null, null, false );
    }

    /**
     * {@code at [module/]com.example.Foo$Bar.method(Foo.java:42)} - the declaring class
     * is group 1. The module prefix ({@code java.base/}) is skipped rather than kept, so
     * a class name compares equal however the JVM printed it.
     */
    public static final Pattern FRAME = Pattern.compile( "^\\s*at\\s+(?:[\\w.]+/)?([\\w$.]+)\\.[\\w$<>]+\\(.*\\)\\s*$" );

    /** JUnit's {@code ... 12 more}, which counts frames relative to the enclosing trace and means nothing once that is cut. */
    private static final Pattern ELIDED = Pattern.compile( "^\\s*\\.\\.\\.\\s+\\d+\\s+more\\s*$" );

    private StackTraces()
    {
    }

    /**
     * Abridges a trace.
     *
     * @param ownCode whether a frame's declaring class, as the JVM printed it, is code
     *            worth showing - the test project and what it depends on in the workspace
     */
    public static Abridged abridge( String trace, Predicate<String> ownCode )
    {
        if ( trace == null || trace.isBlank() )
        {
            return Abridged.NONE;
        }

        List<String> message = new ArrayList<>();
        List<String> frames = new ArrayList<>();
        List<String> omitted = new ArrayList<>();
        int kept = 0;
        boolean truncated = false;
        boolean inFrames = false;

        for ( String line : trace.split( "\\R" ) )
        {
            Matcher frame = FRAME.matcher( line );
            if ( frame.matches() )
            {
                inFrames = true;
                String declaringClass = frame.group( 1 );
                if ( !ownCode.test( declaringClass ) )
                {
                    omitted.add( declaringClass );
                }
                else if ( kept < MAX_FRAMES )
                {
                    flushOmitted( frames, omitted );
                    frames.add( frame.group( 0 ).strip().substring( "at ".length() ).strip() );
                    kept++;
                }
                else
                {
                    truncated = true;
                }
            }
            else if ( ELIDED.matcher( line ).matches() || line.isBlank() )
            {
                // Nothing to carry: blank lines separate nothing here, and "... N more" counts frames we no longer show.
            }
            else if ( !inFrames )
            {
                message.add( line.strip() );
            }
            else
            {
                // A cause header, or a continuation line of its message; either way it belongs to the frames side now.
                flushOmitted( frames, omitted );
                frames.add( line.strip() );
            }
        }
        flushOmitted( frames, omitted );
        if ( truncated )
        {
            frames.add( "... more frames in own code omitted" );
        }

        return new Abridged( message.isEmpty() ? null : String.join( "\n", message ),
                frames.isEmpty() ? null : String.join( "\n", frames ), truncated );
    }

    /** Collapses a run of skipped frames into one line naming their common package, when they have one. */
    private static void flushOmitted( List<String> frames, List<String> omitted )
    {
        if ( omitted.isEmpty() )
        {
            return;
        }
        String shared = commonPackage( omitted );
        frames.add( "... " + omitted.size() + ( omitted.size() == 1 ? " frame" : " frames" )
                + ( shared.isEmpty() ? "" : " in " + shared ) + " omitted" );
        omitted.clear();
    }

    /** The longest dotted prefix the class names share, cut at a package boundary; empty when they share none. */
    static String commonPackage( List<String> classNames )
    {
        String prefix = classNames.get( 0 );
        for ( String name : classNames )
        {
            int i = 0;
            while ( i < prefix.length() && i < name.length() && prefix.charAt( i ) == name.charAt( i ) )
            {
                i++;
            }
            prefix = prefix.substring( 0, i );
        }
        int lastDot = prefix.lastIndexOf( '.' );
        return lastDot < 0 ? "" : prefix.substring( 0, lastDot );
    }
}
