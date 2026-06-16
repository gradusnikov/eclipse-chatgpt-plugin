package com.github.gradusnikov.eclipse.assistai.preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PreferenceInitializer#initializeAuthToken(IPreferenceStore)}.
 *
 * <p>The HTTP MCP server auth token must be generated exactly once, so that a token the
 * user has intentionally cleared (to run the server without bearer authentication) is not
 * silently regenerated on the next startup. A token already stored by a version that
 * predates the one-time marker must be preserved across upgrade.
 */
public class PreferenceInitializerAuthTokenTest
{
    private IPreferenceStore store;

    @BeforeEach
    public void setUp()
    {
        // A plain PreferenceStore is an in-memory IPreferenceStore that persists whatever
        // is set on it, which lets a single instance stand in for state surviving across
        // simulated Eclipse sessions (repeated initializeAuthToken calls).
        store = new PreferenceStore();
    }

    private String token()
    {
        return store.getString( PreferenceConstants.ASSISTAI_MCP_HTTP_AUTH_TOKEN );
    }

    private boolean initialized()
    {
        return store.getBoolean( PreferenceConstants.ASSISTAI_MCP_HTTP_AUTH_TOKEN_INITIALIZED );
    }

    @Test
    public void freshInstallGeneratesTokenAndSetsMarker()
    {
        assertTrue( token().isBlank(), "precondition: no token yet" );
        assertFalse( initialized(), "precondition: marker not set" );

        PreferenceInitializer.initializeAuthToken( store );

        assertFalse( token().isBlank(), "a token should have been generated" );
        // Generated tokens are random UUIDs.
        UUID.fromString( token() );
        assertTrue( initialized(), "marker should be set after generation" );
    }

    @Test
    public void secondRunDoesNotChangeGeneratedToken()
    {
        PreferenceInitializer.initializeAuthToken( store );
        String first = token();

        // Simulates a subsequent Eclipse session re-running the initializer.
        PreferenceInitializer.initializeAuthToken( store );

        assertEquals( first, token(), "an existing generated token must not be regenerated" );
    }

    @Test
    public void clearedTokenIsNotRegenerated()
    {
        // First session: token generated.
        PreferenceInitializer.initializeAuthToken( store );
        assertFalse( token().isBlank() );

        // User clears the token to disable bearer authentication.
        store.setValue( PreferenceConstants.ASSISTAI_MCP_HTTP_AUTH_TOKEN, "" );

        // Next session must respect the cleared token rather than backfilling a new one.
        PreferenceInitializer.initializeAuthToken( store );

        assertTrue( token().isBlank(), "an intentionally cleared token must stay empty" );
        assertTrue( initialized(), "marker must remain set" );
    }

    @Test
    public void upgradePreservesExistingTokenWithoutMarker()
    {
        // Simulates upgrading from a version that stored a token before the marker existed:
        // a token is present but the initialized marker has never been written.
        store.setValue( PreferenceConstants.ASSISTAI_MCP_HTTP_AUTH_TOKEN, "legacy-token-1234" );
        assertFalse( initialized() );

        PreferenceInitializer.initializeAuthToken( store );

        assertEquals( "legacy-token-1234", token(), "an existing token must survive the upgrade" );
        assertTrue( initialized(), "marker should be set during the upgrade" );
    }
}
