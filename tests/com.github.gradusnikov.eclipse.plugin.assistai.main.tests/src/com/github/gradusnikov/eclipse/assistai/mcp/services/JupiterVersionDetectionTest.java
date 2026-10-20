package com.github.gradusnikov.eclipse.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Plain JUnit for {@link UnitTestService#isJupiterSix(String)}: telling a JUnit 6
 * classpath entry from a JUnit 5 one across the shapes the resolved classpath actually
 * takes. The original check matched only {@code junit-jupiter-api-6.} (one Maven file
 * name), so a JUnit 6 project resolved through an OSGi bundle folder was read as 5 - the
 * bug behind the refused launch.
 */
public class JupiterVersionDetectionTest
{
    @Test
    public void recognisesJUnit6AcrossClasspathShapes()
    {
        // Maven jar, '-' separator
        assertTrue( UnitTestService.isJupiterSix(
            "/home/.m2/repository/org/junit/jupiter/junit-jupiter-api/6.0.0/junit-jupiter-api-6.0.0.jar" ) );
        // OSGi / p2 bundle folder, '_' separator - the shape that used to be missed
        assertTrue( UnitTestService.isJupiterSix(
            "/eclipse/plugins/org.junit.jupiter.api_6.0.0.v20250101.jar" ) );
        // A future major, still 6+ semantics
        assertTrue( UnitTestService.isJupiterSix( "junit-jupiter-api-7.1.0.jar" ) );
    }

    @Test
    public void doesNotMistakeJUnit5ForSix()
    {
        assertFalse( UnitTestService.isJupiterSix(
            "/home/.m2/repository/org/junit/jupiter/junit-jupiter-api/5.11.0/junit-jupiter-api-5.11.0.jar" ) );
        assertFalse( UnitTestService.isJupiterSix( "org.junit.jupiter.api_5.11.0.jar" ) );
    }

    @Test
    public void isNotFooledByAVersionLikeNumberElsewhereInThePath()
    {
        // "6" appears in the path, but not as the junit-jupiter-api version.
        assertFalse( UnitTestService.isJupiterSix( "/opt/tools6/junit-jupiter-api-5.11.0.jar" ) );
        assertFalse( UnitTestService.isJupiterSix( null ) );
        assertFalse( UnitTestService.isJupiterSix( "" ) );
    }
}
