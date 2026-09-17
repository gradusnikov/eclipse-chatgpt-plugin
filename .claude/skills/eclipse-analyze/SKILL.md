---
name: eclipse-analyze
description: Analyze Java code using Eclipse JDT tools — type hierarchy, find references, call hierarchy, compilation errors, quick fixes, and import suggestions. Use this to understand code structure before making changes.
argument-hint: "[class or element to analyze]"
allowed-tools: mcp__eclipse-ide__getClassOutline, mcp__eclipse-ide__getMethodSource, mcp__eclipse-ide__getFilteredSource, mcp__eclipse-ide__getTypeHierarchy, mcp__eclipse-ide__findReferences, mcp__eclipse-ide__getMethodCallHierarchy, mcp__eclipse-ide__getCompilationErrors, mcp__eclipse-ide__getQuickFixes, mcp__eclipse-ide__getImportSuggestions, mcp__eclipse-ide__getJavaDoc, mcp__eclipse-ide__getSource, mcp__eclipse-ide__getProjectLayout, mcp__eclipse-ide__getProjectProperties, mcp__eclipse-ide__readProjectResource, mcp__eclipse-ide__listProjects, mcp__eclipse-ide__getCurrentlyOpenedFile, mcp__eclipse-ide__getEditorSelection, mcp__eclipse-ide__fileSearch, mcp__eclipse-ide__fileSearchRegExp, mcp__eclipse-ide__findFiles, mcp__eclipse-ide__getProjectDependencies, mcp__eclipse-ide__explainTypeResolution, mcp__eclipse-git__gitReadFile, mcp__eclipse-git__gitDiff
---

# Eclipse Code Analysis

Analyze Java code using Eclipse's JDT (Java Development Tools) infrastructure. These tools provide accurate, compiler-level analysis — not text-based guessing.

## Reading Code Efficiently

These tools are designed to minimize context window usage. Follow the **outline-first** pattern:

1. **getClassOutline** — Start here. Returns class declaration, field declarations, method signatures (no bodies), and inner types — all with line numbers and the first sentence of each member's Javadoc (an undocumented override reports its supertype's text, flagged `javadocInherited`). A 500-line class becomes ~30 lines. Provide `fullyQualifiedClassName`. Set `includeFields=false` to omit fields; `javadoc=FULL` renders whole comments, `javadoc=NONE` leaves them out.

2. **getMethodSource** — After seeing the outline, read only the methods you need. Accepts comma-separated `methodNames` (e.g., `"save,findById,delete"`). Returns source with line numbers. Set `includeJavadoc=false` to skip doc comments. Use `methodSignature` to disambiguate overloads (e.g., `"String"` matches methods with a String parameter).

3. **getFilteredSource** — Middle ground: returns the full source but collapses methods you don't need to their signatures. Provide `methodNames` to expand specific methods; all others are shown as one-line signatures with line ranges. Set `excludeImports=true` (the default) to collapse the import block. Line numbers always match the original file for accurate editing.

4. **getSource** — Full source dump. Use only for small classes or when you need everything. Prefer the tools above to save context.

5. **readProjectResource** — Read any file by path. Supports `startLine`/`endLine` for ranges, `showLineNumbers=true` for editing, and `excludeImports=true` to collapse Java import blocks.

### Choosing the Right Tool

| Scenario | Tool | Why |
|----------|------|-----|
| Understand class structure | `getClassOutline` | Signatures only, ~90% smaller |
| Read 1-3 specific methods | `getMethodSource` | Surgical reads with line numbers |
| Read class but focus on 2 methods | `getFilteredSource` | Full context, collapsed noise |
| Small class (<100 lines) | `getSource` | Overhead not worth optimizing |
| Referenced-library class | `getSource` | Uses attached source or decompiles the class |
| Diagnose which JAR/root won | `explainTypeResolution` | Shows the project-specific JDT classpath decision |
| Compare a historical file | `gitReadFile` | Reads a revision or staged index without checkout |
| Non-Java file or specific line range | `readProjectResource` | Works with any file type |

## Navigation & Understanding

- **getTypeHierarchy** — Show supertypes, implemented interfaces, and subtypes for a class.
- **findReferences** — Find all usages of a type, method, or field across the workspace. Essential before renaming or deleting.
- **getMethodCallHierarchy** — Show callers and callees of a method. Use `methodSignature` for overloaded methods.
- **getJavaDoc** — Rendered Javadoc (Markdown) of a type and each of its members, as the IDE hover shows it, inherited text included. `memberName` narrows it to one member's overloads; `javadoc=SUMMARY` gives first sentences only. `getTypeHierarchy`, `searchTypes`, `searchMethods` and `getPackageSummary` take the same `javadoc` parameter.
- **explainTypeResolution** — Explain source-vs-binary resolution in one project, including classpath root/entry, source attachment, class file, and whether `getSource` will decompile.
- **gitReadFile** — Read a project-relative text file at a Git revision or from `INDEX` without modifying the working tree.
- **gitDiff** — Limit diffs with project-relative `pathFilter` values and optionally set `ignoreWhitespace=true`.

## Error Analysis

- **getCompilationErrors** — Get all compilation errors/warnings. Filter by `projectName` and `severity` (ERROR, WARNING, ALL).
- **getQuickFixes** — Get available quick fixes for errors in a file. Optionally filter by `lineNumber`.
- **getImportSuggestions** — Find import candidates for unresolved types.

## Project Navigation

- **getProjectLayout** — File/folder structure. Use `scopePath` and `maxDepth` for large projects.
- **getProjectProperties** — Java version, source folders, output location, natures.
- **listProjects** — All workspace projects with their natures.
- **getProjectDependencies** — Maven dependencies for a project.

## Search

- **fileSearch** — Plain text search across workspace files. Filter with `fileNamePatterns`.
- **fileSearchRegExp** — Regex search across workspace files.
- **findFiles** — Find files by glob patterns (e.g., `*.xml`, `pom.xml`).

## Editor Context

- **getCurrentlyOpenedFile** — Get the file currently active in the Eclipse editor.
- **getEditorSelection** — Get the currently selected text/lines in the active editor.

## Recommended Workflow

1. Orient: `listProjects` or `getProjectLayout` to find what you need
2. Outline: `getClassOutline` to understand class structure
3. Focus: `getMethodSource` to read specific methods, or `getFilteredSource` for full-but-collapsed view
4. Analyze: `getTypeHierarchy`, `findReferences`, `getMethodCallHierarchy` as needed
5. After edits: `getCompilationErrors` to verify, then `getQuickFixes` or `getImportSuggestions` to resolve
