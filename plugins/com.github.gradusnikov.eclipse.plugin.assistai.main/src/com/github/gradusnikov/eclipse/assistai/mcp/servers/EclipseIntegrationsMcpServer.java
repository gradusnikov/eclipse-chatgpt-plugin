package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;
import com.github.gradusnikov.eclipse.assistai.mcp.services.CodeAnalysisService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.CodeEditingService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.ConsoleService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.EditorService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.MarkdownService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.JavaDocService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.MavenService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.OutlineService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.ProjectService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.ResourceService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.SearchService;
import com.github.gradusnikov.eclipse.assistai.mcp.services.UnitTestService;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceResultSerializer;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceToolResult;

import jakarta.inject.Inject;

@Creatable
@McpServer(name = "eclipse-ide")
public class EclipseIntegrationsMcpServer
{
    @Inject
    private JavaDocService javaDocService;

    @Inject
    private ProjectService projectService;

    @Inject
    private CodeAnalysisService codeAnalysisService;

    @Inject
    private ResourceService resourceService;

    @Inject
    private SearchService searchService;

    @Inject
    private EditorService editorService;

    @Inject
    private ConsoleService consoleService;

    @Inject
    private CodeEditingService codeEditingService;

    @Inject
    private UnitTestService unitTestService;

    @Inject
    private MavenService mavenService;

    @Inject
    private OutlineService outlineService;

    @Inject
    private MarkdownService markdownService;

    @Tool(name = "formatCode", description = "Formats code according to the current Eclipse formatter settings.", type = "object")
    public String formatCode(
            @ToolParam(name = "code", description = "The code to be formatted", required = true) String code,
            @ToolParam(name = "projectName", description = "Optional project name to use project-specific formatter settings", required = false) String projectName)
    {
        return codeEditingService.formatCode(code, projectName);
    }

    @Tool(name = "getJavaDoc", description = "Get the JavaDoc for the given compilation unit.  For example,a class B defined as a member type of a class A in package x.y should have athe fully qualified name \"x.y.A.B\".Note that in order to be found, a type name (or its top level enclosingtype name) must match its corresponding compilation unit name.", type = "object")
    public String getJavaDoc(
            @ToolParam(name = "fullyQualifiedName", description = "A fully qualified name of the compilation unit", required = true) String fullyQualifiedClassName)
    {
        return javaDocService.getJavaDoc(fullyQualifiedClassName);
    }

    @Tool(name = "getSource", description = "Get the source for the given class.", type = "object")
    public String getSource(
            @ToolParam(name = "fullyQualifiedClassName", description = "A fully qualified class name of the Java class", required = true) String fullyQualifiedClassName)
    {
        // Use resource-aware method and serialize for caching
        ResourceToolResult result = javaDocService.getSourceWithResource(fullyQualifiedClassName);
        return ResourceResultSerializer.serialize(result);
    }

    @Tool(name = "getClassOutline", description = "Returns a compact outline of a Java class: class declaration, field declarations, method signatures (no bodies), and inner types â all with line numbers. Much more token-efficient than getSource for understanding class structure. Use this first, then getMethodSource for specific methods.", type = "object")
    public String getClassOutline(
            @ToolParam(name = "fullyQualifiedClassName", description = "A fully qualified class name (e.g. 'com.example.MyClass')", required = true) String fullyQualifiedClassName,
            @ToolParam(name = "includeFields", description = "Whether to include field declarations (default: true)", required = false) String includeFields)
    {
        boolean fields = Optional.ofNullable(includeFields).map(Boolean::parseBoolean).orElse(true);
        ResourceToolResult result = outlineService.getClassOutline(fullyQualifiedClassName, fields);
        return ResourceResultSerializer.serialize(result);
    }

