package com.github.gradusnikov.eclipse.assistai.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Plain JUnit: the trace text alone, with "own code" decided by a package prefix. */
public class StackTracesTest
{
    private static boolean own( String className )
    {
        return className.startsWith( "sample." );
    }

    @Test
    public void keepsTheMessageAndTheOwnFramesAndCountsTheRest()
    {
        // A JUnit 4 shape: assertion library above the test, reflection and the runner below it.
        String trace = """
                java.lang.AssertionError: expected 5 but was 4
                \tat org.junit.Assert.fail(Assert.java:89)
                \tat org.junit.Assert.assertEquals(Assert.java:120)
                \tat sample.FixtureTest.check(FixtureTest.java:42)
                \tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
                \tat java.base/java.lang.reflect.Method.invoke(Method.java:565)
                \tat org.eclipse.jdt.internal.junit.runner.RemoteTestRunner.main(RemoteTestRunner.java:225)
                """;

        StackTraces.Abridged abridged = StackTraces.abridge( trace, StackTracesTest::own );

        assertEquals( "java.lang.AssertionError: expected 5 but was 4", abridged.message() );
        assertEquals( """
                ... 2 frames in org.junit omitted
                sample.FixtureTest.check(FixtureTest.java:42)
                ... 3 frames omitted""", abridged.frames() );
        assertFalse( abridged.truncated() );
    }

    @Test
    public void aMultiLineAssertionMessageStaysWhole()
    {
        String trace = "org.opentest4j.AssertionFailedError: \nexpected: <a\nb>\n but was: <c>\n\tat sample.T.m(T.java:1)\n";

        StackTraces.Abridged abridged = StackTraces.abridge( trace, StackTracesTest::own );

        assertEquals( "org.opentest4j.AssertionFailedError:\nexpected: <a\nb>\nbut was: <c>", abridged.message() );
        assertEquals( "sample.T.m(T.java:1)", abridged.frames() );
    }

    @Test
    public void causesKeepTheirHeadersAndDropJUnitsRelativeCounts()
    {
        String trace = """
                java.lang.NoClassDefFoundError: com/vladsch/flexmark/html/renderer/LinkType
                \tat com.vladsch.flexmark.html2md.converter.internal.HtmlConverterCoreNodeRenderer.processA(HtmlConverterCoreNodeRenderer.java:271)
                \tat com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter.convert(FlexmarkHtmlConverter.java:346)
                \tat sample.Javadocs.toMarkdown(Javadocs.java:136)
                \tat sample.JavadocsTest.markdownDropsLinks(JavadocsTest.java:72)
                Caused by: java.lang.ClassNotFoundException: com.vladsch.flexmark.html.renderer.LinkType
                \tat java.base/jdk.internal.loader.BuiltinClassLoader.loadClass(BuiltinClassLoader.java:641)
                \tat java.base/java.lang.ClassLoader.loadClass(ClassLoader.java:526)
                \t... 11 more
                """;

        StackTraces.Abridged abridged = StackTraces.abridge( trace, StackTracesTest::own );

        assertEquals( "java.lang.NoClassDefFoundError: com/vladsch/flexmark/html/renderer/LinkType", abridged.message() );
        assertEquals( """
                ... 2 frames in com.vladsch.flexmark.html2md.converter omitted
                sample.Javadocs.toMarkdown(Javadocs.java:136)
                sample.JavadocsTest.markdownDropsLinks(JavadocsTest.java:72)
                Caused by: java.lang.ClassNotFoundException: com.vladsch.flexmark.html.renderer.LinkType
                ... 2 frames omitted""", abridged.frames() );
    }

    @Test
    public void aRunawayOwnTraceIsCappedAndSaysSo()
    {
        String trace = "java.lang.StackOverflowError\n" + "\tat sample.Loop.go(Loop.java:5)\n".repeat( 1000 );

        StackTraces.Abridged abridged = StackTraces.abridge( trace, StackTracesTest::own );

        assertTrue( abridged.truncated() );
        String[] lines = abridged.frames().split( "\n" );
        assertEquals( StackTraces.MAX_FRAMES + 1, lines.length );
        assertEquals( "... more frames in own code omitted", lines[lines.length - 1] );
    }

    @Test
    public void noTraceIsNoneNotEmptyStrings()
    {
        assertEquals( StackTraces.Abridged.NONE, StackTraces.abridge( null, StackTracesTest::own ) );
        assertEquals( StackTraces.Abridged.NONE, StackTraces.abridge( "  \n", StackTracesTest::own ) );
        assertNull( StackTraces.abridge( "java.lang.Error: no frames", StackTracesTest::own ).frames() );
    }

    @Test
    public void commonPackageStopsAtAPackageBoundary()
    {
        assertEquals( "org.junit", StackTraces.commonPackage( List.of( "org.junit.Assert", "org.junit.jupiter.api.AssertTrue" ) ) );
        assertEquals( "", StackTraces.commonPackage( List.of( "org.junit.Assert", "java.lang.reflect.Method" ) ) );
        assertEquals( "java.lang", StackTraces.commonPackage( List.of( "java.lang.ClassLoader" ) ) );
    }
}
