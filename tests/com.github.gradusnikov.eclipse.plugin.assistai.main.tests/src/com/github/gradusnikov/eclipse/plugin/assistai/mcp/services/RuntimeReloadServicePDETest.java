package com.github.gradusnikov.eclipse.plugin.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.mcp.services.RuntimeReloadService;

public class RuntimeReloadServicePDETest
{
    @Test
    public void testRestartMcpServersDefersWithDefaultDelay()
    {
        CapturingReloadService service = new CapturingReloadService();

        String result = service.restartMcpServers(null);

        assertTrue(service.scheduled);
        assertEquals(1500, service.delayMillis);
        assertNotNull(service.action);
        assertTrue(result.contains("current tool response will complete first"));

        service.action.run();

        assertTrue(service.restarted);
    }

    @Test
    public void testRestartMcpServersRejectsUnsafeDelay()
    {
        CapturingReloadService service = new CapturingReloadService();

        assertThrows(IllegalArgumentException.class, () -> service.restartMcpServers(499));
        assertThrows(IllegalArgumentException.class, () -> service.restartMcpServers(30_001));
    }

    @Test
    public void testReloadWorkspaceBundleRejectsUnknownBundle()
    {
        CapturingReloadService service = new CapturingReloadService();

        assertThrows(IllegalArgumentException.class,
                () -> service.reloadWorkspaceBundle("does.not.exist.assistai.test", 1000));
    }

    private static final class CapturingReloadService extends RuntimeReloadService
    {
        private boolean scheduled;
        private boolean restarted;
        private int delayMillis;
        private Runnable action;

        private CapturingReloadService()
        {
            super(null, null);
        }

        @Override
        protected void schedule(String name, int delayMillis, Runnable action)
        {
            this.scheduled = true;
            this.delayMillis = delayMillis;
            this.action = action;
        }

        @Override
        protected void restartMcpServersNow()
        {
            restarted = true;
        }
    }
}