    @Tool(name = "getMethodSource", description = "Returns the source code of specific method(s) with line numbers. Accepts comma-separated method names to retrieve multiple methods in one call. Use after getClassOutline to read only the methods you need.", type = "object")
    public String getMethodSource(
            @ToolParam(name = "fullyQualifiedClassName", description = "A fully qualified class name (e.g. 'com.example.MyClass')", required = true) String fullyQualifiedClassName,
            @ToolParam(name = "methodNames", description = "Comma-separated method names to retrieve (e.g. 'findById,save,delete')", required = true) String methodNames,
            @ToolParam(name = "methodSignature", description = "Optional parameter type hint to disambiguate overloaded methods (e.g. 'String')", required = false) String methodSignature,
            @ToolParam(name = "includeJavadoc", description = "Whether to include Javadoc comments (default: true)", required = false) String includeJavadoc)
    {
        boolean javadoc = Optional.ofNullable(includeJavadoc).map(Boolean::parseBoolean).orElse(true);
        ResourceToolResult result = outlineService.getMethodSource(fullyQualifiedClassName, methodNames, methodSignature, javadoc);
        return ResourceResultSerializer.serialize(result);
    }

    @Tool(name = "getFilteredSource", description = "Returns source code with optional import exclusion and selective method expansion. Methods not in the expand list are collapsed to their signature with line ranges. Line numbers always match the original file for accurate editing.", type = "object")
    public String getFilteredSource(
            @ToolParam(name = "fullyQualifiedClassName", description = "A fully qualified class name (e.g. 'com.example.MyClass')", required = true) String fullyQualifiedClassName,
            @ToolParam(name = "excludeImports", description = "Whether to collapse the import block (default: true)", required = false) String excludeImports,
            @ToolParam(name = "methodNames", description = "Comma-separated method names to fully expand. Methods not listed are collapsed to signatures. If omitted, all methods are expanded.", required = false) String methodNames)
    {
        boolean noImports = Optional.ofNullable(excludeImports).map(Boolean::parseBoolean).orElse(true);
        ResourceToolResult result = outlineService.getFilteredSource(fullyQualifiedClassName, noImports, methodNames);
        return ResourceResultSerializer.serialize(result);
    }

    @Tool(name = "getProjectProperties", description = "Retrieves the properties and configuration of a specified project.", type = "object")
    public String getProjectProperties(
            @ToolParam(name = "projectName", description = "The name of the project to analyze", required = true) String projectName)
    {
        return projectService.getProjectProperties(projectName);
    }

    @Tool(name = "getProjectLayout", description = "Get the file and folder structure of a specified project in a hierarchical format. For large projects, use scopePath to limit to a subdirectory and/or maxDepth to limit tree depth.", type = "object")
    public String getProjectLayout(
            @ToolParam(name = "projectName", description = "The name of the project to analyze", required = true) String projectName,
            @ToolParam(name = "scopePath", description = "Optional path relative to the project root to limit the listing (e.g., 'src/main/java/com/example'). If omitted, shows the entire project.", required = false) String scopePath,
            @ToolParam(name = "maxDepth", description = "Optional maximum depth of the directory tree to display (e.g., '3' for 3 levels deep). If omitted, shows all levels.", required = false) String maxDepth)
    {
        int depth = Optional.ofNullable(maxDepth).map(Integer::parseInt).orElse(-1);
        ResourceToolResult result = projectService.getProjectLayoutWithResource(projectName, scopePath, depth);
        return ResourceResultSerializer.serialize(result);
    }

    @Tool(name = "getMethodCallHierarchy", description = "Retrieves the call hierarchy (callers) for a specified method to understand how it's used in the codebase.", type = "object")
    public String getMethodCallHierarchy(
            @ToolParam(name = "fullyQualifiedClassName", description = "The fully qualified name of the class containing the method", required = true) String fullyQualifiedClassName,
            @ToolParam(name = "methodName", description = "The name of the method to analyze", required = true) String methodName,
            @ToolParam(name = "methodSignature", description = "The signature of the method (optional, required if method is overloaded)", required = false) String methodSignature,
            @ToolParam(name = "maxDepth", description = "Maximum depth of the call hierarchy to retrieve (default: 3)", required = false) String maxDepth)
    {
        return codeAnalysisService.getMethodCallHierarchy(fullyQualifiedClassName, methodName, methodSignature,
                Optional.ofNullable(maxDepth).map(Integer::parseInt).orElse(0));
    }

