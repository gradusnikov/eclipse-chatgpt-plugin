
package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleConstants;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.IConsoleView;
import org.eclipse.ui.console.IOConsole;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.TextConsole;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Service interface for console-related operations.
 */
@Creatable
public class ConsoleService {
    
    @Inject
    ILog logger;
    
    /**
     * Retrieves the recent output from Eclipse console(s).
     * 
     * @param consoleName Name of the specific console to retrieve (optional)
     * @param maxLines Maximum number of lines to retrieve
     * @param includeAllConsoles Whether to include output from all available consoles
     * @return A formatted string containing console output
     */
    public String getConsoleOutput(String consoleName, Integer maxLines, Boolean includeAllConsoles)
    {
        // Set default values
        if (maxLines == null || maxLines < 1) {
            maxLines = 100;
        }
        
        if (includeAllConsoles == null) {
            includeAllConsoles = false;
        }
        
        final StringBuilder result = new StringBuilder();
        result.append("# Console Output\n\n");
        
        final int finalMaxLines = maxLines;
        final boolean finalIncludeAllConsoles = includeAllConsoles;
        
        Display.getDefault().syncExec(() -> {
            try {
                // Get all consoles from the console manager
                IConsoleManager consoleManager = ConsolePlugin.getDefault().getConsoleManager();
                IConsole[] consoles = consoleManager.getConsoles();
                
                if (consoles.length == 0) {
                    result.append("No consoles found in the Eclipse workspace.");
                    return;
                }
                
                // Filter consoles based on parameters
                List<IConsole> targetConsoles = new ArrayList<>();
                
                if (consoleName != null && !consoleName.trim().isEmpty()) {
                    // Find console by name
                    for (IConsole console : consoles) {
                        if (console.getName().contains(consoleName)) {
                            targetConsoles.add(console);
                        }
                    }
                    
                    if (targetConsoles.isEmpty()) {
                        result.append("No console found with name containing '").append(consoleName).append("'.");
                        return;
                    }
                } else if (finalIncludeAllConsoles) {
                    // Include all consoles
                    targetConsoles.addAll(Arrays.asList(consoles));
                } else {
                    // Get the most recently used console
                    IConsole mostRecentConsole = null;
                    
                    // First check if there's an active console page
                    IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                    if (window != null) {
                        IWorkbenchPage page = window.getActivePage();
                        if (page != null) {
                            IViewPart consoleView = page.findView(IConsoleConstants.ID_CONSOLE_VIEW);
                            if (consoleView instanceof IConsoleView) {
                                IConsole activeConsole = ((IConsoleView) consoleView).getConsole();
                                if (activeConsole != null) {
                                    mostRecentConsole = activeConsole;
                                }
                            }
                        }
                    }
                    
                    // If we couldn't find an active console, just use the first one
                    if (mostRecentConsole == null && consoles.length > 0) {
                        mostRecentConsole = consoles[0];
                    }
                    
                    if (mostRecentConsole != null) {
                        targetConsoles.add(mostRecentConsole);
                    }
                }
                
                if (targetConsoles.isEmpty()) {
                    result.append("No applicable consoles found to retrieve output from.");
                    return;
                }
                
                // Process each target console
                boolean foundContent = false;
                for (IConsole console : targetConsoles) {
                    String consoleContent = "";
                    
                    // Handle different console types
                    if (console instanceof TextConsole) {
                        TextConsole textConsole = (TextConsole) console;
                        IDocument document = textConsole.getDocument();
                        
                        if (document != null) {
                            String fullContent = document.get();
                            
                            // Get the last N lines
                            String[] lines = fullContent.split("\n");
                            int startLine = Math.max(0, lines.length - finalMaxLines);
                            
                            StringBuilder consoleText = new StringBuilder();
                            for (int i = startLine; i < lines.length; i++) {
                                consoleText.append(lines[i]).append("\n");
                            }
                            
                            consoleContent = consoleText.toString();
                        }
                    } else if (console instanceof IOConsole) {
                        IOConsole ioConsole = (IOConsole) console;
                        
                        // For IOConsole, we can only access what's currently in the buffer
                        IDocument document = ioConsole.getDocument();
                        if (document != null) {
                            String fullContent = document.get();
                            
                            // Get the last N lines
                            String[] lines = fullContent.split("\n");
                            int startLine = Math.max(0, lines.length - finalMaxLines);
                            
                            StringBuilder consoleText = new StringBuilder();
                            for (int i = startLine; i < lines.length; i++) {
                                consoleText.append(lines[i]).append("\n");
                            }
                            
                            consoleContent = consoleText.toString();
                        }
                    } else if (console instanceof MessageConsole) {
                        MessageConsole messageConsole = (MessageConsole) console;
                        
                        // For MessageConsole, access the document
                        IDocument document = messageConsole.getDocument();
                        if (document != null) {
                            String fullContent = document.get();
                            
                            // Get the last N lines
                            String[] lines = fullContent.split("\n");
                            int startLine = Math.max(0, lines.length - finalMaxLines);
                            
                            StringBuilder consoleText = new StringBuilder();
                            for (int i = startLine; i < lines.length; i++) {
                                consoleText.append(lines[i]).append("\n");
                            }
                            
                            consoleContent = consoleText.toString();
                        }
                    }
                    
                    // Add the console content to the result if not empty
                    if (consoleContent != null && !consoleContent.trim().isEmpty()) {
                        foundContent = true;
                        result.append("## Console: ").append(console.getName()).append("\n\n");
                        result.append("```\n");
                        result.append(consoleContent);
                        if (!consoleContent.endsWith("\n")) {
                            result.append("\n");
                        }
                        result.append("```\n\n");
                    }
                }
                
                if (!foundContent) {
                    result.append("No content found in the ").append(targetConsoles.size() == 1 ? "selected console." : "selected consoles.");
                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                result.append("Error retrieving console output: ").append(e.getMessage());
            }
        });
        
        return result.toString();
    }
    
    
    
}
