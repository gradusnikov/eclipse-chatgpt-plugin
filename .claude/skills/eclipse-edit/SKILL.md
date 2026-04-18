---
name: eclipse-edit
description: Edit files in Eclipse projects using MCP tools. Use this when modifying code in Eclipse workspace projects — it keeps Eclipse editors in sync, triggers incremental compilation, and provides undo via local history.
argument-hint: [description of changes]
allowed-tools: mcp__eclipse-coder__applyPatch, mcp__eclipse-coder__replaceString, mcp__eclipse-coder__insertIntoFile, mcp__eclipse-coder__createFile, mcp__eclipse-coder__deleteFile, mcp__eclipse-coder__deleteLinesInFile, mcp__eclipse-coder__replaceFileContent, mcp__eclipse-coder__formatFile, mcp__eclipse-coder__organizeImports, mcp__eclipse-coder__organizeImportsInPackage, mcp__eclipse-coder__undoEdit, mcp__eclipse-coder__renameFile, mcp__eclipse-coder__createDirectories, mcp__eclipse-coder__moveResource, mcp__eclipse-coder__refactorRenameJavaType, mcp__eclipse-coder__refactorMoveJavaType, mcp__eclipse-coder__refactorRenamePackage, mcp__eclipse-ide__getCompilationErrors, mcp__eclipse-ide__getProjectLayout, mcp__eclipse-ide__readProjectResource, mcp__eclipse-ide__getClassOutline, mcp__eclipse-ide__getMethodSource, mcp__eclipse-ide__getFilteredSource, mcp__eclipse-ide__getSource
---

# Eclipse Code Editing

Edit files in Eclipse workspace projects using the eclipse-coder MCP tools. These tools keep Eclipse editors synchronized, trigger incremental compilation, and maintain local history for undo.

## Available Editing Tools

- **applyPatch** — Apply a unified diff patch. Supports multiple hunks with fuzzy context matching. Best for multi-location edits. Set `showDialog=true` to let the user review via Eclipse's Apply Patch wizard.
- **replaceString** — Find and replace exact string. Good for single targeted changes. Optionally scoped to a line range.
- **insertIntoFile** — Insert content before a specific line (1-based).
- **createFile** — Create a new file and open it in the editor.
- **deleteFile** — Delete a file from the project.
- **deleteLinesInFile** — Delete a range of lines (1-based, inclusive).
- **replaceFileContent** — Replace entire file content.
- **formatFile** — Format a Java file using Eclipse's formatter (Ctrl+Shift+F).
- **organizeImports** — Organize imports in a Java file (Ctrl+Shift+O).
- **organizeImportsInPackage** — Organize imports across all files in a package.
- **undoEdit** — Restore a file from Eclipse's local history.

## Refactoring Tools

For Java files, prefer refactoring tools over manual rename/move — they update all references:
- **refactorRenameJavaType** — Rename class/interface/enum with workspace-wide reference updates.
- **refactorMoveJavaType** — Move type to different package with reference updates.
- **refactorRenamePackage** — Rename package with all declaration and reference updates.
- **moveResource** — Move file/folder (no reference updates — use refactorMoveJavaType for Java).

## Workflow

1. **Read efficiently** — Don't dump entire files:
   - `getClassOutline` → understand class structure (~30 lines vs ~300)
   - `getMethodSource` → read only the methods you need to edit
   - `getFilteredSource` → full source with non-relevant methods collapsed
   - `readProjectResource` with `excludeImports=true` → skip import noise
   - Reserve `getSource` / full `readProjectResource` for small files
2. **Edit** using the appropriate tool (see above)
3. **Verify** with `getCompilationErrors` to check for problems
4. **Clean up** with `organizeImports` if new types were referenced, `formatFile` for conventions

## Tips

- For multi-hunk changes, prefer `applyPatch` over multiple `replaceString` calls
- `replaceString` requires exact whitespace matching — if it fails, try `applyPatch` instead
- All tools require `projectName` (Eclipse project name) and `filePath` (relative to project root, without project name)
- Use `getProjectLayout` with `scopePath` and `maxDepth` to navigate large projects
- Line numbers from `getClassOutline`, `getMethodSource`, and `getFilteredSource` are always accurate for `replaceString` line ranges and `applyPatch` hunks
