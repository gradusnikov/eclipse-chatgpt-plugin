package com.github.gradusnikov.eclipse.assistai.mcp.services;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;

import com.github.gradusnikov.eclipse.assistai.mcp.http.HttpMcpServerRegistry;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class RuntimeReloadService
{
    static final int DEFAULT_DELAY_MILLIS = 1500;
    private static final int MIN_DELAY_MILLIS = 500;
    private static final int MAX_DELAY_MILLIS = 30_000;

    private final IEclipseContext eclipseContext;
    private final ILog logger;

    @Inject
    public RuntimeReloadService(IEclipseContext eclipseContext, ILog logger)
    {
        this.eclipseContext = eclipseContext;
        this.logger = logger;
    }

    public String restartMcpServers(Integer delayMillis)
    {
        int delay = validateDelay(delayMillis);
        schedule("Restart AssistAI MCP servers", delay, this::restartMcpServersNow);
        return "Scheduled MCP server restart in " + delay + " ms. The current tool response will complete first; clients may need to reconnect.";
    }

    protected void restartMcpServersNow()
    {
        // Resolve lazily: the registry creates PDEMcpServer, which injects this
        // service. Eagerly injecting the registry here recurses during startup.
        HttpMcpServerRegistry registry = eclipseContext.get(HttpMcpServerRegistry.class);
        if (registry == null)
        {
            throw new IllegalStateException("The AssistAI HTTP MCP server registry is not available.");
        }
        registry.restart();
    }

    public String reloadWorkspaceBundle(String symbolicName, Integer delayMillis)
    {
        if (symbolicName == null || symbolicName.isBlank())
        {
            throw new IllegalArgumentException("Bundle symbolic name cannot be empty.");
        }

        Bundle bundle = resolveReloadableBundle(symbolicName);
        int delay = validateDelay(delayMillis);
        schedule("Reload workspace bundle " + symbolicName, delay, () -> {
            try
            {
                bundle.update();
            }
            catch (Exception e)
            {
                throw new RuntimeException("Failed to reload bundle '" + symbolicName + "': " + e.getMessage(), e);
            }
        });
        return "Scheduled reload of workspace bundle '" + symbolicName + "' in " + delay
                + " ms. The current tool response will complete first; MCP clients may need to reconnect.";
    }

    protected Bundle resolveReloadableBundle(String symbolicName)
    {
        Bundle bundle = Platform.getBundle(symbolicName);
        if (bundle == null)
        {
            throw new IllegalArgumentException("OSGi bundle not found: " + symbolicName);
        }
        if (bundle.getBundleId() == 0)
        {
            throw new IllegalArgumentException("The OSGi system bundle cannot be reloaded.");
        }
        if (bundle.getHeaders().get(Constants.FRAGMENT_HOST) != null)
        {
            throw new IllegalArgumentException("Bundle fragments cannot be reloaded directly: " + symbolicName);
        }

        IProject workspaceProject = ResourcesPlugin.getWorkspace().getRoot().getProject(symbolicName);
        if (!workspaceProject.exists() || !workspaceProject.isOpen())
        {
            throw new IllegalArgumentException(
                    "Only bundles backed by an open Eclipse workspace project can be reloaded: " + symbolicName);
        }
        return bundle;
    }

    protected void schedule(String name, int delayMillis, Runnable action)
    {
        Job job = new Job(name)
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                if (monitor.isCanceled())
                {
                    return Status.CANCEL_STATUS;
                }
                try
                {
                    action.run();
                    return Status.OK_STATUS;
                }
                catch (RuntimeException e)
                {
                    logger.error(name + " failed: " + e.getMessage(), e);
                    return new Status(IStatus.ERROR, "com.github.gradusnikov.eclipse.plugin.assistai.main", e.getMessage(), e);
                }
            }
        };
        job.setSystem(true);
        job.schedule(delayMillis);
    }

    private int validateDelay(Integer delayMillis)
    {
        int delay = delayMillis == null ? DEFAULT_DELAY_MILLIS : delayMillis;
        if (delay < MIN_DELAY_MILLIS || delay > MAX_DELAY_MILLIS)
        {
            throw new IllegalArgumentException(
                    "Reload delay must be between " + MIN_DELAY_MILLIS + " and " + MAX_DELAY_MILLIS + " ms.");
        }
        return delay;
    }
}
