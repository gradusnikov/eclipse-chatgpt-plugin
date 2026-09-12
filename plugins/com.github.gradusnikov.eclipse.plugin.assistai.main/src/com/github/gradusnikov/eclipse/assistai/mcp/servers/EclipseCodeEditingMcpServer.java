
package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.core.resources.IResource;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gradusnikov.eclipse.assistai.mcp.StructuredToolResult;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;
import com.github.gradusnikov.eclipse.assistai.mcp.results.LineDelimiterPreference;
import com.github.gradusnikov.eclipse.assistai.mcp.results.LineDelimiterPreference;
import com.github.gradusnikov.eclipse.assistai.mcp.services.CodeEditingService;
import com.github.gradusnikov.eclipse.assistai.resources.ContentRange;
import com.github.gradusnikov.eclipse.assistai.resources.EditResult;

import com.github.gradusnikov.eclipse.assistai.resources.Occurrence;
import com.github.gradusnikov.eclipse.assistai.resources.TextEditRequest;

import jakarta.inject.Inject;

@Creatable
@McpServer(name = "eclipse-coder")
public class EclipseCodeEditingMcpServer 
{
    /** Only used to read the {@code edits} parameter, which arrives as a JSON string. */
    private static final ObjectMapper EDIT_MAPPER = new ObjectMapper();

    @Inject
    private CodeEditingService codeEditingService;

