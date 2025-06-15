
package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;

import jakarta.inject.Inject;

/**
 * Service for project-related operations including structure analysis, 
 * properties, and layout.
 */
@Creatable
public class ProjectService {
    
    @Inject
    ILog logger;
    
    /**
     * Lists all available projects in the workspace with their detected natures.
     * 
     * @return A formatted string containing project information
     */
    public String listProjects() {
        StringBuilder result = new StringBuilder();
        result.append("# Available Projects in Workspace\n\n");
        
        try {
            // Get all projects in the workspace
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            
            if (projects.length == 0) 
            {
                return "No projects found in the workspace.";
            }
            
            // Define common nature IDs
            final String JAVA_NATURE = JavaCore.NATURE_ID; // "org.eclipse.jdt.core.javanature"
            final String CPP_NATURE = "org.eclipse.cdt.core.cnature";
            final String CPP_CC_NATURE = "org.eclipse.cdt.core.ccnature";
            final String PYTHON_NATURE = "org.python.pydev.pythonNature";
            final String JS_NATURE = "org.eclipse.wst.jsdt.core.jsNature";
            final String PHP_NATURE = "org.eclipse.php.core.PHPNature";
            final String WEB_NATURE = "org.eclipse.wst.common.project.facet.core.nature";
            final String MAVEN_NATURE = "org.eclipse.m2e.core.maven2Nature";
            final String GRADLE_NATURE = "org.eclipse.buildship.core.gradleprojectnature";
            
            // List all projects with their status and natures
            for (IProject project : projects) {
                result.append("- **").append(project.getName()).append("**");
                
                // Add project status (open/closed)
                result.append(" (").append(project.isOpen() ? "Open" : "Closed").append(")");
                
                // Only attempt to determine natures if the project is open
                if (project.isOpen()) {
                    try {
                        List<String> detectedNatures = new ArrayList<>();
                        
                        // Check for Java nature
                        if (project.hasNature(JAVA_NATURE)) {
                            IJavaProject javaProject = JavaCore.create(project);
                            String javaVersion = javaProject.getOption(JavaCore.COMPILER_COMPLIANCE, true);
                            detectedNatures.add("Java " + javaVersion);
                        }
                        
                        // Check for C/C++ nature
                        if (project.hasNature(CPP_NATURE)) {
                            detectedNatures.add("C");
                        }
                        if (project.hasNature(CPP_CC_NATURE)) {
                            detectedNatures.add("C++");
                        }
                        
                        // Check for Python nature
                        if (project.hasNature(PYTHON_NATURE)) {
                            detectedNatures.add("Python");
                        }
                        
                        // Check for JavaScript nature
                        if (project.hasNature(JS_NATURE)) {
                            detectedNatures.add("JavaScript");
                        }
                        
                        // Check for PHP nature
                        if (project.hasNature(PHP_NATURE)) {
                            detectedNatures.add("PHP");
                        }
                        
                        // Check for Web nature
                        if (project.hasNature(WEB_NATURE)) {
                            detectedNatures.add("Web");
                        }
                        
                        // Check for build system natures
                        if (project.hasNature(MAVEN_NATURE)) {
                            detectedNatures.add("Maven");
                        }
                        if (project.hasNature(GRADLE_NATURE)) {
                            detectedNatures.add("Gradle");
                        }
                        
                        // If we detected natures, add them to the output
                        if (!detectedNatures.isEmpty()) {
                            result.append(" - Project Type: ").append(String.join(", ", detectedNatures));
                        } else {
                            // Get all natures for projects we couldn't categorize
                            String[] natures = project.getDescription().getNatureIds();
                            if (natures.length > 0) {
                                result.append(" - Other Nature IDs: ").append(String.join(", ", natures));
                            } else {
                                result.append(" - Generic Project (no specific natures)");
                            }
                        }
                    } catch (CoreException e) {
                        result.append(" - Error determining project nature");
                        logger.error(e.getMessage(), e);
                    }
                }
                
                result.append("\n");
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return "Error retrieving projects: " + e.getMessage();
        }
        
        return result.toString();
    }
    
    /**
     * Gets the file and folder structure of a specified project.
     * 
     * @param projectName The name of the project to analyze
     * @return A hierarchical representation of the project structure
     */
    public String getProjectLayout(String projectName) 
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists()) 
        {
            return "Project '" + projectName + "' not found.";
        }
        
