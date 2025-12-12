
package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import java.util.Optional;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;
import com.github.gradusnikov.eclipse.assistai.mcp.services.CodeEditingService;

import jakarta.inject.Inject;

@Creatable
@McpServer(name = "eclipse-coder")
public class EclipseCodeEditingMcpServer 
{
    @Inject
    private CodeEditingService codeEditingService;

    @Tool(name="createFile", description="Create and open a new file in a specified project. Ensure the file doesn't already exist.", type="object")
    public String createFile(
        @ToolParam(name="projectName", description="The name of the project where the file should be created", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the file relative to the project root. Do not include project name!", required=true) String filePath,
        @ToolParam(name="content", description="The content to write to the file", required=true) String content) 
    {
        return codeEditingService.createFileAndOpen(projectName, filePath, content);
    }

    @Tool(name="insertIntoFile", description="Insert content into a file at a specified line position, using 1-based line indexing. The new content will be inserted BEFORE the specified line, and existing content at that line and below will be shifted down.", type="object")
    public String insertIntoFile(
        @ToolParam(name = "projectName", description = "The name of the project containing the file", required = true) String projectName,
        @ToolParam(name = "filePath", description = "The path to the file relative to the project root. Do not include project name!", required = true) String filePath,
        @ToolParam(name = "content", description = "The content to insert into the file", required = true) String content,
        @ToolParam(name = "line", description = "The line number before which to insert the text (1-based index). Existing content at this line and below will be shifted down. Use line=1 to insert at the beginning of the file.", required = false) String line) 
    {
        int lineNum = Optional.ofNullable(line).map(Integer::parseInt).orElse(0);
        return codeEditingService.insertIntoFile(projectName, filePath, content, lineNum);
    }

    /**
     * LLMs have problems with correctly using replace lines, in contrary to
     * replace string.
     * <p>
     * This is their argumentation:
     * <br>
     * <quote> When using replaceString, I only
     * needed to identify the exact string to replace and provide a new string.
     * The tool handled the replacement precisely, regardless of the internal
     * structure of the file.
     * 
     * With replaceLines, I encountered several issues:
     * 
     * 1. Line counting error: In my first attempt, I didn't correctly account
     * for all the lines in the original code block, which led to a malformed
     * replacement where some closing braces were duplicated.
     * 
     * 2. Boundary identification: I needed to be very precise about which lines
     * to include in the replacement. With replaceString, the tool automatically
     * finds the exact string boundaries, but with replaceLines, I had to
     * manually specify start and end lines.
     * 
     * 3. Content integrity: When replacing lines, I needed to ensure that the
     * replacement content maintained the correct structure of the code,
     * including proper indentation and braces. I accidentally introduced syntax
     * errors by not properly closing the array declaration.
     * 
     * The fundamental difference is that replaceString operates on exact string
     * matching (regardless of line boundaries), while replaceLines operates on
     * a line-by-line basis that requires precise line counting and boundary
     * identification.
     * 
     * This demonstrates an important lesson: replaceString is often safer for
     * replacing well-defined blocks of code because it ensures exact matching,
     * while replaceLines requires more careful handling of line numbers and
     * content structure. </quote>
     * <p>
     * Note: this is why this tool is disabled
     */
//    @Tool(name="replaceLines", description="Replace specific lines in a file with new content. Ensure accurate line range selection to avoid duplication or misplacement. Use 1-based line indexing. This function works by first deleting a code block between startLine and endLine, and then inserting new content at startLine. Important: carefully analyze the code structure before making the replacement. Be careful about identifying the exact content and boundaries.", type="object")
//    public String replaceLines(
//        @ToolParam(name="projectName", description="The name of the project containing the file", required=true) String projectName,
//        @ToolParam(name="filePath", description="The path to the file relative to the project root. Do not include project name!", required=true) String filePath,
//        @ToolParam(name="startLine", description="The line number to start replacement from (1-based index).", required=true) String startLine,
//        @ToolParam(name="endLine", description="The line number to end replacement at (inclusive, 1-based index).", required=true) String endLine,
//        @ToolParam(name="replacementContent", description="The new content to insert in place of the deleted lines", required=true) String replacementContent) 
//    {
//        int startLineNum = Integer.parseInt(startLine);
//        int endLineNum = Integer.parseInt(endLine);
//        return codeEditingService.replaceLines(projectName, filePath, replacementContent, startLineNum, endLineNum);
//    }

    @Tool(name="replaceString", description="Find and replace a specific string in a file, with optional line range for targeted replacement.", type="object")
    public String replaceString(
        @ToolParam(name="projectName", description="The name of the project containing the file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the file relative to the project root. Do not include project name!", required=true) String filePath,
        @ToolParam(name="oldString", description="The text to replace (must match exactly, including whitespace and indentation)", required=true) String oldString,
        @ToolParam(name="newString", description="The new text to insert in place of the old text", required=true) String newString,
        @ToolParam(name="startLine", description="Optional line number to start searching from (1-based index)", required=false) String startLine,
        @ToolParam(name="endLine", description="Optional line number to end searching at (1-based index)", required=false) String endLine) 
    {
        Integer startLineNum = Optional.ofNullable(startLine).map(Integer::parseInt).orElse(null);
        Integer endLineNum = Optional.ofNullable(endLine).map(Integer::parseInt).orElse(null);
        return codeEditingService.replaceStringInFile(projectName, filePath, oldString, newString, startLineNum, endLineNum);
    }

    @Tool(name="undoEdit", description="Undoes the last edit operation by restoring a file from its backup.", type="object")
    public String undoEdit(
        @ToolParam(name="projectName", description="The name of the project containing the file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the file relative to the project root. Do not include project name!", required=true) String filePath) 
    {
        return codeEditingService.undoEdit(projectName, filePath);
    }

    @Tool(name="createDirectories", description="Creates a directory structure (recursively) in the specified project.", type="object")
    public String createDirectories(
        @ToolParam(name="projectName", description="The name of the project where directories should be created", required=true) String projectName,
        @ToolParam(name="directoryPath", description="The path of directories to create, relative to the project root. Do not include project name!", required=true) String directoryPath) 
    {
        return codeEditingService.createDirectories(projectName, directoryPath);
    }
    
    @Tool(name="renameFile", description="Renames a file in the specified project.", type="object")
    public String renameFile(
        @ToolParam(name="projectName", description="The name of the project containing the file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the file relative to the project root. Do not include project name!", required=true) String filePath,
        @ToolParam(name="newFileName", description="The new name for the file", required=true) String newFileName) 
    {
        return codeEditingService.renameFile(projectName, filePath, newFileName);
    }

    @Tool(name="refactorRenameJavaType", description="Renames a Java class/interface/enum using Eclipse's refactoring mechanism. This updates the type name, file name, and ALL references throughout the workspace. Use this instead of renameFile for Java files to ensure all references are updated correctly.", type="object")
    public String refactorRenameJavaType(
        @ToolParam(name="projectName", description="The name of the project containing the Java file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the Java file relative to the project root (e.g., 'src/com/example/MyClass.java')", required=true) String filePath,
        @ToolParam(name="newTypeName", description="The new name for the Java type (without .java extension, e.g., 'NewClassName')", required=true) String newTypeName) 
    {
        return codeEditingService.refactorRenameJavaType(projectName, filePath, newTypeName);
    }

    @Tool(name="refactorMoveJavaType", description="Moves a Java class/interface/enum to a different package using Eclipse's refactoring mechanism. This updates the package declaration and ALL references throughout the workspace. The target package will be created if it doesn't exist.", type="object")
    public String refactorMoveJavaType(
        @ToolParam(name="projectName", description="The name of the project containing the Java file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the Java file relative to the project root (e.g., 'src/com/example/MyClass.java')", required=true) String filePath,
        @ToolParam(name="targetPackage", description="The fully qualified target package name (e.g., 'com.example.newpackage')", required=true) String targetPackage) 
    {
        return codeEditingService.refactorMoveJavaType(projectName, filePath, targetPackage);
    }

    @Tool(name="refactorRenamePackage", description="Renames a Java package using Eclipse's refactoring mechanism. This renames the package directory, updates all package declarations in contained files, and updates ALL references throughout the workspace.", type="object")
    public String refactorRenamePackage(
        @ToolParam(name="projectName", description="The name of the project containing the package", required=true) String projectName,
        @ToolParam(name="packageName", description="The current fully qualified package name (e.g., 'com.example.oldpackage')", required=true) String packageName,
        @ToolParam(name="newPackageName", description="The new package name - can be fully qualified (e.g., 'com.example.newpackage') or just the last segment to rename", required=true) String newPackageName) 
    {
        return codeEditingService.refactorRenamePackage(projectName, packageName, newPackageName);
    }

    @Tool(name="moveResource", description="Moves a file or folder to a different location within the project. For Java files, prefer using refactorMoveJavaType instead to ensure all references are updated.", type="object")
    public String moveResource(
        @ToolParam(name="projectName", description="The name of the project containing the resource", required=true) String projectName,
        @ToolParam(name="sourcePath", description="The path to the file or folder relative to the project root", required=true) String sourcePath,
        @ToolParam(name="targetPath", description="The target directory path relative to the project root where the resource should be moved to", required=true) String targetPath) 
    {
        return codeEditingService.moveResource(projectName, sourcePath, targetPath);
    }

    @Tool(name="organizeImports", description="Organizes imports in a Java file using Eclipse's organize imports mechanism. This removes unused imports, adds missing imports, and sorts them according to project settings. Equivalent to pressing Ctrl+Shift+O in Eclipse.", type="object")
    public String organizeImports(
        @ToolParam(name="projectName", description="The name of the project containing the Java file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the Java file relative to the project root (e.g., 'src/com/example/MyClass.java')", required=true) String filePath) 
    {
        return codeEditingService.organizeImports(projectName, filePath);
    }

    @Tool(name="organizeImportsInPackage", description="Organizes imports in all Java files within a package. This is useful for cleaning up imports across multiple files at once.", type="object")
    public String organizeImportsInPackage(
        @ToolParam(name="projectName", description="The name of the project containing the package", required=true) String projectName,
        @ToolParam(name="packageName", description="The fully qualified package name (e.g., 'com.example.mypackage')", required=true) String packageName) 
    {
        return codeEditingService.organizeImportsInPackage(projectName, packageName);
    }

    @Tool(name="deleteFile", description="Deletes a file from the specified project.", type="object")
    public String deleteFile(
        @ToolParam(name="projectName", description="The name of the project containing the file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the file relative to the project root. Do not include project name!", required=true) String filePath) 
    {
        return codeEditingService.deleteFile(projectName, filePath);
    }

    @Tool(name="replaceFileContent", description="Replaces the entire content of a file with new content.", type="object")
    public String replaceFileContent(
        @ToolParam(name="projectName", description="The name of the project containing the file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the file relative to the project root. Do not include project name!", required=true) String filePath,
        @ToolParam(name="content", description="The new content to write to the file", required=true) String content) 
    {
        return codeEditingService.replaceFileContent(projectName, filePath, content);
    }

    @Tool(name="deleteLinesInFile", description="Deletes a range of lines in a file, using 1-based line indexing.", type="object")
    public String deleteLinesInFile(
        @ToolParam(name="projectName", description="The name of the project containing the file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the file relative to the project root. Do not include project name!", required=true) String filePath,
        @ToolParam(name="startLine", description="The line number to start deletion from (1-based index)", required=true) String startLine,
        @ToolParam(name="endLine", description="The line number to end deletion at (inclusive, 1-based index)", required=true) String endLine) 
    {
        int startLineNum = Integer.parseInt(startLine);
        int endLineNum = Integer.parseInt(endLine);
        return codeEditingService.deleteLinesInFile(projectName, filePath, startLineNum, endLineNum);
    }
}
