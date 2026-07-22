package com.github.gradusnikov.eclipse.assistai.services;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.services.AiIgnoreService.ProjectIgnoreRules;

/**
 * Unit tests for {@link AiIgnoreService} pattern matching logic.
 */
class AiIgnoreServicePDETest
{
    @Test
    void testEmptyRulesNeverExclude()
    {
        ProjectIgnoreRules rules = ProjectIgnoreRules.empty();
        assertFalse(rules.isIgnored("src/Main.java", false));
        assertFalse(rules.isIgnored(".env", false));
        assertFalse(rules.isEmpty() == false);
    }

    @Test
    void testAllDisabledExcludesEverything()
    {
        ProjectIgnoreRules rules = ProjectIgnoreRules.allDisabled();
        assertTrue(rules.isAllAiDisabled());
    }

    @Test
    void testSimpleFilePattern()
    {
        List<FastIgnoreRule> rules = List.of(
                new FastIgnoreRule("*.pem"),
                new FastIgnoreRule("*.key")
        );
        ProjectIgnoreRules ignoreRules = new ProjectIgnoreRules(rules, false);

        assertTrue(ignoreRules.isIgnored("certs/server.pem", false));
        assertTrue(ignoreRules.isIgnored("private.key", false));
        assertFalse(ignoreRules.isIgnored("src/Main.java", false));
    }

    @Test
    void testDirectoryPattern()
    {
        List<FastIgnoreRule> rules = List.of(
                new FastIgnoreRule("secret/")
        );
        ProjectIgnoreRules ignoreRules = new ProjectIgnoreRules(rules, false);

        assertTrue(ignoreRules.isIgnored("secret", true));
        assertFalse(ignoreRules.isIgnored("secret", false));
    }

    @Test
    void testWildcardPattern()
    {
        List<FastIgnoreRule> rules = List.of(
                new FastIgnoreRule(".env*")
        );
        ProjectIgnoreRules ignoreRules = new ProjectIgnoreRules(rules, false);

        assertTrue(ignoreRules.isIgnored(".env", false));
        assertTrue(ignoreRules.isIgnored(".env.local", false));
        assertTrue(ignoreRules.isIgnored(".env.production", false));
        assertFalse(ignoreRules.isIgnored("src/config.env.java", false));
    }

    @Test
    void testNegationPattern()
    {
        List<FastIgnoreRule> rules = List.of(
                new FastIgnoreRule("*.properties"),
                new FastIgnoreRule("!application.properties")
        );
        ProjectIgnoreRules ignoreRules = new ProjectIgnoreRules(rules, false);

        assertTrue(ignoreRules.isIgnored("secrets.properties", false));
        assertFalse(ignoreRules.isIgnored("application.properties", false));
    }

    @Test
    void testDoubleStarPattern()
    {
        List<FastIgnoreRule> rules = List.of(
                new FastIgnoreRule("**/credentials.json")
        );
        ProjectIgnoreRules ignoreRules = new ProjectIgnoreRules(rules, false);

        assertTrue(ignoreRules.isIgnored("credentials.json", false));
        assertTrue(ignoreRules.isIgnored("config/credentials.json", false));
        assertTrue(ignoreRules.isIgnored("a/b/c/credentials.json", false));
        assertFalse(ignoreRules.isIgnored("credentials.yaml", false));
    }

    @Test
    void testPathPattern()
    {
        List<FastIgnoreRule> rules = List.of(
                new FastIgnoreRule("src/main/resources/secret/")
        );
        ProjectIgnoreRules ignoreRules = new ProjectIgnoreRules(rules, false);

        assertTrue(ignoreRules.isIgnored("src/main/resources/secret", true));
        assertFalse(ignoreRules.isIgnored("src/main/resources/public", true));
    }

    @Test
    void testMultipleRulesLastMatchWins()
    {
        List<FastIgnoreRule> rules = List.of(
                new FastIgnoreRule("*"),
                new FastIgnoreRule("!*.java"),
                new FastIgnoreRule("!*.xml")
        );
        ProjectIgnoreRules ignoreRules = new ProjectIgnoreRules(rules, false);

        assertFalse(ignoreRules.isIgnored("Main.java", false));
        assertFalse(ignoreRules.isIgnored("pom.xml", false));
        assertTrue(ignoreRules.isIgnored("secret.txt", false));
        assertTrue(ignoreRules.isIgnored("data.json", false));
    }

    @Test
    void testAiAccessDeniedException()
    {
        AiAccessDeniedException ex = new AiAccessDeniedException("test message");
        assertEquals("test message", ex.getMessage());
        assertInstanceOf(RuntimeException.class, ex);
    }
}