    @Tool(name="createFile", description="Create and open a new file in a specified project, creating any missing parent folders. Fails if the file already exists - use replaceFileContent to overwrite one.", type="object", outputType=EditResult.class)
    public EditResult createFile(
        @ToolParam(name="projectName", description="The name of the project where the file should be created", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the file relative to the project root. Do not include project name!", required=true) String filePath,
        @ToolParam(name="content", description="The content to write to the file", required=true) String content) 
    {
        return codeEditingService.createFileAndOpen(projectName, filePath, content);
    }

    @Tool(name="insertIntoFile", description="Insert content into a file at a specified line position, using 1-based line indexing. The new content will be inserted BEFORE the specified line, "
            + "and existing content at that line and below will be shifted down. A line beyond the end of the file is rejected with INVALID_RANGE rather than clamped. "
            + "Pass expectedModificationStamp from a previous read to reject the edit if the file changed since.", type="object",
            outputType=EditResult.class)
    public EditResult insertIntoFile(
        @ToolParam(name = "projectName", description = "The name of the project containing the file", required = true) String projectName,
        @ToolParam(name = "filePath", description = "The path to the file relative to the project root. Do not include project name!", required = true) String filePath,
        @ToolParam(name = "content", description = "The content to insert into the file", required = true) String content,
        @ToolParam(name = "line", description = "The line number before which to insert the text (1-based index). Existing content at this line and below will be shifted down. "
                + "Use line=1 to insert at the beginning of the file, or one past the last line to append. Default: 1", required = false) String line,
        @ToolParam(name="expectedModificationStamp", description="The modificationStamp reported by an earlier read or edit of this file. "
                + "When supplied, the edit is rejected with VERSION_CONFLICT if the file has changed since.", required=false) String expectedModificationStamp,
        @ToolParam(name="preview", description="If 'true', report what would change without modifying the file. Default: false", required=false) String preview)
    {
        // Default 1, not 0: 0 is not a line, and the previous default made every call
        // that omitted the parameter fail.
        int lineNum = Optional.ofNullable(line).map(Integer::parseInt).orElse(1);
        return codeEditingService.insertIntoFile(projectName, filePath, content, lineNum,
                parseModificationStamp(expectedModificationStamp), parseBoolean(preview));
    }

    @Tool(name="replaceString", description="Find and replace a specific string in a file, with optional line range for targeted replacement. "
            + "Fails with AMBIGUOUS_MATCH and lists the candidate ranges when the text occurs more than once, rather than silently "
            + "replacing every occurrence - pass occurrence to say which one you mean. "
            + "Pass expectedModificationStamp from a previous read to reject the edit if the file changed since.", type="object",
            outputType=EditResult.class)
    public EditResult replaceString(
        @ToolParam(name="projectName", description="The name of the project containing the file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the file relative to the project root. Do not include project name!", required=true) String filePath,
        @ToolParam(name="oldString", description="The text to replace (must match exactly, including whitespace and indentation)", required=true) String oldString,
        @ToolParam(name="newString", description="The new text to insert in place of the old text", required=true) String newString,
        @ToolParam(name="startLine", description="Optional line number to start searching from (1-based index)", required=false) String startLine,
        @ToolParam(name="endLine", description="Optional line number to end searching at (1-based index)", required=false) String endLine,
        @ToolParam(name="occurrence", description="Which match to replace when there is more than one: UNIQUE (default, fails if not exactly one), "
                + "FIRST, LAST, ALL, or INDEX with occurrenceIndex", required=false) String occurrence,
        @ToolParam(name="occurrenceIndex", description="The 1-based match to replace when occurrence=INDEX", required=false) String occurrenceIndex,
        @ToolParam(name="expectedModificationStamp", description="The modificationStamp reported by an earlier read or edit of this file. "
                + "When supplied, the edit is rejected with VERSION_CONFLICT if the file has changed since.", required=false) String expectedModificationStamp,
        @ToolParam(name="preview", description="If 'true', report what would change without modifying the file. Default: false", required=false) String preview)
    {
        Integer startLineNum = Optional.ofNullable(startLine).map(Integer::parseInt).orElse(null);
        Integer endLineNum = Optional.ofNullable(endLine).map(Integer::parseInt).orElse(null);
        Integer index = Optional.ofNullable(occurrenceIndex).map(Integer::parseInt).orElse(null);

        return codeEditingService.replaceString(
                projectName, filePath, oldString, newString, startLineNum, endLineNum,
                parseModificationStamp(expectedModificationStamp),
                Occurrence.parse(occurrence), index, parseBoolean(preview));
    }

    @Tool(name="applyTextEdits", description="Applies several replacements to one file as a single transaction: either all of them apply or none do. "
            + "Overlapping ranges are rejected. The file is written once, so the whole batch is one Local History entry and one undo point. "
            + "Prefer this over repeated replaceString calls when changing several places in the same file.", type="object",
            outputType=EditResult.class)
    public EditResult applyTextEdits(
        @ToolParam(name="projectName", description="The name of the project containing the file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the file relative to the project root. Do not include project name!", required=true) String filePath,
        @ToolParam(name="edits", description="A JSON array of edits, each "
                + "{\"startLine\":n,\"startColumn\":n,\"endLine\":n,\"endColumn\":n,\"replacement\":\"...\",\"expectedText\":\"...\"}. "
                + "Lines and columns are 1-based; endColumn is exclusive. expectedText is optional and, when given, must match the current "
                + "text of the range or the whole batch is rejected. Ranges refer to the file as it is now, not as it becomes after earlier "
                + "edits in the list - the platform shifts them for you.", required=true) String edits,
        @ToolParam(name="expectedModificationStamp", description="The modificationStamp reported by an earlier read or edit of this file. "
                + "When supplied, the batch is rejected with VERSION_CONFLICT if the file has changed since.", required=false) String expectedModificationStamp,
        @ToolParam(name="preview", description="If 'true', report the resulting diff without modifying the file. Default: false", required=false) String preview)
    {
        return codeEditingService.applyTextEdits(
                projectName, filePath, parseModificationStamp(expectedModificationStamp),
                parseEdits(edits), parseBoolean(preview));
    }

    /**
     * Parses the {@code edits} parameter. The tool layer passes every argument as a
     * String, so structured input arrives as JSON rather than as a typed list.
     */
    private static List<TextEditRequest> parseEdits(String edits)
    {
        try
        {
            JsonNode array = EDIT_MAPPER.readTree(edits);
            if (!array.isArray())
            {
                throw new IllegalArgumentException("Error: 'edits' must be a JSON array.");
            }
            List<TextEditRequest> requests = new ArrayList<>();
            for (JsonNode node : array)
            {
                ContentRange range = new ContentRange(
                        requiredInt(node, "startLine"),
                        node.has("startColumn") ? node.get("startColumn").asInt() : 1,
                        requiredInt(node, "endLine"),
                        node.has("endColumn") ? node.get("endColumn").asInt() : 1);
                String expectedText = node.hasNonNull("expectedText") ? node.get("expectedText").asText() : null;
                String replacement = node.hasNonNull("replacement") ? node.get("replacement").asText() : "";
                requests.add(new TextEditRequest(range, expectedText, replacement));
            }
            return requests;
        }
        catch (JsonProcessingException e)
        {
            throw new IllegalArgumentException("Error: 'edits' is not valid JSON: " + e.getOriginalMessage());
        }
    }

    private static int requiredInt(JsonNode node, String field)
    {
        if (!node.hasNonNull(field))
        {
            throw new IllegalArgumentException("Error: each edit needs a '" + field + "'.");
        }
        return node.get(field).asInt();
    }

    /** Absent means "do not check", which is what NULL_STAMP signals to the service. */
    private static long parseModificationStamp(String value)
    {
        if (value == null || value.isBlank())
        {
            return IResource.NULL_STAMP;
        }
        try
        {
            return Long.parseLong(value.trim());
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException("Error: expectedModificationStamp must be a number, was '" + value + "'.");
        }
    }

    private static boolean parseBoolean(String value)
    {
        return value != null && Boolean.parseBoolean(value.trim());
    }

    @Tool(name="undoEdit", description="Undoes the last edit to a file by restoring the newest state from Eclipse's Local History, and reports what was rolled back as a diff. Rejected with HISTORY_UNAVAILABLE when the file has no stored history.", type="object", outputType=EditResult.class)
    public EditResult undoEdit(
        @ToolParam(name="projectName", description="The name of the project containing the file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the file relative to the project root. Do not include project name!", required=true) String filePath) 
    {
        return codeEditingService.undoEdit(projectName, filePath);
    }

    @Tool(name="createDirectories", description="Creates a directory structure (recursively) in the specified project. Idempotent: a directory that already exists is reported with versionBefore equal to versionAfter.", type="object", outputType=EditResult.class)
    public EditResult createDirectories(
        @ToolParam(name="projectName", description="The name of the project where directories should be created", required=true) String projectName,
        @ToolParam(name="directoryPath", description="The path of directories to create, relative to the project root. Do not include project name!", required=true) String directoryPath) 
    {
        return codeEditingService.createDirectories(projectName, directoryPath);
    }
    
    @Tool(name="renameFile", description="Renames a file in the specified project. The result names the renamed file as projectName + filePath, and affectedResources lists the old path as DELETED beside the new one as MOVED. For Java types use refactorRenameJavaType instead: this does not update references.", type="object", outputType=EditResult.class)
    public EditResult renameFile(
        @ToolParam(name="projectName", description="The name of the project containing the file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the file relative to the project root. Do not include project name!", required=true) String filePath,
        @ToolParam(name="newFileName", description="The new name for the file", required=true) String newFileName) 
    {
        return codeEditingService.renameFile(projectName, filePath, newFileName);
    }

    @Tool(name="refactorRenameJavaType", longExecution=true, description="Renames a Java class/interface/enum using Eclipse's refactoring mechanism. This updates the type name, file name, and ALL references throughout the workspace. Use this instead of renameFile for Java files to ensure all references are updated correctly. The result names the renamed file, and affectedResources lists every file the refactoring rewrote - in any project - with the version each one now has, so there is no need to guess which files to re-read. A failed precondition is reported as REFACTORING_PRECONDITION_FAILED.", type="object", outputType=EditResult.class)
    public EditResult refactorRenameJavaType(
        @ToolParam(name="projectName", description="The name of the project containing the Java file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the Java file relative to the project root (e.g., 'src/com/example/MyClass.java')", required=true) String filePath,
        @ToolParam(name="newTypeName", description="The new name for the Java type (without .java extension, e.g., 'NewClassName')", required=true) String newTypeName) 
    {
        return codeEditingService.refactorRenameJavaType(projectName, filePath, newTypeName);
    }

    @Tool(name="refactorRenameJavaElement", longExecution=true, description="Renames the Java element at a position in a source file - a method, field, enum constant, local variable, parameter, type parameter or type - using the matching Eclipse rename refactoring, so every reference in the workspace follows. The position is selected the way the IDE selects it: put line and column on the identifier, either at its declaration or at any reference to it. Pass elementName to make sure the position lands on the element you mean; a mismatch is reported as VALIDATION_ERROR and nothing is renamed. A rename that Eclipse refuses (an invalid name, a clash) is reported as REFACTORING_PRECONDITION_FAILED. The result is addressed to the file as it stands afterwards - renaming a file's top-level type renames the file too - and affectedResources lists every file the refactoring rewrote, in any project, with the version each one now has. Use refactorRenameJavaType to rename a type by file and refactorRenamePackage for packages.", type="object", outputType=EditResult.class)
    public EditResult refactorRenameJavaElement(
        @ToolParam(name="projectName", description="The name of the project containing the Java file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the Java file relative to the project root (e.g., 'src/com/example/MyClass.java')", required=true) String filePath,
        @ToolParam(name="line", description="1-based line of the identifier to rename", required=true) int line,
        @ToolParam(name="column", description="1-based column of the identifier to rename; anywhere within the identifier works", required=true) int column,
        @ToolParam(name="newName", description="The new name for the element", required=true) String newName,
        @ToolParam(name="elementName", description="Optional: the current simple name of the element expected at that position, e.g. 'calculateTotal'. When the element found there has a different name the rename is refused.", required=false) String elementName,
        @ToolParam(name="updateReferences", description="Whether to rewrite references to the element as well. Default: true", required=false) Boolean updateReferences,
        @ToolParam(name="updateTextualOccurrences", description="Whether to also rewrite occurrences of the name in comments and string literals (types and fields only). Default: false", required=false) Boolean updateTextualOccurrences,
        @ToolParam(name="updateGettersAndSetters", description="When renaming a field, whether to rename its getter and setter with it. Default: false", required=false) Boolean updateGettersAndSetters)
    {
        return codeEditingService.refactorRenameJavaElement(projectName, filePath, line, column, newName, elementName,
                updateReferences == null || updateReferences,
                Boolean.TRUE.equals(updateTextualOccurrences),
                Boolean.TRUE.equals(updateGettersAndSetters));
    }

    @Tool(name="refactorExtractTypeToNewFile", longExecution=true, description="Extracts a nested Java class, interface, enum, or record into a new top-level Java file using Eclipse's Move Type to New File refactoring. The type name must be relative to the source compilation unit, for example 'Outer.Inner'. Eclipse validates the change and updates all required references. The result names the new file, and affectedResources lists it as CREATED beside the source file and every other file whose references changed, with the version each one now has. A failed precondition is reported as REFACTORING_PRECONDITION_FAILED.", type="object", outputType=EditResult.class)
    public EditResult refactorExtractTypeToNewFile(
        @ToolParam(name="projectName", description="The name of the project containing the Java file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the Java file relative to the project root (e.g., 'src/com/example/Outer.java')", required=true) String filePath,
        @ToolParam(name="nestedTypeName", description="The nested type to extract, relative to the compilation unit (e.g., 'Outer.Inner')", required=true) String nestedTypeName)
    {
        return codeEditingService.refactorExtractTypeToNewFile(projectName, filePath, nestedTypeName);
    }

    @Tool(name="refactorMoveJavaType", longExecution=true, description="Moves a Java class/interface/enum to a different package using Eclipse's refactoring mechanism, updating the package declaration and ALL references throughout the workspace. The package is looked for in the file's project and in every project on its build path: the one source folder that already holds it is used; several holding it are refused with an AMBIGUOUS_MATCH diagnostic that lists the candidate folders; and a package that exists nowhere is created beside the file, in its own source folder. targetProjectName and targetSourceFolder narrow that choice, and are the way to move a type into a project that does not yet have the package. The result names the moved file at its new location - possibly in another project - and affectedResources lists every file the refactoring rewrote with the version each one now has. A failed precondition is reported as REFACTORING_PRECONDITION_FAILED.", type="object", outputType=EditResult.class)
    public EditResult refactorMoveJavaType(
        @ToolParam(name="projectName", description="The name of the project containing the Java file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the Java file relative to the project root (e.g., 'src/com/example/MyClass.java')", required=true) String filePath,
        @ToolParam(name="targetPackage", description="The fully qualified target package name (e.g., 'com.example.newpackage')", required=true) String targetPackage,
        @ToolParam(name="targetProjectName", description="Optional project that should receive the file. Without it the package is looked for in the file's project and every project on its build path.", required=false) String targetProjectName,
        @ToolParam(name="targetSourceFolder", description="Optional project-relative source folder that should receive the file (e.g. 'src/main/java'), for when more than one folder could.", required=false) String targetSourceFolder)
    {
        return codeEditingService.refactorMoveJavaType(projectName, filePath, targetPackage, targetProjectName, targetSourceFolder);
    }

    @Tool(name="refactorRenamePackage", longExecution=true, description="Renames a Java package using Eclipse's refactoring mechanism. This renames the package directory, updates all package declarations in contained files, and updates ALL references throughout the workspace. The result names the renamed package folder, and affectedResources lists every file the refactoring rewrote - in any project - with the version each one now has. A failed precondition is reported as REFACTORING_PRECONDITION_FAILED.", type="object", outputType=EditResult.class)
    public EditResult refactorRenamePackage(
        @ToolParam(name="projectName", description="The name of the project containing the package", required=true) String projectName,
        @ToolParam(name="packageName", description="The current fully qualified package name (e.g., 'com.example.oldpackage')", required=true) String packageName,
        @ToolParam(name="newPackageName", description="The new package name - can be fully qualified (e.g., 'com.example.newpackage') or just the last segment to rename", required=true) String newPackageName) 
    {
        return codeEditingService.refactorRenamePackage(projectName, packageName, newPackageName);
    }

    @Tool(name="moveResource", description="Moves a file or folder to a different location within the project. The result names the destination, and affectedResources lists the source as DELETED beside the destination as MOVED. For Java files, prefer using refactorMoveJavaType instead to ensure all references are updated.", type="object", outputType=EditResult.class)
    public EditResult moveResource(
        @ToolParam(name="projectName", description="The name of the project containing the resource", required=true) String projectName,
        @ToolParam(name="sourcePath", description="The path to the file or folder relative to the project root", required=true) String sourcePath,
        @ToolParam(name="targetPath", description="The target directory path relative to the project root where the resource should be moved to", required=true) String targetPath) 
    {
        return codeEditingService.moveResource(projectName, sourcePath, targetPath);
    }

    @Tool(name="organizeImports", description="Cleans up existing imports in a Java file using Eclipse's organize imports mechanism: removes unused imports and sorts the remaining imports according to project settings. This tool does NOT add imports for unresolved types. To add a missing import, use eclipse-ide getImportSuggestions and then edit the file explicitly. The unifiedDiff shows what changed, and an empty edits list means nothing needed changing.", type="object", outputType=EditResult.class)
    public EditResult organizeImports(
        @ToolParam(name="projectName", description="The name of the project containing the Java file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the Java file relative to the project root (e.g., 'src/com/example/MyClass.java')", required=true) String filePath) 
    {
        return codeEditingService.organizeImports(projectName, filePath);
    }

    @Tool(name="organizeImportsInPackage", longExecution=true, description="Cleans up existing imports in all Java files within a package by removing unused imports and sorting the remaining imports. This tool does NOT add imports for unresolved types. The result names the package folder, and affectedResources lists only the files that actually changed, with the version each one now has. A file that could not be organized is one diagnostic naming it, and a package in which every file failed is REJECTED rather than reported as a success.", type="object", outputType=EditResult.class)
    public EditResult organizeImportsInPackage(
        @ToolParam(name="projectName", description="The name of the project containing the package", required=true) String projectName,
        @ToolParam(name="packageName", description="The fully qualified package name (e.g., 'com.example.mypackage')", required=true) String packageName) 
    {
        return codeEditingService.organizeImportsInPackage(projectName, packageName);
    }

    @Tool(name="deleteFile", description="Deletes a file from the specified project. The content stays recoverable from Eclipse's Local History, and undoHistoryTimestamp in the result identifies the state holding it.", type="object", outputType=EditResult.class)
    public EditResult deleteFile(
        @ToolParam(name="projectName", description="The name of the project containing the file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the file relative to the project root. Do not include project name!", required=true) String filePath) 
    {
        return codeEditingService.deleteFile(projectName, filePath);
    }

    @Tool(name="getLineDelimiterPreference", description="Reports the line delimiter Eclipse is configured to write in a project - "
            + "the same value the editor uses, resolved from the project setting, then the workspace setting, then the platform default. "
            + "source says which of the three supplied it, so a deliberate project-specific choice is distinguishable from an inherited one. "
            + "name is LF, CRLF or CR, which is easier to branch on than the escaped delimiter string.", type="object",
            outputType=LineDelimiterPreference.class)
    public LineDelimiterPreference getLineDelimiterPreference(
        @ToolParam(name="projectName", description="The name of the project. Omit to ask the workspace rather than a project.", required=false) String projectName)
    {
        return codeEditingService.getLineDelimiterPreference(projectName);
    }

    @Tool(name="normalizeLineDelimiters", description="Rewrites a file so every line ends with the delimiter Eclipse is configured to use, "
            + "leaving the text itself unchanged. Use this on a file with mixed line endings: applyPatch rejoins every line with a single "
            + "delimiter, so patching such a file rewrites the whole file and buries a small change in a whole-file diff. "
            + "A file that already matches the preference is left untouched and reported with an empty diff. "
            + "Pass expectedModificationStamp from a previous read to reject the edit if the file changed since.", type="object",
            outputType=EditResult.class)
    public EditResult normalizeLineDelimiters(
        @ToolParam(name="projectName", description="The name of the project containing the file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the file relative to the project root. Do not include project name!", required=true) String filePath,
        @ToolParam(name="expectedModificationStamp", description="The modificationStamp reported by an earlier read or edit of this file. "
                + "When supplied, the edit is rejected with VERSION_CONFLICT if the file has changed since.", required=false) String expectedModificationStamp,
        @ToolParam(name="preview", description="If 'true', report what would change without modifying the file. Default: false", required=false) String preview)
    {
        return codeEditingService.normalizeLineDelimiters(projectName, filePath,
                parseModificationStamp(expectedModificationStamp), parseBoolean(preview));
    }

    @Tool(name="replaceFileContent", description="Replaces the entire content of a file with new content. "
            + "Pass expectedModificationStamp from a previous read to reject the edit if the file changed since.", type="object",
            outputType=EditResult.class)
    public EditResult replaceFileContent(
        @ToolParam(name="projectName", description="The name of the project containing the file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the file relative to the project root. Do not include project name!", required=true) String filePath,
        @ToolParam(name="content", description="The new content to write to the file", required=true) String content,
        @ToolParam(name="expectedModificationStamp", description="The modificationStamp reported by an earlier read or edit of this file. "
                + "When supplied, the edit is rejected with VERSION_CONFLICT if the file has changed since.", required=false) String expectedModificationStamp,
        @ToolParam(name="preview", description="If 'true', report what would change without modifying the file. Default: false", required=false) String preview)
    {
        return codeEditingService.replaceFileContent(projectName, filePath, content,
                parseModificationStamp(expectedModificationStamp), parseBoolean(preview));
    }

    @Tool(name="deleteLinesInFile", description="Deletes a range of lines in a file, using 1-based line indexing. A range the file cannot satisfy is rejected with "
            + "INVALID_RANGE rather than clamped, so lines outside the range you named are never touched. "
            + "Pass expectedModificationStamp from a previous read to reject the edit if the file changed since.", type="object",
            outputType=EditResult.class)
    public EditResult deleteLinesInFile(
        @ToolParam(name="projectName", description="The name of the project containing the file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the file relative to the project root. Do not include project name!", required=true) String filePath,
        @ToolParam(name="startLine", description="The line number to start deletion from (1-based index)", required=true) String startLine,
        @ToolParam(name="endLine", description="The line number to end deletion at (inclusive, 1-based index)", required=true) String endLine,
        @ToolParam(name="expectedModificationStamp", description="The modificationStamp reported by an earlier read or edit of this file. "
                + "When supplied, the edit is rejected with VERSION_CONFLICT if the file has changed since.", required=false) String expectedModificationStamp,
        @ToolParam(name="preview", description="If 'true', report what would change without modifying the file. Default: false", required=false) String preview)
    {
        return codeEditingService.deleteLinesInFile(projectName, filePath,
                Integer.parseInt(startLine), Integer.parseInt(endLine),
                parseModificationStamp(expectedModificationStamp), parseBoolean(preview));
    }

    @Tool(name="applyPatch", description="Atomically applies a unified diff with one or more hunks to a workspace file. Validates all hunk context before writing, preserves the file's "
            + "line delimiter, and writes once so the whole patch is one Local History entry and one undo point. File headers are optional. A hunk whose context is not in the file "
            + "rejects the whole patch with TEXT_NOT_FOUND and writes nothing; a malformed patch is rejected with INVALID_RANGE. "
            + "Pass expectedModificationStamp from a previous read to reject the patch if the file changed since.", type="object",
            outputType=EditResult.class)
    public EditResult applyPatch(
        @ToolParam(name="projectName", description="The name of the project containing the file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the file relative to the project root. Do not include project name!", required=true) String filePath,
        @ToolParam(name="patch", description="The unified diff content to apply. Should contain @@ hunk headers and lines prefixed with ' ' (context), '-' (remove), or '+' (add). File headers (--- and +++) are optional.", required=true) String patch,
        @ToolParam(name="showDialog", description="If 'true', shows Eclipse's Apply Patch wizard dialog for user review instead of applying directly, and the result is a PREVIEW. Default is 'false'.", required=false) String showDialog,
        @ToolParam(name="expectedModificationStamp", description="The modificationStamp reported by an earlier read or edit of this file. "
                + "When supplied, the patch is rejected with VERSION_CONFLICT if the file has changed since.", required=false) String expectedModificationStamp,
        @ToolParam(name="preview", description="If 'true', report what the patch would change without modifying the file. Default: false", required=false) String preview)
    {
        return codeEditingService.applyPatch(projectName, filePath, patch, parseBoolean(showDialog),
                parseModificationStamp(expectedModificationStamp), parseBoolean(preview));
    }

    @Tool(name="formatFile", description="Formats an entire file using its registered Eclipse editor's formatter (equivalent to Ctrl/Cmd+Shift+F). Java files use JDT directly; formats such as XML, JSON, HTML, and SQL use the formatter contributed by the installed editor. The unifiedDiff shows exactly what the formatter touched.", type="object", outputType=EditResult.class)
    public EditResult formatFile(
        @ToolParam(name="projectName", description="The name of the project containing the file", required=true) String projectName,
        @ToolParam(name="filePath", description="The path to the file relative to the project root. Do not include project name!", required=true) String filePath)
    {
        return codeEditingService.formatFile(projectName, filePath);
    }
}
