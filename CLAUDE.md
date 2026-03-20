# AssistAI - Eclipse IDE Plugin

## Project Overview
AssistAI is an Eclipse IDE plugin that integrates LLM assistants (OpenAI, Anthropic, Gemini, Grok, DeepSeek, Groq) into the development environment. It also functions as an MCP Server, exposing Eclipse IDE tools via HTTP for external clients like Claude Code and Claude Desktop.

## Project Structure
- `plugins/com.github.gradusnikov.eclipse.plugin.assistai.main/` — main plugin (Java 21, Eclipse PDE)
  - `src/com/github/gradusnikov/eclipse/assistai/` — source root
    - `mcp/servers/` — MCP server endpoint classes (`@McpServer` annotated)
    - `mcp/services/` — service classes with business logic
    - `network/clients/` — API connector clients (OpenAI, Gemini, Grok, etc.)
- `tests/com.github.gradusnikov.eclipse.plugin.assistai.main.tests/` — test project

## MCP Tools Available
This project exposes Eclipse IDE capabilities as MCP tools. When working on code in Eclipse projects, prefer using these MCP tools over direct file edits:

- **eclipse-coder** — file editing, refactoring, patching, formatting
- **eclipse-ide** — code analysis, navigation, testing, building, search
- **eclipse-runner** — launch, debug, breakpoints, stepping

## Key Conventions
- Use `eclipse-coder__applyPatch` for multi-hunk edits (more reliable than replaceString)
- Use `eclipse-coder__replaceString` for single targeted replacements
- Always check `eclipse-ide__getCompilationErrors` after code changes
- Use `eclipse-ide__getProjectLayout` with `scopePath` and `maxDepth` for large projects
- MCP tool annotations: `@McpServer`, `@Tool`, `@ToolParam` in the `mcp.annotations` package
- Service classes in `mcp/services/` contain business logic; server classes in `mcp/servers/` are thin wrappers

## Build
Eclipse PDE project — build via Eclipse or `mvn clean verify` from the repo root.