        StringBuilder result = new StringBuilder();
        try 
        {
            result.append("# Project Structure: ").append(projectName).append("\n\n");
            collectResourcesForLLM(project, 0, result); // Start with the project root
        } 
        catch (CoreException e)
        {
            logger.error(e.getMessage(), e);
            return "Error retrieving project layout: " + e.getMessage();
        }
        
        return result.toString();
    }
    
    /**
     * Retrieves the properties and configuration of a specified project.
     * 
     * @param projectName The name of the project to analyze
     * @return A formatted string containing project properties
     */
    public String getProjectProperties(String projectName) 
    {
        try 
        {
            // Get the project
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.exists()) {
                return "Error: Project '" + projectName + "' not found.";
            }
            
            if (!project.isOpen()) {
                return "Error: Project '" + projectName + "' is closed.";
            }
            
            StringBuilder result = new StringBuilder();
            result.append("# Project Properties: ").append(projectName).append("\n\n");
            
            // Check project natures
            String[] natures = project.getDescription().getNatureIds();
            result.append("## Project Natures\n");
            for (String nature : natures) {
                result.append("- ").append(nature).append("\n");
            }
            result.append("\n");
            
            // Handle Java projects
            if (project.hasNature(JavaCore.NATURE_ID)) {
                appendJavaProjectProperties(JavaCore.create(project), result);
            }
            
            // Handle C/C++ projects
            if (project.hasNature("org.eclipse.cdt.core.cnature") || 
                project.hasNature("org.eclipse.cdt.core.ccnature")) {
                appendCProjectProperties(project, result);
            }
            
            // Handle Python projects
            if (project.hasNature("org.python.pydev.pythonNature")) {
                appendPythonProjectProperties(project, result);
            }
            
            // If the project doesn't match any of the specific natures, show generic properties
            if (!project.hasNature(JavaCore.NATURE_ID) && 
                !project.hasNature("org.eclipse.cdt.core.cnature") && 
                !project.hasNature("org.eclipse.cdt.core.ccnature") && 
                !project.hasNature("org.python.pydev.pythonNature")) {
                appendGenericProjectProperties(project, result);
            }
            
            return result.toString();
            
        } catch (CoreException e) {
            logger.error(e.getMessage(), e);
            return "Error retrieving project properties: " + e.getMessage();
        }
    }
    
    /**
     * Collects resources in a hierarchical structure for display.
     * 
     * @param resource The starting resource
     * @param depth The current depth in the hierarchy
     * @param result The StringBuilder to append results to
     * @throws CoreException if an error occurs
     */
    private void collectResourcesForLLM(IResource resource, int depth, StringBuilder result) throws CoreException {
        // Use proper indentation with markdown list formatting
        String indent = "";
        for (int i = 0; i < depth; i++) 
        {
            indent += "  ";
        }
        
        // Append the current resource with markdown list item
        String prefix = depth > 0 ? indent + "- " : "- ";
        result.append(prefix).append(resource.getName());
        
        // Add type indicator for better context
        String type = switch ( resource.getType() ) {
            case IResource.FOLDER -> "(Directory)";
            case IResource.FILE -> "(File)";
            case IResource.PROJECT -> "(Project)";
            default -> "";
        };
        result.append( " ");
        result.append( type );
        result.append("\n");
    
        // If the resource is a container, list its children
        if (resource instanceof IContainer) 
        {
            IContainer container = (IContainer) resource;
            IResource[] members = container.members();
            for (IResource member : members) 
            {
                collectResourcesForLLM(member, depth + 1, result);
            }
        }
    }

    /**
     * Appends Java project specific properties to the result.
     * 
     * @param javaProject The Java project to analyze
     * @param result The StringBuilder to append results to
     */
    private void appendJavaProjectProperties(IJavaProject javaProject, StringBuilder result) {
        try {
            result.append("## Java Project Properties\n\n");
            
            // Get Java version
            String javaVersion = javaProject.getOption(JavaCore.COMPILER_COMPLIANCE, true);
            result.append("### Java Version\n");
            result.append("- Compiler Compliance Level: ").append(javaVersion).append("\n");
            
            // Get source compatibility
            String sourceCompatibility = javaProject.getOption(JavaCore.COMPILER_SOURCE, true);
            result.append("- Source Compatibility: ").append(sourceCompatibility).append("\n");
            
            // Get target compatibility
            String targetCompatibility = javaProject.getOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, true);
            result.append("- Target Compatibility: ").append(targetCompatibility).append("\n\n");
            
            // Get output location
            IPath outputLocation = javaProject.getOutputLocation();
            result.append("### Output Location\n");
            result.append("- ").append(outputLocation.toString()).append("\n\n");
            
            // Get source folders
            result.append("### Source Folders\n");
            IClasspathEntry[] entries = javaProject.getRawClasspath();
            for (IClasspathEntry entry : entries) {
                if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
                    result.append("- ").append(entry.getPath().toString()).append("\n");
                }
            }
            result.append("\n");
            
            // Get referenced libraries
            result.append("### Referenced Libraries\n");
            List<String> libraries = new ArrayList<>();
            for (IClasspathEntry entry : entries) {
                if (entry.getEntryKind() == IClasspathEntry.CPE_LIBRARY) {
                    IPath path = entry.getPath();
                    libraries.add(path.toOSString());
                    result.append("- ").append(path.toOSString()).append("\n");
                }
            }
            
            if (libraries.isEmpty()) {
                result.append("- No external libraries referenced\n");
            }
            result.append("\n");
            
            // Get project references
            result.append("### Project References\n");
            boolean hasProjectReferences = false;
            for (IClasspathEntry entry : entries) {
                if (entry.getEntryKind() == IClasspathEntry.CPE_PROJECT) {
                    hasProjectReferences = true;
                    result.append("- ").append(entry.getPath().toString()).append("\n");
                }
            }
            
            if (!hasProjectReferences) {
                result.append("- No project references\n");
            }
            result.append("\n");
            
            // Get compiler options
            result.append("### Compiler Options\n");
            Map<String, String> options = javaProject.getOptions(true);
            List<String> relevantOptions = Arrays.asList(
                JavaCore.COMPILER_PB_UNUSED_LOCAL,
                JavaCore.COMPILER_PB_UNUSED_PRIVATE_MEMBER,
                JavaCore.COMPILER_PB_RAW_TYPE_REFERENCE,
                JavaCore.COMPILER_PB_MISSING_OVERRIDE_ANNOTATION
            );
            
            for (String option : relevantOptions) {
                if (options.containsKey(option)) {
                    result.append("- ").append(option).append(": ").append(options.get(option)).append("\n");
                }
            }
            
        } catch (JavaModelException e) {
            logger.error(e.getMessage(), e);
            result.append("Error retrieving Java project properties: ").append(e.getMessage()).append("\n");
        }
    }
    
    /**
     * Appends C/C++ project specific properties to the result.
     * 
     * @param project The C/C++ project to analyze
     * @param result The StringBuilder to append results to
     */
    private void appendCProjectProperties(IProject project, StringBuilder result) {
        result.append("## C/C++ Project Properties\n\n");
        
        try {
            // Check if it's a C or C++ project or both
            boolean isC = project.hasNature("org.eclipse.cdt.core.cnature");
            boolean isCpp = project.hasNature("org.eclipse.cdt.core.ccnature");
            
            result.append("### Project Type\n");
            if (isC && isCpp) {
                result.append("- C/C++ Project\n\n");
            } else if (isC) {
                result.append("- C Project\n\n");
            } else if (isCpp) {
                result.append("- C++ Project\n\n");
            }
            
            // Get build configurations (requires CDT classes)
            // This is a simplified version as we can't directly use CDT classes without adding dependencies
            result.append("### Build Configuration\n");
            IResource settingsResource = project.findMember(".settings/org.eclipse.cdt.core.prefs");
            if (settingsResource instanceof IFile) {
                IFile settingsFile = (IFile) settingsResource;
                try (InputStream is = settingsFile.getContents()) {
                    Properties props = new Properties();
                    props.load(is);
                    
                    // Extract some common CDT properties
                    List<String> configurationList = new ArrayList<>();
                    for (Object key : props.keySet()) {
                        String keyStr = (String) key;
                        if (keyStr.contains("activeConfiguration")) {
                            result.append("- Active Configuration: ").append(props.getProperty(keyStr)).append("\n");
                        }
                        if (keyStr.contains("configuration")) {
                            String value = props.getProperty(keyStr);
                            if (!configurationList.contains(value)) {
                                configurationList.add(value);
                            }
                        }
                    }
                    
                    if (!configurationList.isEmpty()) {
                        result.append("- Available Configurations: ").append(String.join(", ", configurationList)).append("\n");
                    }
                } catch (IOException e) {
                    logger.error(e.getMessage(), e);
                    result.append("- Could not read CDT configuration properties\n");
                }
            } else {
                result.append("- CDT configuration properties not found\n");
            }
            result.append("\n");
            
            // Check for common build systems
            result.append("### Build System\n");
            if (project.findMember("Makefile") != null) {
                result.append("- Makefile-based project\n");
            } else if (project.findMember("CMakeLists.txt") != null) {
                result.append("- CMake-based project\n");
            } else {
                result.append("- Default CDT managed build\n");
            }
            result.append("\n");
            
            // Look for include directories (simplified approach)
            result.append("### Include Directories\n");
            IResource includePathsFile = project.findMember(".settings/language.settings.xml");
            if (includePathsFile instanceof IFile) {
                result.append("- Include paths defined in language.settings.xml\n");
                // A full implementation would parse the XML file
            } else {
                result.append("- No specific include directories detected\n");
            }
            
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            result.append("Error retrieving C/C++ project properties: ").append(e.getMessage()).append("\n");
        }
    }
    
    /**
     * Appends Python project specific properties to the result.
     * 
     * @param project The Python project to analyze
     * @param result The StringBuilder to append results to
     */
    private void appendPythonProjectProperties(IProject project, StringBuilder result) {
        result.append("## Python Project Properties\n\n");
        
        try {
            // Check for PyDev properties
            IResource pydevProps = project.findMember(".pydevproject");
            
            if (pydevProps instanceof IFile) {
                IFile pydevFile = (IFile) pydevProps;
                try (InputStream is = pydevFile.getContents()) {
                    // Parse the XML content
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    DocumentBuilder builder = factory.newDocumentBuilder();
                    org.w3c.dom.Document doc = builder.parse(is);
                    
                    // Get Python interpreter
                    result.append("### Python Interpreter\n");
                    org.w3c.dom.NodeList interpreterNodes = doc.getElementsByTagName("pydev_property");
                    boolean foundInterpreter = false;
                    
                    for (int i = 0; i < interpreterNodes.getLength(); i++) {
                        org.w3c.dom.Node node = interpreterNodes.item(i);
                        if (node.getAttributes().getNamedItem("name") != null && 
                            "org.python.pydev.PYTHON_PROJECT_INTERPRETER".equals(
                                node.getAttributes().getNamedItem("name").getNodeValue())) {
                            result.append("- ").append(node.getTextContent()).append("\n\n");
                            foundInterpreter = true;
                            break;
                        }
                    }
                    
                    if (!foundInterpreter) {
                        result.append("- Default interpreter\n\n");
                    }
                    
                    // Get Python version
                    result.append("### Python Version\n");
                    boolean foundVersion = false;
                    
                    for (int i = 0; i < interpreterNodes.getLength(); i++) {
                        org.w3c.dom.Node node = interpreterNodes.item(i);
                        if (node.getAttributes().getNamedItem("name") != null && 
                            "org.python.pydev.PYTHON_PROJECT_VERSION".equals(
                                node.getAttributes().getNamedItem("name").getNodeValue())) {
                            result.append("- ").append(node.getTextContent()).append("\n\n");
                            foundVersion = true;
                            break;
                        }
                    }
                    
                    if (!foundVersion) {
                        result.append("- Not specified\n\n");
                    }
                    
                    // Get source paths
                    result.append("### Source Paths\n");
                    org.w3c.dom.NodeList sourcePathNodes = doc.getElementsByTagName("path");
                    boolean foundSourcePaths = false;
                    
                    for (int i = 0; i < sourcePathNodes.getLength(); i++) {
                        org.w3c.dom.Node node = sourcePathNodes.item(i);
                        result.append("- ").append(node.getTextContent()).append("\n");
                        foundSourcePaths = true;
                    }
                    
                    if (!foundSourcePaths) {
                        result.append("- Default source path (project root)\n");
                    }
                    
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                    result.append("Error parsing PyDev project file: ").append(e.getMessage()).append("\n");
                }
            } else {
                result.append("PyDev project file not found. Basic Python project.\n");
            }
            
            // Check for requirements.txt or setup.py
            IResource requirementsFile = project.findMember("requirements.txt");
            IResource setupFile = project.findMember("setup.py");
            
            result.append("\n### Dependencies\n");
            if (requirementsFile instanceof IFile) {
                result.append("- Project uses requirements.txt for dependency management\n");
            } else if (setupFile instanceof IFile) {
                result.append("- Project uses setup.py for packaging and dependency management\n");
            } else {
                result.append("- No standard dependency management files detected\n");
            }
            
            // Check for virtual environment
            IResource venvDir = project.findMember("venv");
            IResource env = project.findMember(".env");
            
            result.append("\n### Virtual Environment\n");
            if (venvDir != null && venvDir.getType() == IResource.FOLDER) {
                result.append("- Project contains a 'venv' virtual environment directory\n");
            } else if (env != null && env.getType() == IResource.FOLDER) {
                result.append("- Project contains a '.env' virtual environment directory\n");
            } else {
                result.append("- No virtual environment directory detected in project root\n");
            }
            
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            result.append("Error retrieving Python project properties: ").append(e.getMessage()).append("\n");
        }
    }
    
    
    /**
     * Appends generic project properties for projects without specific natures.
     * 
     * @param project The project to analyze
     * @param result The StringBuilder to append results to
     */
    private void appendGenericProjectProperties(IProject project, StringBuilder result) {
        try {
            result.append("## Generic Project Properties\n\n");
            
            // Project location
            IPath location = project.getLocation();
            result.append("### Project Location\n");
            result.append("- ").append(location.toOSString()).append("\n\n");
            
            // Check for common build files
            result.append("### Build System Indicators\n");
            List<String> buildFiles = new ArrayList<>();
            
            if (project.findMember("pom.xml") != null) {
                buildFiles.add("Maven (pom.xml)");
            }
            if (project.findMember("build.gradle") != null) {
                buildFiles.add("Gradle (build.gradle)");
            }
            if (project.findMember("build.xml") != null) {
                buildFiles.add("Ant (build.xml)");
            }
            if (project.findMember("package.json") != null) {
                buildFiles.add("Node.js (package.json)");
            }
            if (project.findMember("CMakeLists.txt") != null) {
                buildFiles.add("CMake (CMakeLists.txt)");
            }
            if (project.findMember("Makefile") != null) {
                buildFiles.add("Make (Makefile)");
            }
            
            if (buildFiles.isEmpty()) {
                result.append("- No common build system files detected\n");
            } else {
                for (String buildFile : buildFiles) {
                    result.append("- ").append(buildFile).append("\n");
                }
            }
            result.append("\n");
            
            // Project size metrics
            result.append("### Project Metrics\n");
            int fileCount = countResources(project, IResource.FILE);
            int folderCount = countResources(project, IResource.FOLDER);
            
            result.append("- Total Files: ").append(fileCount).append("\n");
            result.append("- Total Folders: ").append(folderCount).append("\n");
            
            // Check for .gitignore or other VCS files
            result.append("\n### Version Control\n");
            if (project.findMember(".git") != null) {
                result.append("- Git repository (.git directory present)\n");
            } else if (project.findMember(".gitignore") != null) {
                result.append("- Git configuration (.gitignore present)\n");
            } else if (project.findMember(".svn") != null) {
                result.append("- Subversion repository (.svn directory present)\n");
            } else {
                result.append("- No common version control indicators detected\n");
            }
            
            // Project settings
            result.append("\n### Project Settings\n");
            IResource settingsFolder = project.findMember(".settings");
            if (settingsFolder != null && settingsFolder.getType() == IResource.FOLDER) {
                IContainer container = (IContainer) settingsFolder;
                IResource[] members = container.members();
                
                if (members.length > 0) {
                    for (IResource member : members) {
                        if (member.getType() == IResource.FILE) {
                            result.append("- ").append(member.getName()).append("\n");
                        }
                    }
                } else {
                    result.append("- Empty .settings directory\n");
                }
            } else {
                result.append("- No .settings directory found\n");
            }
            
        } catch (CoreException e) {
            logger.error(e.getMessage(), e);
            result.append("Error retrieving generic project properties: ").append(e.getMessage()).append("\n");
        }
    }
    
    /**
     * Counts the number of resources of a specific type in a project.
     * 
     * @param container The container to count resources in
     * @param resourceType The type of resource to count (IResource.FILE or IResource.FOLDER)
     * @return The count of resources of the specified type
     * @throws CoreException if an error occurs while accessing resources
     */
    private int countResources(IContainer container, int resourceType) throws CoreException {
        int count = 0;
        
        IResource[] members = container.members();
        for (IResource resource : members) {
            if (resource.getType() == resourceType) {
                count++;
            }
            
            if (resource instanceof IContainer) {
                count += countResources((IContainer) resource, resourceType);
            }
        }
        
        return count;
    }
}