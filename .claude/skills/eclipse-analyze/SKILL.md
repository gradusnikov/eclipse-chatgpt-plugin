---
name: eclipse-analyze
description: Analyze Java code using Eclipse JDT tools — type hierarchy, find references, call hierarchy, compilation errors, quick fixes, and import suggestions. Use this to understand code structure before making changes.
argument-hint: [class or element to analyze]
allowed-tools: mcp__eclipse-ide__getTypeHierarchy, mcp__eclipse-ide__findReferences, mcp__eclipse-ide__getMethodCallHierarchy, mcp__eclipse-ide__getCompilationErrors, mcp__eclipse-ide__getQuickFixes, mcp__eclipse-ide__getImportSuggestions, mcp__eclipse-ide__getJavaDoc, mcp__eclipse-ide__getSource, mcp__eclipse-ide__getProjectLayout, mcp__eclipse-ide__getProjectProperties, mcp__eclipse-ide__readProjectResource, mcp__eclipse-ide__listProjects, mcp__eclipse-ide__getCurrentlyOpenedFile, mcp__eclipse-ide__getEditorSelection, mcp__eclipse-ide__fileSearch, mcp__eclipse-ide__fileSearchRegExp, mcp__eclipse-ide__findFiles, mcp__eclipse-ide__getProjectDependencies
---

# Eclipse Code Analysis

Analyze Java code using Eclipse's JDT (Java Development Tools) infrastructure. These tools provide accurate, compiler-level analysis — not text-based guessing.

## Navigation & Understanding

- **getTypeHierarchy** — Show supertypes, implemented interfaces, and subtypes for a class. Use `fullyQualifiedClassName` (e.g., `com.example.MyClass`).
- **findReferences** — Find all usages of a type, method, or field across the workspace. Essential before renaming or deleting. Provide `fullyQualifiedClassName` and optionally `elementName` for a method/field.
- **getMethodCallHierarchy** — Show callers and callees of a method. Provide `fullyQualifiedClassName`, `methodName`, and optionally `methodSignature` for overloaded methods.
- **getJavaDoc** — Get JavaDoc for a compilation unit by fully qualified name.
- **getSource** — Get full source code for a class by fully qualified name.

## Error Analysis

- **getCompilationErrors** — Get all compilation errors/warnings. Filter by `projectName` and `severity` (ERROR, WARNING, ALL). Shows error context with surrounding code lines.
- **getQuickFixes** — Get available quick fixes for errors in a file. Provide `projectName`, `filePath`, and optionally `lineNumber` to focus on a specific error.
- **getImportSuggestions** — Find import candidates for unresolved types. Shows fully qualified names from the workspace that match each unresolved type.

## Project Navigation

- **getProjectLayout** — File/folder structure. Use `scopePath` (e.g., `src/main/java`) and `maxDepth` (e.g., `3`) for large projects.
- **getProjectProperties** — Java version, source folders, output location, natures.
- **listProjects** — All workspace projects with their natures.
- **getProjectDependencies** — Maven dependencies for a project.

## Search

- **fileSearch** — Plain text search across workspace files. Filter with `fileNamePatterns` (e.g., `*.java`).
- **fileSearchRegExp** — Regex search across workspace files.
- **findFiles** — Find files by glob patterns (e.g., `*.xml`, `pom.xml`).

## Editor Context

- **getCurrentlyOpenedFile** — Get the file currently active in the Eclipse editor.
- **getEditorSelection** — Get the currently selected text/lines in the active editor.
- **readProjectResource** — Read any file from a project by path.

## Typical Workflow

1. Use `listProjects` or `getProjectLayout` to orient yourself
2. Use `getSource` or `readProjectResource` to read the code
3. Use `getTypeHierarchy` to understand class relationships
4. Use `findReferences` before any rename/delete to assess impact
5. Use `getMethodCallHierarchy` to understand method usage patterns
6. After edits, use `getCompilationErrors` to check for problems
7. Use `getQuickFixes` or `getImportSuggestions` to resolve errors
