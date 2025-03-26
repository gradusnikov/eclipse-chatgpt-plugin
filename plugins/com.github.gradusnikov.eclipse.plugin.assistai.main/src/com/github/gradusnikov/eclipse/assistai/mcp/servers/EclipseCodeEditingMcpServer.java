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

	@Tool(name="createFile", description="Creates a new file in the specified project, adds it to the project, and opens it in the editor.", type="object")
	public String createFile(
	        @ToolParam(name="projectName", description="The name of the project where the file should be created", required=true) String projectName,
	        @ToolParam(name="filePath", description="The path to the file relative to the project root", required=true) String filePath,
	        @ToolParam(name="content", description="The content to write to the file", required=true) String content) 
	{
	    return codeEditingService.createFileAndOpen(projectName, filePath, content);
	}

	@Tool(name="insertIntoFile", description="Inserts content at a specific position in an existing file.", type="object")
	public String insertIntoFile(
			@ToolParam(name = "projectName", description = "The name of the project containing the file", required = true) String projectName,
			@ToolParam(name = "filePath", description = "The path to the file relative to the project root", required = true) String filePath,
			@ToolParam(name = "content", description = "The content to insert into the file", required = true) String content,
			@ToolParam(name = "line", description = "The line number after which to insert the text (0 for beginning of file)", required = false) String line) {
	    int lineNum = Optional.ofNullable(line).map(Integer::parseInt).orElse(0);
	    
	    return codeEditingService.insertIntoFile(projectName, filePath, content, lineNum);
	}
	
	
	@Tool(name="replaceString", description="Replaces a specific string in a file with a new string, optionally within a specified line range. This is used for making precise edits.", type="object")
	public String replaceString(
	        @ToolParam(name="projectName", description="The name of the project containing the file", required=true) String projectName,
	        @ToolParam(name="filePath", description="The path to the file relative to the project root", required=true) String filePath,
	        @ToolParam(name="oldString", description="The text to replace (must match exactly, including whitespace and indentation)", required=true) String oldString,
	        @ToolParam(name="newString", description="The new text to insert in place of the old text", required=true) String newString,
	        @ToolParam(name="startLine", description="Optional line number to start searching from (0 for beginning of file)", required=false) String startLine,
	        @ToolParam(name="endLine", description="Optional line number to end searching at (0 for beginning of file)", required=false) String endLine) 
	{
	    Integer startLineNum = Optional.ofNullable(startLine).map(Integer::parseInt).orElse(null);
	    Integer endLineNum = Optional.ofNullable(endLine).map(Integer::parseInt).orElse(null);
	    
	    return codeEditingService.replaceStringInFile(projectName, filePath, oldString, newString, startLineNum, endLineNum);
	}
}