    @Tool(name = "getCompilationErrors", description = "Retrieves compilation errors and problems from the current workspace or a specific project.", type = "object")
    public String getCompilationErrors(
            @ToolParam(name = "projectName", description = "The name of the specific project to check (optional, leave empty for all projects)", required = false) String projectName,
            @ToolParam(name = "severity", description = "Filter by severity level: 'ERROR', 'WARNING', or 'ALL' (default)", required = false) String severity,
            @ToolParam(name = "maxResults", description = "Maximum number of problems to return (default: 50)", required = false) String maxResults)
    {
        return codeAnalysisService.getCompilationErrors(projectName, severity,
                Optional.ofNullable(maxResults).map(Integer::parseInt).orElse(0));
    }

    @Tool(name = "readProjectResource", description = "Read the content of a text resource from a specified project. Supports line numbers, reading specific line ranges, and collapsing Java imports to reduce token usage.", type = "object")
    public String readProjectResource(
            @ToolParam(name = "projectName", description = "The name of the project containing the resource", required = true) String projectName,
            @ToolParam(name = "resourcePath", description = "The path to the resource relative to the project root", required = true) String resourcePath,
            @ToolParam(name = "showLineNumbers", description = "If 'true', prepends line numbers to each line (like cat -n). Useful for creating accurate patches. Default: 'false'", required = false) String showLineNumbers,
            @ToolParam(name = "startLine", description = "Optional 1-based start line to read from. If omitted, reads from the beginning.", required = false) String startLine,
            @ToolParam(name = "endLine", description = "Optional 1-based end line to read to (inclusive). If omitted, reads to the end.", required = false) String endLine,
            @ToolParam(name = "excludeImports", description = "If 'true', collapses Java import statements into a single summary line. Line numbers are preserved for accurate editing. Default: 'false'", required = false) String excludeImports)
    {
        boolean lineNumbers = Optional.ofNullable(showLineNumbers).map(Boolean::parseBoolean).orElse(false);
        int start = Optional.ofNullable(startLine).map(Integer::parseInt).orElse(0);
        int end = Optional.ofNullable(endLine).map(Integer::parseInt).orElse(0);
        boolean noImports = Optional.ofNullable(excludeImports).map(Boolean::parseBoolean).orElse(false);
        ResourceToolResult result = resourceService.readProjectResourceWithResource(projectName, resourcePath, lineNumbers, start, end, noImports);
        return ResourceResultSerializer.serialize(result);
    }

    @Tool(name = "listProjects", description = "List all available projects in the workspace with their detected natures (Java, C/C++, Python, etc.).", type = "object")
    public String listProjects()
    {
        return projectService.listProjects();
    }

    @Tool(name = "getCurrentlyOpenedFile", description = "Gets information about the currently active file in the Eclipse editor.", type = "object")
    public String getCurrentlyOpenedFile()
    {
        // Use resource-aware method and serialize for caching
        ResourceToolResult result = editorService.getCurrentlyOpenedFileContentWithResource();
        return ResourceResultSerializer.serialize(result);
    }

    @Tool(name = "getEditorSelection", description = "Gets the currently selected text or lines in the active editor.", type = "object")
    public String getEditorSelection()
    {
        return editorService.getEditorSelection();
    }

    @Tool(name = "getConsoleOutput", description = "Retrieves the recent output from Eclipse console(s).", type = "object")
    public String getConsoleOutput(
            @ToolParam(name = "consoleName", description = "Name of the specific console to retrieve (optional, leave empty for all or most recent console)", required = false) String consoleName,
            @ToolParam(name = "maxLines", description = "Maximum number of lines to retrieve (default: 100)", required = false) String maxLines,
            @ToolParam(name = "includeAllConsoles", description = "Whether to include output from all available consoles (default: false)", required = false) Boolean includeAllConsoles)
    {
        // Use resource-aware method and serialize for caching
        ResourceToolResult result = consoleService.getConsoleOutputWithResource(consoleName,
                Optional.ofNullable(maxLines).map(Integer::parseInt).orElse(0), includeAllConsoles);
        return ResourceResultSerializer.serialize(result);
    }

    // Unit Test Service Tools

