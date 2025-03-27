package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import java.util.Optional;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.mcp.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.ToolParam;
import com.github.gradusnikov.eclipse.assistai.mcp.services.CodeEditingService;

import jakarta.inject.Inject;

@Creatable
@McpServer(name = "eclipse-coder")
public class EclipseCodeEditingMcpServer 
{
	
    @Inject
    private CodeEditingService codeEditingService;

	@Tool(name="createFile", description="Creates a new file in the specified project, adds it to the project, and opens it in the editor. If the file exists returns an error. Make sure to check that the file does not exist before using this tool.", type="object")
	public String createFile(
	        @ToolParam(name="projectName", description="The name of the project where the file should be created", required=true) String projectName,
	        @ToolParam(name="filePath", description="The path to the file relative to the project root. Do not include project name!", required=true) String filePath,
	        @ToolParam(name="content", description="The content to write to the file", required=true) String content) 
	{
	    return codeEditingService.createFileAndOpen(projectName, filePath, content);
	}
	@Tool(name="insertIntoFile", description="Inserts content at a specific position in an existing file.", type="object")
	public String insertIntoFile(
			@ToolParam(name = "projectName", description = "The name of the project containing the file", required = true) String projectName,
			@ToolParam(name = "filePath", description = "The path to the file relative to the project root. Do not include project name!", required = true) String filePath,
			@ToolParam(name = "content", description = "The content to insert into the file", required = true) String content,
			@ToolParam(name = "line", description = "The line number after which to insert the text (0 for beginning of file)", required = false) String line) {
	    int lineNum = Optional.ofNullable(line).map(Integer::parseInt).orElse(0);
	    
	    return codeEditingService.insertIntoFile(projectName, filePath, content, lineNum);
	}
	

	@Tool(name="replaceLines", description="Replaces specified lines in a file with new content. Use this tool to replace code blocks, for example a new function, or new file content, or delete portions of the file by replacing with empty string.", type="object")
	public String replaceLines(
	        @ToolParam(name="projectName", description="The name of the project containing the file", required=true) String projectName,
	        @ToolParam(name="filePath", description="The path to the file relative to the project root. Do not include project name!", required=true) String filePath,
	        @ToolParam(name="startLine", description="The line number to start replacement from (0-based index, where 0 is the first line)", required=true) String startLine,
	        @ToolParam(name="endLine", description="The line number to end replacement at (inclusive, 0-based index)", required=true) String endLine,
	        @ToolParam(name="replacementContent", description="The new content to insert in place of the deleted lines", required=true) String replacementContent) 
	{
	    int startLineNum = Integer.parseInt(startLine);
	    int endLineNum = Integer.parseInt(endLine);
	    
	    // Step 1: Delete the code block between startLine and endLine
	    codeEditingService.replaceLines(projectName, filePath, startLineNum, endLineNum, "");
	    
	    // Step 2: Insert the new content at position startLine
	    return codeEditingService.insertIntoFile(projectName, filePath, replacementContent, startLineNum);
	}



	
	@Tool(name="replaceString", description="Replaces a specific string in a file with a new string, optionally within a specified line range. This is used for making precise edits.", type="object")
	public String replaceString(
	        @ToolParam(name="projectName", description="The name of the project containing the file", required=true) String projectName,
	        @ToolParam(name="filePath", description="The path to the file relative to the project root. Do not include project name!", required=true) String filePath,
	        @ToolParam(name="oldString", description="The text to replace (must match exactly, including whitespace and indentation)", required=true) String oldString,
	        @ToolParam(name="newString", description="The new text to insert in place of the old text", required=true) String newString,
	        @ToolParam(name="startLine", description="Optional line number to start searching from (0 for beginning of file)", required=false) String startLine,
	        @ToolParam(name="endLine", description="Optional line number to end searching at (0 for beginning of file)", required=false) String endLine) 
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
	
	

}
