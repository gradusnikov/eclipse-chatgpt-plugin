package com.github.gradusnikov.eclipse.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Plain JUnit for {@link UnitTestService#launchFailureMessage(String, String, boolean)}:
 * the text a refused test launch hands back. The point of this message is that the caller
 * - the AI driving the tool - can act on it: it must say which JUnit engine the run used,
 * and when that engine was guessed, point at the junitVersion argument as the fix.
 */
public class LaunchFailureMessageTest
{
    private static final String JUNIT5 = "org.eclipse.jdt.junit.loader.junit5";
    private static final String JUNIT6 = "org.eclipse.jdt.junit.loader.junit6";

    @Test
    public void carriesTheLaunchersOwnMessage()
    {
        String launcher = "Cannot find 'org.junit.platform.commons.annotation.Testable' on project build path.";
        String message = UnitTestService.launchFailureMessage( launcher, JUNIT5, true );

        assertTrue( message.contains( launcher ),
            "the launcher's own words must survive - they name the missing thing" );
    }

    @Test
    public void namesTheEngineThatWasTriedAndPointsAtTheOverride_whenDetected()
    {
        // The bug: a JUnit 6 project launched as 5 (mis-detected). The message must say
        // "launched with JUnit 5" and tell the caller it can override with junitVersion,
        // so a retry with '6' is an obvious next step instead of a dead end.
        String message = UnitTestService.launchFailureMessage( "JUnit 5 not on the build path", JUNIT5, true );

        assertTrue( message.contains( "JUnit 5" ), "the attempted engine is named" );
        assertTrue( message.contains( "auto-detected" ), "a detected engine is called out as a guess" );
        assertTrue( message.contains( "junitVersion" ), "the fix names the argument to pass" );
        assertTrue( message.contains( "'6'" ), "the possible engine values are offered" );
    }

    @Test
    public void doesNotBlameDetection_whenTheEngineWasAskedFor()
    {
        // The caller chose the engine. A failure is then not a detection problem, so the
        // message must not send them back to the junitVersion argument they already set.
        String message = UnitTestService.launchFailureMessage( "JUnit 6 not on the build path", JUNIT6, false );

        assertTrue( message.contains( "JUnit 6" ), "the requested engine is named" );
        assertTrue( message.contains( "as requested" ), "an asked-for engine is reported as such" );
        assertFalse( message.contains( "auto-detected" ),
            "the caller's own choice is not reported as a guess" );
        assertFalse( message.contains( "re-run and pass" ),
            "do not suggest overriding an engine the caller already picked" );
    }

    @Test
    public void staysReadableWhenNoEngineIsKnown()
    {
        // FROM_LAUNCHER keeps the base config's TEST_KIND, so the service never set one and
        // has none to name. The launcher's message still has to come through intact.
        String message = UnitTestService.launchFailureMessage( "some launcher error", null, false );

        assertTrue( message.contains( "some launcher error" ) );
        assertFalse( message.contains( "JUnit null" ), "an unknown engine is left out, not printed as null" );
    }
}