    @Tool(name = "runAllTests", description = "Runs all JUnit tests in a specified project and returns the results. Use findTestClasses first if unsure which project contains tests. The projectName must be the test project (e.g. 'my.app.tests'), not the main source project.", type = "object")
    public String runAllTests(
            @ToolParam(name = "projectName", description = "The exact Eclipse project name containing the test classes (use listProjects to find it)", required = true) String projectName,
            @ToolParam(name = "timeout", description = "Maximum time in seconds to wait for test completion (default: 60)", required = false) String timeout)
    {
        return unitTestService.runAllTests(projectName, Optional.ofNullable(timeout).map(Integer::parseInt).orElse(60));
    }

    @Tool(name = "runPackageTests", description = "Runs all JUnit tests in a specific package and returns the results.", type = "object")
    public String runPackageTests(
            @ToolParam(name = "projectName", description = "The exact Eclipse project name containing the test classes (use listProjects to find it)", required = true) String projectName,
            @ToolParam(name = "packageName", description = "The fully qualified package name (e.g. 'com.example.service')", required = true) String packageName,
            @ToolParam(name = "timeout", description = "Maximum time in seconds to wait for test completion (default: 60)", required = false) String timeout)
    {
        return unitTestService.runPackageTests(projectName, packageName,
                Optional.ofNullable(timeout).map(Integer::parseInt).orElse(60));
    }

    @Tool(name = "runClassTests", description = "Runs all JUnit tests in a specific test class and returns the results.", type = "object")
    public String runClassTests(
            @ToolParam(name = "projectName", description = "The exact Eclipse project name containing the test class (use listProjects to find it)", required = true) String projectName,
            @ToolParam(name = "className", description = "The fully qualified class name including package (e.g. 'com.example.MyServiceTest')", required = true) String className,
            @ToolParam(name = "timeout", description = "Maximum time in seconds to wait for test completion (default: 60)", required = false) String timeout)
    {
        return unitTestService.runClassTests(projectName, className,
                Optional.ofNullable(timeout).map(Integer::parseInt).orElse(60));
    }

    @Tool(name = "runTestMethod", description = "Runs a single JUnit test method and returns the results.", type = "object")
    public String runTestMethod(
            @ToolParam(name = "projectName", description = "The exact Eclipse project name containing the test class (use listProjects to find it)", required = true) String projectName,
            @ToolParam(name = "className", description = "The fully qualified class name including package (e.g. 'com.example.MyServiceTest')", required = true) String className,
            @ToolParam(name = "methodName", description = "The test method name without parentheses (e.g. 'testCreate')", required = true) String methodName,
            @ToolParam(name = "timeout", description = "Maximum time in seconds to wait for test completion (default: 60)", required = false) String timeout)
    {
        return unitTestService.runTestMethod(projectName, className, methodName,
                Optional.ofNullable(timeout).map(Integer::parseInt).orElse(60));
    }

    @Tool(name = "findTestClasses", description = "Finds all test classes in a project. Use this before runAllTests or runClassTests to discover the correct project name and fully qualified class names.", type = "object")
    public String findTestClasses(
            @ToolParam(name = "projectName", description = "The exact Eclipse project name to search (use listProjects to find it)", required = true) String projectName)
    {
        return unitTestService.findTestClasses(projectName);
    }

    // Maven Service Tools

    @Tool(name = "runMavenBuild", description = "Runs a Maven build with the specified goals on a project.", type = "object")
    public String runMavenBuild(
            @ToolParam(name = "projectName", description = "The name of the project to build", required = true) String projectName,
            @ToolParam(name = "goals", description = "The Maven goals to execute (e.g., \"clean install\")", required = true) String goals,
            @ToolParam(name = "profiles", description = "Optional Maven profiles to activate", required = false) String profiles,
            @ToolParam(name = "timeout", description = "Maximum time in seconds to wait for build completion (0 for no timeout)", required = false) String timeout)
    {
        return mavenService.runMavenBuild(projectName, goals, profiles,
                Optional.ofNullable(timeout).map(Integer::parseInt).orElse(0));
    }

    @Tool(name = "getEffectivePom", description = "Gets the effective POM for a Maven project.", type = "object")
    public String getEffectivePom(
            @ToolParam(name = "projectName", description = "The name of the Maven project", required = true) String projectName)
    {
        return mavenService.getEffectivePom(projectName);
    }

    @Tool(name = "listMavenProjects", description = "Lists all available Maven projects in the workspace.", type = "object")
    public String listMavenProjects()
    {
        return mavenService.listMavenProjects();
    }

