# AssistAI - Eclipse IDE Plugin

## Project Overview
AssistAI is an Eclipse IDE plugin that integrates LLM assistants (OpenAI, Anthropic, Gemini, Grok, DeepSeek, Groq) into the development environment. It also functions as an MCP Server, exposing Eclipse IDE tools via HTTP for external clients like Claude Code and Claude Desktop.

## Project Structure
- `plugins/com.github.gradusnikov.eclipse.plugin.assistai.main/` — main plugin (Java 21, Eclipse PDE)
  - `src/com/github/gradusnikov/eclipse/assistai/` — source root
    - `chat/` — chat session management
    - `commands/` — Eclipse command handlers
    - `completion/` — code completion integration
    - `handlers/` — event and request handlers
    - `jobs/` — Eclipse background jobs
    - `mcp/` — MCP (Model Context Protocol) implementation
      - `annotations/` — `@McpServer`, `@Tool`, `@ToolParam` annotations
      - `http/` — HTTP server infrastructure (auth, registry, preferences)
      - `local/` — in-memory MCP transport
      - `servers/` — MCP server endpoint classes (`@McpServer` annotated)
      - `services/` — service classes with business logic for MCP tools
    - `models/` — data models
    - `network/clients/` — API connector clients (OpenAI, OpenAI Responses, Anthropic, Gemini, Grok, DeepSeek, Groq)
    - `preferences/` — preference pages and initializers
    - `prompt/` — prompt templates and management
    - `resources/` — resource caching
    - `services/` — top-level services (separate from MCP services)
    - `tools/` — utility classes
    - `view/` — UI views and editors
- `tests/com.github.gradusnikov.eclipse.plugin.assistai.main.tests/` — test project

## MCP Tools Available
This project exposes Eclipse IDE capabilities as MCP tools. When working on code in Eclipse projects, prefer using these MCP tools over direct file edits.

The full reference — every tool, its parameters and the shape of what it returns — is
`plugins/com.github.gradusnikov.eclipse.plugin.assistai.main/docs/mcp-api.md`. It is
generated from the annotations by `tools/generate-mcp-api.sh`; do not edit it by hand,
and note that `McpApiDocPDETest` fails if it drifts from the code.

The servers:

- **eclipse-coder** — file editing, refactoring, patching, formatting
- **eclipse-ide** — code analysis, navigation, testing, building, search
- **eclipse-runner** — launch, debug, breakpoints, stepping
- **eclipse-context** — resource caching, file history, workspace context
- **eclipse-git** — git operations (status, diff, commit, branch, stash)
- **eclipse-pde** — PDE target platform management
- **duck-duck-search** — web search via DuckDuckGo
- **webpage-reader** — fetch and read web pages
- **memory** — thinking/memory tool for reasoning
- **time** — time zone conversion and current time

## Key Conventions
- Use `eclipse-coder__applyPatch` for multi-hunk edits (more reliable than replaceString)
- Use `eclipse-coder__replaceString` for single targeted replacements
- Always check `eclipse-ide__getCompilationErrors` after code changes
- Use `eclipse-ide__getProjectLayout` with `scopePath` and `maxDepth` for large projects
- MCP tool annotations: `@McpServer`, `@Tool`, `@ToolParam` in the `mcp/annotations` package
- Service classes in `mcp/services/` contain business logic; server classes in `mcp/servers/` are thin wrappers

## Testing

Test project: `com.github.gradusnikov.eclipse.plugin.assistai.main.tests`

### Plain JUnit (run with `runClassTests` / `runAllTests`)

- `com.github.gradusnikov.eclipse.assistai.chat.ConversationContextTest`
- `com.github.gradusnikov.eclipse.assistai.preferences.PreferenceInitializerAuthTokenTest`
- `com.github.gradusnikov.eclipse.assistai.tools.ContentTypeDetectorTest`
- `com.github.gradusnikov.eclipse.plugin.assistai.main.HtmlToMarkdownConverterTest`
- `com.github.gradusnikov.eclipse.plugin.assistai.mcp.operations.OperationOutputBufferTest`
- `com.github.gradusnikov.eclipse.plugin.assistai.mcp.servers.TimeMcpServerTest`
- `com.github.gradusnikov.eclipse.plugin.assistai.mcp.transport.SdkHttpStreamingTest`

### PDE harness tests (run with `runJUnitPluginTestClass` / `runJUnitPluginTests`)

Any test that uses Eclipse workspace, JDT, UI, platform, or OSGi runtime services must be named `*PDETest.java`. This lets test discovery route it to the PDE harness. Current examples include:

- `com.github.gradusnikov.eclipse.assistai.mcp.services.CodeAnalysisServicePDETest`
- `com.github.gradusnikov.eclipse.assistai.mcp.services.MavenServicePDETest`
- `com.github.gradusnikov.eclipse.assistai.tools.ResourceUtilitiesPDETest`
- `com.github.gradusnikov.eclipse.plugin.assistai.resources.ResourceUriSpacesPDETest`

## Adding or changing MCP tools

When you add, rename, or remove a `@Tool` method in any `@McpServer` class, the generated
API reference must be updated:

1. Edit `docs/mcp-api.md` in the main plugin:
   - Update the tool count in the **Servers** table for the affected server.
   - Add/remove/update the tool entry in the server's section (tools are alphabetically sorted).
   - Each entry has: heading (`### \`toolName\``), description, parameter table (if any), and `**Returns**` line.
2. Run `McpApiDocPDETest` (PDE harness) to verify the doc matches the annotations.
   If it fails, the committed doc doesn't match what the annotations generate.
   Alternatively, run `tools/generate-mcp-api.sh` (requires Maven + full build) to regenerate from scratch.

## Build
Eclipse PDE project — for a full build, run `./mvnw clean verify` from the repo root via the shell (do not use Eclipse MCP tools for full builds).

Two prerequisites, each of which fails the build in a way that does not name them:

- **Maven 3.9.x — use `./mvnw`, which pins 3.9.16.** Tycho 5.0.2 on Maven 3.8 dies with
  `No implementation for org.eclipse.tycho.targetplatform.TargetPlatformArtifactResolver
  was bound`; on Maven 3.10 it dies with `Invalid Collect Request: null`, because the
  resolver rejects the `lib/...jar` classifiers Tycho gives `Bundle-ClassPath` jars
  (eclipse-tycho/tycho#6399).
- **A registered `JavaSE-21` toolchain.** The parent pom sets `<useJDK>BREE</useJDK>`, so
  Tycho compiles each bundle against a JDK matching its declared execution environment
  rather than whatever Maven runs on. Without a `toolchains.xml` entry whose id is
  `JavaSE-21` the compile fails with `no toolchain of type 'jdk' with id 'JavaSE-21'
  found`, even when a JDK 21 is installed. Pass one with `mvn -t <file> ...`.

A full run is roughly 13 minutes, most of it the PDE test bundle.
