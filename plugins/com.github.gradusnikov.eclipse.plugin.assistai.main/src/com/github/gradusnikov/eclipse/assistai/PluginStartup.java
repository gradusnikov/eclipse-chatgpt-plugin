package com.github.gradusnikov.eclipse.assistai;

import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.github.gradusnikov.eclipse.assistai.mcp.http.HttpMcpServerRegistry;
import com.github.gradusnikov.eclipse.assistai.tools.UISynchronizeCallable;

public class PluginStartup implements IStartup
{
    @Override
    public void earlyStartup()
    {
        System.err.println(">>>>>>>");
        Display.getDefault().asyncExec(() -> {
            // Wait for window to be ready
            Display.getDefault().timerExec(5000, () -> {
                // This runs on UI thread
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if (window != null) {
                    System.err.println("Initializing HTTP MCP Server Registry on UI thread");
                        var sync = Activator.getDefault().make( UISynchronizeCallable.class );
                        System.err.println( "UI sync: " + sync );
                        System.err.println("UI sync sync: " +  sync.uiSync );
                        Activator.getDefault().make( HttpMcpServerRegistry.class );
                } else {
                    System.err.println("Window still not ready");
                }
            });
        });
    }
}