    @Tool(name = "getProjectDependencies", description = "Gets Maven project dependencies.", type = "object")
    public String getProjectDependencies(
            @ToolParam(name = "projectName", description = "The name of the Maven project", required = true) String projectName)
    {
        return mavenService.getProjectDependencies(projectName);
    }

    // Code Analysis Tools

    @Tool(name = "getTypeHierarchy", description = "Retrieves the type hierarchy (supertypes, implemented interfaces, and subtypes) for a given Java class or interface.", type = "object")
    public String getTypeHierarchy(
            @ToolParam(name = "fullyQualifiedClassName", description = "The fully qualified name of the class (e.g., 'com.example.MyClass')", required = true) String fullyQualifiedClassName)
    {
        return codeAnalysisService.getTypeHierarchy(fullyQualifiedClassName);
    }

    @Tool(name = "findReferences", description = "Finds all references/usages of a Java type, method, or field across the entire workspace. Essential before renaming or deleting code elements.", type = "object")
    public String findReferences(
            @ToolParam(name = "fullyQualifiedClassName", description = "The fully qualified name of the class containing the element", required = true) String fullyQualifiedClassName,
            @ToolParam(name = "elementName", description = "Optional method or field name to search for. If omitted, searches for references to the class itself.", required = false) String elementName)
    {
        return codeAnalysisService.findReferences(fullyQualifiedClassName, elementName);
    }

    @Tool(name = "getQuickFixes", description = "Gets available quick fixes for compilation errors in a Java file. Shows problem details and suggested corrections for each error.", type = "object")
    public String getQuickFixes(
            @ToolParam(name = "projectName", description = "The name of the project containing the file", required = true) String projectName,
            @ToolParam(name = "filePath", description = "The path to the Java file relative to the project root", required = true) String filePath,
            @ToolParam(name = "lineNumber", description = "Optional line number to filter fixes for a specific error", required = false) String lineNumber)
    {
        return codeAnalysisService.getQuickFixes(projectName, filePath,
                Optional.ofNullable(lineNumber).map(Integer::parseInt).orElse(null));
    }

    @Tool(name = "getImportSuggestions", description = "Finds import candidates for unresolved types in a Java file. Shows matching fully qualified names from the workspace for each unresolved type error.", type = "object")
    public String getImportSuggestions(
            @ToolParam(name = "projectName", description = "The name of the project containing the file", required = true) String projectName,
            @ToolParam(name = "filePath", description = "The path to the Java file relative to the project root", required = true) String filePath)
    {
        return codeAnalysisService.getImportSuggestions(projectName, filePath);
    }

    // Search Service Tools

    @Tool(name = "fileSearch", description = "Searches for a plain substring in workspace files using Eclipse's text search engine.", type = "object")
    public String fileSearch(
            @ToolParam(name = "containingText", description = "Text that must be contained in a line (plain substring, not regex)", required = true) String containingText,
            @ToolParam(name = "fileNamePatterns", description = "Optional file name patterns. Accepts either an array (e.g. [\"*.java\", \"*.xml\"]) or a string (e.g. \"*.java,*.xml\"). If omitted, all files are searched.", required = false) Object fileNamePatterns)
    {
        String[] patterns = normalizeFileNamePatterns(fileNamePatterns);
        return searchService.fileSearch(containingText, patterns).toString();
    }

    @Tool(name = "fileSearchRegExp", description = "Searches workspace files using a Java regular expression via Eclipse's text search engine.", type = "object")
    public String fileSearchRegExp(
            @ToolParam(name = "pattern", description = "Java regular expression", required = true) String pattern,
            @ToolParam(name = "fileNamePatterns", description = "Optional file name patterns. Accepts either an array (e.g. [\"*.java\", \"*.xml\"]) or a string (e.g. \"*.java,*.xml\"). If omitted, all files are searched.", required = false) Object fileNamePatterns)
    {
        String[] patterns = normalizeFileNamePatterns(fileNamePatterns);
        return searchService.fileSearchRegExp(pattern, patterns).toString();
    }

