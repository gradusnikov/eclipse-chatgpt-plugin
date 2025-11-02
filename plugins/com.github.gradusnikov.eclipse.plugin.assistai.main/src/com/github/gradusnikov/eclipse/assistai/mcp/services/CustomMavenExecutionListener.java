package com.github.gradusnikov.eclipse.assistai.mcp.services;

import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.plugin.MojoExecution;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.m2e.core.embedder.MonitorExecutionListener;

/**
 * Custom Maven execution listener that captures Maven build output and redirects it to the console.
 */
public class CustomMavenExecutionListener extends MonitorExecutionListener {
    
    private final String consoleName;
    private ConsoleService consoleService;
    
    /**
     * Creates a new Maven execution listener that redirects output to the specified console.
     * 
     * @param monitor The progress monitor
     * @param consoleName The name of the console to write output to
     * @param consoleService The console service to use for output
     * @param sync The UI synchronization service
     */
    public CustomMavenExecutionListener(IProgressMonitor monitor, String consoleName, 
                                        ConsoleService consoleService) {
        super(monitor);
        this.consoleService = consoleService;
        this.consoleName = consoleName;
    }
    
    @Override
    public void projectDiscoveryStarted(ExecutionEvent event) {
        super.projectDiscoveryStarted(event);
        writeToConsole("Scanning for projects...");
    }
    
    @Override
    public void sessionStarted(ExecutionEvent event) {
        super.sessionStarted(event);
        writeToConsole("Maven build started");
        writeToConsole("Goals: " + event.getSession().getGoals());
    }
    
    @Override
    public void sessionEnded(ExecutionEvent event) {
        super.sessionEnded(event);
        if (event.getSession().getResult().hasExceptions()) {
            writeToConsole("BUILD FAILED");
        } else {
            writeToConsole("BUILD SUCCESS");
        }
        writeToConsole("Maven build finished");
    }
    
    @Override
    public void projectStarted(ExecutionEvent event) {
        super.projectStarted(event);
        writeToConsole("Building " + event.getProject().getName() + " " + event.getProject().getVersion());
    }
    
    @Override
    public void projectSucceeded(ExecutionEvent event) {
        super.projectSucceeded(event);
        writeToConsole("Project " + event.getProject().getName() + " built successfully");
    }
    
    @Override
    public void projectFailed(ExecutionEvent event) {
        super.projectFailed(event);
        writeToConsole("Project " + event.getProject().getName() + " build FAILED");
    }
    
    @Override
    public void mojoStarted(ExecutionEvent event) {
        super.mojoStarted(event);
        MojoExecution mojo = event.getMojoExecution();
        writeToConsole("Executing " + mojo.getGroupId() + ":" + mojo.getArtifactId() + ":" + 
                       mojo.getVersion() + ":" + mojo.getGoal());
    }
    
    @Override
    public void mojoFailed(ExecutionEvent event) {
        super.mojoFailed(event);
        MojoExecution mojo = event.getMojoExecution();
        writeToConsole("FAILED: " + mojo.getGroupId() + ":" + mojo.getArtifactId() + ":" + 
                       mojo.getVersion() + ":" + mojo.getGoal());
        if (event.getException() != null) {
            writeToConsole("Reason: " + event.getException().getMessage());
        }
    }
    
    @Override
    public void mojoSucceeded(ExecutionEvent event) {
        super.mojoSucceeded(event);
        // Uncomment if you want to see success messages for each mojo
        // MojoExecution mojo = event.getMojoExecution();
        // writeToConsole("SUCCESS: " + mojo.getGroupId() + ":" + mojo.getArtifactId() + ":" + 
        //               mojo.getVersion() + ":" + mojo.getGoal());
    }
    
    /**
     * Writes a message to the console.
     * 
     * @param message The message to write
     */
    private void writeToConsole(final String message) {
        consoleService.println( consoleName, message );
    }
}
