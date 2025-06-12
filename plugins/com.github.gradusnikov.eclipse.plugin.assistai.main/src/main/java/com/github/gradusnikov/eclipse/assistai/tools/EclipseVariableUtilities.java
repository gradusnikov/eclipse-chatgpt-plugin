package com.github.gradusnikov.eclipse.assistai.tools;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISelectionService;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.ResourceUtil;

public class EclipseVariableUtilities
{
	/**
	 * Resolves Eclipse IDE variables in the given string and converts Windows paths to WSL paths if needed.
	 * 
	 * @param input The string containing Eclipse variables to resolve
	 * @param isWsl Whether the command is being executed in WSL
	 * @return The string with all variables replaced with their actual values
	 */
	public static String resolveEclipseVariables(String input) {
	    if (input == null || input.isEmpty()) 
	    {
	        return input;
	    }
	    
	    // Check if this is a WSL command
	    boolean isWsl = input.trim().startsWith("wsl") || 
	                    input.contains("\\wsl") || 
	                    input.contains("/wsl");
	    
	    // Pattern to match Eclipse variables like ${workspace_loc}, ${project_loc}, etc.
	    Pattern pattern = Pattern.compile("\\$\\{([^}]+)\\}");
	    Matcher matcher = pattern.matcher(input);
	    
	    StringBuffer result = new StringBuffer();
	    while (matcher.find()) 
	    {
	        String variable = matcher.group(1);
	        String replacement = getVariableValue(variable);
	        
	        // Convert Windows path to WSL path if needed
	        if (isWsl && replacement != null && !replacement.isEmpty()) 
	        {
	            replacement = convertToWslPath(replacement);
	        }
	        
	        // Escape backslashes and dollar signs for the replacement
	        replacement = replacement.replace("\\", "\\\\").replace("$", "\\$");
	        matcher.appendReplacement(result, replacement);
	    }
	    matcher.appendTail(result);
	    
	    return result.toString();
	}

	/**
	 * Gets the value for an Eclipse IDE variable.
	 * 
	 * @param variable The variable name (without ${})
	 * @return The resolved value of the variable
	 */
	private static String getVariableValue(String variable) {
	    return switch ( variable )
	    {
	        case "workspace_loc" ->  resolveWorkspaceLocation(variable);
	        case "project_loc" -> resolveProjectLocation(variable);
	        case "container_loc" -> resolveContainerLocation(variable);
	        case "resource_loc" -> resolveResourceLocation(variable);
	        default -> "${" + variable + "}";
	            
	    };
	}

	/**
	 * Resolves workspace location variables like ${workspace_loc} or ${workspace_loc:/path}
	 */
	private static String resolveWorkspaceLocation(String variable) {
	    IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
	    String location = root.getLocation().toOSString();
	    
	    // Check if there's a path after workspace_loc
	    if (variable.length() > "workspace_loc".length()) 
	    {
	        String path = variable.substring("workspace_loc".length());
	        if (path.startsWith(":")) 
	        {
	            path = path.substring(1); // Remove the colon
	            IResource resource = root.findMember(path);
	            if (resource != null) 
	            {
	                return resource.getLocation().toOSString();
	            }
	        }
	    }
	    
	    return location;
	}

	/**
	 * Resolves project location variables like ${project_loc} or ${project_loc:/path}
	 */
	private static String resolveProjectLocation(String variable) 
	{
	    IResource resource = getSelectedResource();
	    if (resource == null) 
	    {
	        return "${" + variable + "}";
	    }
	    
	    IProject project = resource.getProject();
	    if (project == null) 
	    {
	        return "${" + variable + "}";
	    }
	    
	    // Check if there's a path after project_loc
	    if (variable.length() > "project_loc".length()) 
	    {
	        String path = variable.substring("project_loc".length());
	        if (path.startsWith(":")) 
	        {
	            path = path.substring(1); // Remove the colon
	            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
	            IResource pathResource = root.findMember(path);
	            if (pathResource != null && pathResource.getProject() != null) 
	            {
	                return pathResource.getProject().getLocation().toOSString();
	            }
	        }
	    }
	    
	    return project.getLocation().toOSString();
	}

	/**
	 * Resolves the container location (folder containing the selected resource)
	 */
	private static String resolveContainerLocation(String variable) {
	    IResource resource = getSelectedResource();
	    if (resource == null) 
	    {
	        return "${" + variable + "}";
	    }
	    
	    IContainer container;
	    if (resource instanceof IContainer) 
	    {
	        container = (IContainer) resource;
	    }
	    else 
	    {
	        container = resource.getParent();
	    }
	    
	    if (container == null) 
	    {
	        return "${" + variable + "}";
	    }
	    
	    return container.getLocation().toOSString();
	}

	/**
	 * Resolves the selected resource location
	 */
	private static String resolveResourceLocation(String variable) {
	    IResource resource = getSelectedResource();
	    if (resource == null) 
	    {
	        return "${" + variable + "}";
	    }
	    
	    return resource.getLocation().toOSString();
	}

	/**
	 * Gets the currently selected resource in the workbench
	 * 
	 * @return The selected resource or null if none is selected
	 */
	private static IResource getSelectedResource()
	{
        // Try to get the resource from the active editor
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if ( window != null )
        {
            IWorkbenchPage page = window.getActivePage();
            if ( page != null )
            {
                // First check active editor
                IEditorPart editor = page.getActiveEditor();
                if ( editor != null )
                {
                    IEditorInput input = editor.getEditorInput();
                    IResource resource = ResourceUtil.getResource( input );
                    if ( resource != null )
                    {
                        return resource;
                    }
                }

                // Then check selection
                ISelectionService selectionService = window.getSelectionService();
                ISelection selection = selectionService.getSelection();
                if ( selection instanceof IStructuredSelection )
                {
                    IStructuredSelection structuredSelection = (IStructuredSelection) selection;
                    Object firstElement = structuredSelection.getFirstElement();
                    if ( firstElement instanceof IResource )
                    {
                        return (IResource) firstElement;
                    }
                }
            }
        }
	    return null;
	}

	/**
	 * Converts a Windows path to a WSL path.
	 * 
	 * @param windowsPath The Windows path to convert
	 * @return The equivalent WSL path
	 */
	private static String convertToWslPath(String windowsPath) 
	{
	    if (windowsPath == null || windowsPath.isEmpty()) 
	    {
	        return windowsPath;
	    }
	    
	    // Check if it's a valid Windows path
	    if (windowsPath.length() > 1 && windowsPath.charAt(1) == ':') 
	    {
	        char driveLetter = Character.toLowerCase(windowsPath.charAt(0));
	        String path = windowsPath.substring(2).replace('\\', '/');
	        return "/mnt/" + driveLetter + path;
	    }
	    
	    // If it's not a recognizable Windows path, return it unchanged
	    return windowsPath;
	}
}