    @Tool(name = "findFiles", description = "Finds workspace files matching the given glob patterns.", type = "object")
    public String findFiles(
            @ToolParam(name = "fileNamePatterns", description = "Glob patterns. Accepts either an array (e.g. [\"*.java\", \"pom.xml\"]) or a string (e.g. \"*.java, pom.xml\"). If omitted, defaults to '*'", required = false) Object fileNamePatterns,
            @ToolParam(name = "maxResults", description = "Maximum number of results to return (default: 200)", required = false) String maxResults)
    {
        String[] patterns = normalizeFileNamePatterns(fileNamePatterns);
        int limit = Optional.ofNullable(maxResults).map(Integer::parseInt).orElse(0);
        return resourceService.findFiles(patterns, limit).toString();
    }

    @Tool(name = "searchAndReplace", description = "Search and replace across multiple files in the workspace using Eclipse's text search engine.", type = "object")
    public String searchAndReplace(
            @ToolParam(name = "containingText", description = "Plain text to find (not regex)", required = true) String containingText,
            @ToolParam(name = "replacementText", description = "Replacement text (can be empty)", required = true) String replacementText,
            @ToolParam(name = "fileNamePatterns", description = "Optional file name patterns. Accepts either an array (e.g. [\"*.java\", \"*.xml\"]) or a string (e.g. \"*.java,*.xml\"). If omitted, all files are searched.", required = false) Object fileNamePatterns)
    {
        String[] patterns = normalizeFileNamePatterns(fileNamePatterns);
        return searchService.searchAndReplace(containingText, replacementText, patterns).toString();
    }

    @Tool(name = "getMarkdownOutline", description = "Returns the heading structure (table of contents) of a Markdown file with line numbers and section sizes. Use this to understand a large Markdown document before fetching specific sections with getMarkdownSection.", type = "object")
    public String getMarkdownOutline(
            @ToolParam(name = "projectName", description = "The name of the project containing the Markdown file", required = true) String projectName,
            @ToolParam(name = "resourcePath", description = "The path to the Markdown file relative to the project root (e.g., 'docs/README.md')", required = true) String resourcePath)
    {
        return markdownService.getOutline(projectName, resourcePath);
    }

    @Tool(name = "getMarkdownSection", description = "Reads a specific section from a Markdown file by heading name or index. Returns the section content with line numbers. Use getMarkdownOutline first to see available headings.", type = "object")
    public String getMarkdownSection(
            @ToolParam(name = "projectName", description = "The name of the project containing the Markdown file", required = true) String projectName,
            @ToolParam(name = "resourcePath", description = "The path to the Markdown file relative to the project root", required = true) String resourcePath,
            @ToolParam(name = "heading", description = "The heading to find â either a 1-based index from the outline, or a text substring to match (case-insensitive)", required = true) String heading,
            @ToolParam(name = "includeSubsections", description = "If 'true', includes all subsections under the matched heading. If 'false', returns only the content up to the next heading of any level. Default: true", required = false) String includeSubsections)
    {
        boolean includeSubs = Optional.ofNullable(includeSubsections).map(Boolean::parseBoolean).orElse(true);
        return markdownService.getSection(projectName, resourcePath, heading, includeSubs);
    }

    private static String[] normalizeFileNamePatterns(Object fileNamePatterns)
    {
        if (fileNamePatterns == null)
        {
            return new String[0];
        }

        if (fileNamePatterns instanceof String[])
        {
            return (String[]) fileNamePatterns;
        }

        if (fileNamePatterns instanceof List)
        {
            @SuppressWarnings("rawtypes")
            List list = (List) fileNamePatterns;
            List<String> out = new ArrayList<>();
            for (Object o : list)
            {
                if (o != null)
                {
                    String s = String.valueOf(o).trim();
                    if (!s.isEmpty())
                    {
                        out.add(s);
                    }
                }
            }
            return out.toArray(String[]::new);
        }

        if (fileNamePatterns instanceof String)
        {
            String s = ((String) fileNamePatterns).trim();
            if (s.isEmpty())
            {
                return new String[0];
            }

            // allow comma-separated patterns: "*.java, *.xml, test.http"
            return s.split("\\s*,\\s*");
        }

        // Fallback: accept any scalar and treat it as a single pattern
        String s = String.valueOf(fileNamePatterns).trim();
        return s.isEmpty() ? new String[0] : new String[] { s };
    }
}
