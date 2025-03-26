
package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.text.IDocument;
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

import com.github.gradusnikov.eclipse.assistai.tools.EclipseUIUtilities;

import jakarta.inject.Inject;

/**
 * Service interface for console-related operations.
 */
@Creatable
public class ConsoleService 
{
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
        // Validate and set default values
        if (maxLines == null || maxLines < 1) 
        {
            maxLines = 100;
        }
        
        if (includeAllConsoles == null) 
        {
            includeAllConsoles = false;
        }
        
        final StringBuilder result = new StringBuilder();
        result.append("# Console Output\n\n");
        
        final int finalMaxLines = maxLines;
        final boolean finalIncludeAllConsoles = includeAllConsoles;
        
        
        result.append( EclipseUIUtilities.syncExec(() -> readConsoleOutput(consoleName, finalMaxLines, finalIncludeAllConsoles) ) 
        );
        
        return result.toString();
    }

	private String readConsoleOutput(String consoleName, final int finalMaxLines, final boolean finalIncludeAllConsoles ) 
	{
		StringBuilder result = new StringBuilder();
		try 
		{
			
		    // Get all consoles from the console manager
		    IConsoleManager consoleManager = ConsolePlugin.getDefault().getConsoleManager();
		    IConsole[] consoles = consoleManager.getConsoles();
		    
		    if (consoles.length == 0) 
		    {
		        throw new RuntimeException("No consoles found in the Eclipse workspace.");
		    }
		    
		    // Filter consoles based on parameters
		    List<IConsole> targetConsoles = new ArrayList<>();
		    
		    if (consoleName != null && !consoleName.trim().isEmpty()) 
		    {
		        // Find console by name
		        for (IConsole console : consoles) 
		        {
		            if (console.getName().contains(consoleName)) 
		            {
		                targetConsoles.add(console);
		            }
		        }
		        if (targetConsoles.isEmpty()) 
		        {
		            throw new RuntimeException("No console found with name containing '" + consoleName + "'.");
		        }
		    } 
		    else if (finalIncludeAllConsoles) 
		    {
		        // Include all consoles
		        targetConsoles.addAll(Arrays.asList(consoles));
		    } 
		    else 
		    {
		        // Get the most recently used console
		        IConsole mostRecentConsole = null;
		        

				// First check if there's an active console page
				Optional<IConsole> consoleOptional = Optional.ofNullable(PlatformUI.getWorkbench().getActiveWorkbenchWindow())
				    .map(IWorkbenchWindow::getActivePage)
				    .map(page -> page.findView(IConsoleConstants.ID_CONSOLE_VIEW))
				    .filter(consoleView -> consoleView instanceof IConsoleView)
				    .map(consoleView -> ((IConsoleView) consoleView).getConsole());
				
				if (consoleOptional.isPresent()) 
				{
				    mostRecentConsole = consoleOptional.get();
				}

		        // If we couldn't find an active console, just use the first one
		        if (mostRecentConsole == null && consoles.length > 0) 
		        {
		            mostRecentConsole = consoles[0];
		        }
		        
		        if (mostRecentConsole != null) 
		        {
		            targetConsoles.add(mostRecentConsole);
		        }
		    }
		    if (targetConsoles.isEmpty()) 
		    {
		        throw new RuntimeException("No applicable consoles found to retrieve output from.");
		    }
		    
		    // Process each target console
		    boolean foundContent = false;
		    for (IConsole console : targetConsoles) 
		    {
		        String consoleContent = "";
		        
		        // Handle different console types
		        if (console instanceof TextConsole) 
		        {
		            consoleContent = getTextConsoleContent((TextConsole) console, finalMaxLines);
		        } 
		        else if (console instanceof IOConsole) 
		        {
		            consoleContent = getIOConsoleContent((IOConsole) console, finalMaxLines);
		        } 
		        else if (console instanceof MessageConsole) 
		        {
		            consoleContent = getMessageConsoleContent((MessageConsole) console, finalMaxLines);
		        }
		        
		        // Add the console content to the result if not empty
		        if (consoleContent != null && !consoleContent.trim().isEmpty()) 
		        {
		            foundContent = true;
		            result.append("## Console: ").append(console.getName()).append("\n\n");
		            result.append("```\n");
		            result.append(consoleContent);
		            if (!consoleContent.endsWith("\n")) 
		            {
		                result.append("\n");
		            }
		            result.append("```\n\n");
		        }
		    }
		    if (!foundContent) 
		    {
		        result.append("No content found in the ")
		              .append(targetConsoles.size() == 1 ? "selected console." : "selected consoles.");
		    }
		    return result.toString();
		} 
		catch (Exception e) 
		{
		    throw new RuntimeException( e );
		}
	}
    
    /**
     * Extracts content from a TextConsole
     * 
     * @param textConsole The console to extract content from
     * @param maxLines Maximum number of lines to retrieve
     * @return The extracted console content
     */
    private String getTextConsoleContent(TextConsole textConsole, int maxLines) 
    {
        IDocument document = textConsole.getDocument();
        if (document == null) 
        {
            return "";
        }
        
        return extractLastLines(document.get(), maxLines);
    }
    
    /**
     * Extracts content from an IOConsole
     * 
     * @param ioConsole The console to extract content from
     * @param maxLines Maximum number of lines to retrieve
     * @return The extracted console content
     */
    private String getIOConsoleContent(IOConsole ioConsole, int maxLines) 
    {
        IDocument document = ioConsole.getDocument();
        if (document == null) 
        {
            return "";
        }
        
        return extractLastLines(document.get(), maxLines);
    }
    
    /**
     * Extracts content from a MessageConsole
     * 
     * @param messageConsole The console to extract content from
     * @param maxLines Maximum number of lines to retrieve
     * @return The extracted console content
     */
    private String getMessageConsoleContent(MessageConsole messageConsole, int maxLines) 
    {
        IDocument document = messageConsole.getDocument();
        if (document == null) 
        {
            return "";
        }
        
        return extractLastLines(document.get(), maxLines);
    }
    
    /**
     * Helper method to extract the last N lines from a string
     * 
     * @param fullContent The full content string
     * @param maxLines Maximum number of lines to extract
     * @return The extracted lines as a string
     */
    private String extractLastLines(String fullContent, int maxLines) 
    {
        Objects.requireNonNull(fullContent, "Console content cannot be null");
        
        String[] lines = fullContent.split("\n");
        int startLine = Math.max(0, lines.length - maxLines);
        
        StringBuilder consoleText = new StringBuilder();
        for (int i = startLine; i < lines.length; i++) 
        {
            consoleText.append(lines[i]).append("\n");
        }
        
        return consoleText.toString();
    }
}