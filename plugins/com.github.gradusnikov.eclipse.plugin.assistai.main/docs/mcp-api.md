# MCP API reference

The tools this plugin exposes over MCP, and the shape of what each returns.

**This file is generated.** It is produced from the `@McpServer`, `@Tool` and
`@ToolParam` annotations by `McpApiDoc`, over the server list in
`McpServerBuiltins`, and `McpApiDocPDETest` fails when it is out of date. Do not
edit it by hand - change the annotations and regenerate:

```
tools/generate-mcp-api.sh
```

Every tool argument is passed as a string, whatever the parameter means; a
required parameter is marked `*`. Tools marked *long* run asynchronously and
return an operation id to poll with `getOperationStatus`.

## Results

A result arrives twice on one response. `structuredContent` is the object a
client branches on, and matches the shape named under **Returns**. A text
block carries the same data for clients that read only text.

The text block is a *rendering* of that object, not a copy of its
serialization: a string spanning more than one line is lifted into a fenced
code block, and the value it came from becomes `<rendered above as ...>`
naming the block that now holds it. So a source body, a diff or a stack
trace arrives as lines rather than as one escaped string. No field is
dropped, and `structuredContent` itself is never altered.

An empty result is an empty list and a count - never null, and never a
sentence saying nothing was found. A field that can be absent is absent as
`null` rather than as a sentinel or an invented value, so the advertised
output schema admits null for everything except a list and a primitive.

A failure is reported in the result rather than as a protocol error:
a `status` a caller can branch on, and `diagnostics` carrying a coded
`DiagnosticCode` with a message.

## Servers

| Server | Tools |
|---|---|
| [duck-duck-search](#duck-duck-search) | 1 |
| [eclipse-coder](#eclipse-coder) | 22 |
| [eclipse-context](#eclipse-context) | 7 |
| [eclipse-git](#eclipse-git) | 29 |
| [eclipse-ide](#eclipse-ide) | 39 |
| [eclipse-pde](#eclipse-pde) | 6 |
| [eclipse-runner](#eclipse-runner) | 17 |
| [memory](#memory) | 2 |
| [time](#time) | 2 |
| [webpage-reader](#webpage-reader) | 1 |

## duck-duck-search

### `webSearch` *(long)*

Searches the web with DuckDuckGo. Returns totalResults and, for each hit, its title, absolute url and snippet, ranked as the engine ranked them. totalResults of 0 means the search matched nothing. The url of a hit is what webpage-reader takes to fetch the page.

| Parameter | | Description |
|---|---|---|
| `query` | \* | A search query |

**Returns** [`WebSearchResponse`](#websearchresponse)

## eclipse-coder

### `applyPatch`

Atomically applies a unified diff with one or more hunks to a workspace file. Validates all hunk context before writing, preserves the file's line delimiter, and writes once so the whole patch is one Local History entry and one undo point. File headers are optional. A hunk whose context is not in the file rejects the whole patch with TEXT_NOT_FOUND and writes nothing; a malformed patch is rejected with INVALID_RANGE. Pass expectedModificationStamp from a previous read to reject the patch if the file changed since.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the file |
| `filePath` | \* | The path to the file relative to the project root. Do not include project name! |
| `patch` | \* | The unified diff content to apply. Should contain @@ hunk headers and lines prefixed with ' ' (context), '-' (remove), or '+' (add). File headers (--- and +++) are optional. |
| `showDialog` |  | If 'true', shows Eclipse's Apply Patch wizard dialog for user review instead of applying directly, and the result is a PREVIEW. Default is 'false'. |
| `expectedModificationStamp` |  | The modificationStamp reported by an earlier read or edit of this file. When supplied, the patch is rejected with VERSION_CONFLICT if the file has changed since. |
| `preview` |  | If 'true', report what the patch would change without modifying the file. Default: false |

**Returns** [`EditResult`](#editresult)

### `applyTextEdits`

Applies several replacements to one file as a single transaction: either all of them apply or none do. Overlapping ranges are rejected. The file is written once, so the whole batch is one Local History entry and one undo point. Prefer this over repeated replaceString calls when changing several places in the same file.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the file |
| `filePath` | \* | The path to the file relative to the project root. Do not include project name! |
| `edits` | \* | A JSON array of edits, each {"startLine":n,"startColumn":n,"endLine":n,"endColumn":n,"replacement":"...","expectedText":"..."}. Lines and columns are 1-based; endColumn is exclusive. expectedText is optional and, when given, must match the current text of the range or the whole batch is rejected. Ranges refer to the file as it is now, not as it becomes after earlier edits in the list - the platform shifts them for you. |
| `expectedModificationStamp` |  | The modificationStamp reported by an earlier read or edit of this file. When supplied, the batch is rejected with VERSION_CONFLICT if the file has changed since. |
| `preview` |  | If 'true', report the resulting diff without modifying the file. Default: false |

**Returns** [`EditResult`](#editresult)

### `createDirectories`

Creates a directory structure (recursively) in the specified project. Idempotent: a directory that already exists is reported with versionBefore equal to versionAfter.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project where directories should be created |
| `directoryPath` | \* | The path of directories to create, relative to the project root. Do not include project name! |

**Returns** [`EditResult`](#editresult)

### `createFile`

Create and open a new file in a specified project, creating any missing parent folders. Fails if the file already exists - use replaceFileContent to overwrite one.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project where the file should be created |
| `filePath` | \* | The path to the file relative to the project root. Do not include project name! |
| `content` | \* | The content to write to the file |

**Returns** [`EditResult`](#editresult)

### `deleteFile`

Deletes a file from the specified project. The content stays recoverable from Eclipse's Local History, and undoHistoryTimestamp in the result identifies the state holding it.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the file |
| `filePath` | \* | The path to the file relative to the project root. Do not include project name! |

**Returns** [`EditResult`](#editresult)

### `deleteLinesInFile`

Deletes a range of lines in a file, using 1-based line indexing. A range the file cannot satisfy is rejected with INVALID_RANGE rather than clamped, so lines outside the range you named are never touched. Pass expectedModificationStamp from a previous read to reject the edit if the file changed since.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the file |
| `filePath` | \* | The path to the file relative to the project root. Do not include project name! |
| `startLine` | \* | The line number to start deletion from (1-based index) |
| `endLine` | \* | The line number to end deletion at (inclusive, 1-based index) |
| `expectedModificationStamp` |  | The modificationStamp reported by an earlier read or edit of this file. When supplied, the edit is rejected with VERSION_CONFLICT if the file has changed since. |
| `preview` |  | If 'true', report what would change without modifying the file. Default: false |

**Returns** [`EditResult`](#editresult)

### `formatFile`

Formats an entire file using its registered Eclipse editor's formatter (equivalent to Ctrl/Cmd+Shift+F). Java files use JDT directly; formats such as XML, JSON, HTML, and SQL use the formatter contributed by the installed editor. The unifiedDiff shows exactly what the formatter touched.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the file |
| `filePath` | \* | The path to the file relative to the project root. Do not include project name! |

**Returns** [`EditResult`](#editresult)

### `getLineDelimiterPreference`

Reports the line delimiter Eclipse is configured to write in a project - the same value the editor uses, resolved from the project setting, then the workspace setting, then the platform default. source says which of the three supplied it, so a deliberate project-specific choice is distinguishable from an inherited one. name is LF, CRLF or CR, which is easier to branch on than the escaped delimiter string.

| Parameter | | Description |
|---|---|---|
| `projectName` |  | The name of the project. Omit to ask the workspace rather than a project. |

**Returns** [`LineDelimiterPreference`](#linedelimiterpreference)

### `insertIntoFile`

Insert content into a file at a specified line position, using 1-based line indexing. The new content will be inserted BEFORE the specified line, and existing content at that line and below will be shifted down. A line beyond the end of the file is rejected with INVALID_RANGE rather than clamped. Pass expectedModificationStamp from a previous read to reject the edit if the file changed since.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the file |
| `filePath` | \* | The path to the file relative to the project root. Do not include project name! |
| `content` | \* | The content to insert into the file |
| `line` |  | The line number before which to insert the text (1-based index). Existing content at this line and below will be shifted down. Use line=1 to insert at the beginning of the file, or one past the last line to append. Default: 1 |
| `expectedModificationStamp` |  | The modificationStamp reported by an earlier read or edit of this file. When supplied, the edit is rejected with VERSION_CONFLICT if the file has changed since. |
| `preview` |  | If 'true', report what would change without modifying the file. Default: false |

**Returns** [`EditResult`](#editresult)

### `moveResource`

Moves a file or folder to a different location within the project. The result names the destination, and affectedResources lists the source as DELETED beside the destination as MOVED. For Java files, prefer using refactorMoveJavaType instead to ensure all references are updated.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the resource |
| `sourcePath` | \* | The path to the file or folder relative to the project root |
| `targetPath` | \* | The target directory path relative to the project root where the resource should be moved to |

**Returns** [`EditResult`](#editresult)

### `normalizeLineDelimiters`

Rewrites a file so every line ends with the delimiter Eclipse is configured to use, leaving the text itself unchanged. Use this on a file with mixed line endings: applyPatch rejoins every line with a single delimiter, so patching such a file rewrites the whole file and buries a small change in a whole-file diff. A file that already matches the preference is left untouched and reported with an empty diff. Pass expectedModificationStamp from a previous read to reject the edit if the file changed since.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the file |
| `filePath` | \* | The path to the file relative to the project root. Do not include project name! |
| `expectedModificationStamp` |  | The modificationStamp reported by an earlier read or edit of this file. When supplied, the edit is rejected with VERSION_CONFLICT if the file has changed since. |
| `preview` |  | If 'true', report what would change without modifying the file. Default: false |

**Returns** [`EditResult`](#editresult)

### `organizeImports`

Cleans up existing imports in a Java file using Eclipse's organize imports mechanism: removes unused imports and sorts the remaining imports according to project settings. This tool does NOT add imports for unresolved types. To add a missing import, use eclipse-ide getImportSuggestions and then edit the file explicitly. The unifiedDiff shows what changed, and an empty edits list means nothing needed changing.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the Java file |
| `filePath` | \* | The path to the Java file relative to the project root (e.g., 'src/com/example/MyClass.java') |

**Returns** [`EditResult`](#editresult)

### `organizeImportsInPackage` *(long)*

Cleans up existing imports in all Java files within a package by removing unused imports and sorting the remaining imports. This tool does NOT add imports for unresolved types. The result names the package folder, and affectedResources lists only the files that actually changed, with the version each one now has. A file that could not be organized is one diagnostic naming it, and a package in which every file failed is REJECTED rather than reported as a success.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the package |
| `packageName` | \* | The fully qualified package name (e.g., 'com.example.mypackage') |

**Returns** [`EditResult`](#editresult)

### `refactorExtractTypeToNewFile` *(long)*

Extracts a nested Java class, interface, enum, or record into a new top-level Java file using Eclipse's Move Type to New File refactoring. The type name must be relative to the source compilation unit, for example 'Outer.Inner'. Eclipse validates the change and updates all required references. The result names the new file, and affectedResources lists it as CREATED beside the source file and every other file whose references changed, with the version each one now has. A failed precondition is reported as REFACTORING_PRECONDITION_FAILED.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the Java file |
| `filePath` | \* | The path to the Java file relative to the project root (e.g., 'src/com/example/Outer.java') |
| `nestedTypeName` | \* | The nested type to extract, relative to the compilation unit (e.g., 'Outer.Inner') |

**Returns** [`EditResult`](#editresult)

### `refactorMoveJavaType` *(long)*

Moves a Java class/interface/enum to a different package using Eclipse's refactoring mechanism, updating the package declaration and ALL references throughout the workspace. The package is looked for in the file's project and in every project on its build path: the one source folder that already holds it is used; several holding it are refused with an AMBIGUOUS_MATCH diagnostic that lists the candidate folders; and a package that exists nowhere is created beside the file, in its own source folder. targetProjectName and targetSourceFolder narrow that choice, and are the way to move a type into a project that does not yet have the package. The result names the moved file at its new location - possibly in another project - and affectedResources lists every file the refactoring rewrote with the version each one now has. A failed precondition is reported as REFACTORING_PRECONDITION_FAILED.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the Java file |
| `filePath` | \* | The path to the Java file relative to the project root (e.g., 'src/com/example/MyClass.java') |
| `targetPackage` | \* | The fully qualified target package name (e.g., 'com.example.newpackage') |
| `targetProjectName` |  | Optional project that should receive the file. Without it the package is looked for in the file's project and every project on its build path. |
| `targetSourceFolder` |  | Optional project-relative source folder that should receive the file (e.g. 'src/main/java'), for when more than one folder could. |

**Returns** [`EditResult`](#editresult)

### `refactorRenameJavaElement` *(long)*

Renames the Java element at a position in a source file - a method, field, enum constant, local variable, parameter, type parameter or type - using the matching Eclipse rename refactoring, so every reference in the workspace follows. The position is selected the way the IDE selects it: put line and column on the identifier, either at its declaration or at any reference to it. Pass elementName to make sure the position lands on the element you mean; a mismatch is reported as VALIDATION_ERROR and nothing is renamed. A rename that Eclipse refuses (an invalid name, a clash) is reported as REFACTORING_PRECONDITION_FAILED. The result is addressed to the file as it stands afterwards - renaming a file's top-level type renames the file too - and affectedResources lists every file the refactoring rewrote, in any project, with the version each one now has. Use refactorRenameJavaType to rename a type by file and refactorRenamePackage for packages.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the Java file |
| `filePath` | \* | The path to the Java file relative to the project root (e.g., 'src/com/example/MyClass.java') |
| `line` | \* | 1-based line of the identifier to rename |
| `column` | \* | 1-based column of the identifier to rename; anywhere within the identifier works |
| `newName` | \* | The new name for the element |
| `elementName` |  | Optional: the current simple name of the element expected at that position, e.g. 'calculateTotal'. When the element found there has a different name the rename is refused. |
| `updateReferences` |  | Whether to rewrite references to the element as well. Default: true |
| `updateTextualOccurrences` |  | Whether to also rewrite occurrences of the name in comments and string literals (types and fields only). Default: false |
| `updateGettersAndSetters` |  | When renaming a field, whether to rename its getter and setter with it. Default: false |

**Returns** [`EditResult`](#editresult)

### `refactorRenameJavaType` *(long)*

Renames a Java class/interface/enum using Eclipse's refactoring mechanism. This updates the type name, file name, and ALL references throughout the workspace. Use this instead of renameFile for Java files to ensure all references are updated correctly. The result names the renamed file, and affectedResources lists every file the refactoring rewrote - in any project - with the version each one now has, so there is no need to guess which files to re-read. A failed precondition is reported as REFACTORING_PRECONDITION_FAILED.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the Java file |
| `filePath` | \* | The path to the Java file relative to the project root (e.g., 'src/com/example/MyClass.java') |
| `newTypeName` | \* | The new name for the Java type (without .java extension, e.g., 'NewClassName') |

**Returns** [`EditResult`](#editresult)

### `refactorRenamePackage` *(long)*

Renames a Java package using Eclipse's refactoring mechanism. This renames the package directory, updates all package declarations in contained files, and updates ALL references throughout the workspace. The result names the renamed package folder, and affectedResources lists every file the refactoring rewrote - in any project - with the version each one now has. A failed precondition is reported as REFACTORING_PRECONDITION_FAILED.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the package |
| `packageName` | \* | The current fully qualified package name (e.g., 'com.example.oldpackage') |
| `newPackageName` | \* | The new package name - can be fully qualified (e.g., 'com.example.newpackage') or just the last segment to rename |

**Returns** [`EditResult`](#editresult)

### `renameFile`

Renames a file in the specified project. The result names the renamed file as projectName + filePath, and affectedResources lists the old path as DELETED beside the new one as MOVED. For Java types use refactorRenameJavaType instead: this does not update references.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the file |
| `filePath` | \* | The path to the file relative to the project root. Do not include project name! |
| `newFileName` | \* | The new name for the file |

**Returns** [`EditResult`](#editresult)

### `replaceFileContent`

Replaces the entire content of a file with new content. Pass expectedModificationStamp from a previous read to reject the edit if the file changed since.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the file |
| `filePath` | \* | The path to the file relative to the project root. Do not include project name! |
| `content` | \* | The new content to write to the file |
| `expectedModificationStamp` |  | The modificationStamp reported by an earlier read or edit of this file. When supplied, the edit is rejected with VERSION_CONFLICT if the file has changed since. |
| `preview` |  | If 'true', report what would change without modifying the file. Default: false |

**Returns** [`EditResult`](#editresult)

### `replaceString`

Find and replace a specific string in a file, with optional line range for targeted replacement. Fails with AMBIGUOUS_MATCH and lists the candidate ranges when the text occurs more than once, rather than silently replacing every occurrence - pass occurrence to say which one you mean. Pass expectedModificationStamp from a previous read to reject the edit if the file changed since.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the file |
| `filePath` | \* | The path to the file relative to the project root. Do not include project name! |
| `oldString` | \* | The text to replace (must match exactly, including whitespace and indentation) |
| `newString` | \* | The new text to insert in place of the old text |
| `startLine` |  | Optional line number to start searching from (1-based index) |
| `endLine` |  | Optional line number to end searching at (1-based index) |
| `occurrence` |  | Which match to replace when there is more than one: UNIQUE (default, fails if not exactly one), FIRST, LAST, ALL, or INDEX with occurrenceIndex |
| `occurrenceIndex` |  | The 1-based match to replace when occurrence=INDEX |
| `expectedModificationStamp` |  | The modificationStamp reported by an earlier read or edit of this file. When supplied, the edit is rejected with VERSION_CONFLICT if the file has changed since. |
| `preview` |  | If 'true', report what would change without modifying the file. Default: false |

**Returns** [`EditResult`](#editresult)

### `undoEdit`

Undoes the last edit to a file by restoring the newest state from Eclipse's Local History, and reports what was rolled back as a diff. Rejected with HISTORY_UNAVAILABLE when the file has no stored history.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the file |
| `filePath` | \* | The path to the file relative to the project root. Do not include project name! |

**Returns** [`EditResult`](#editresult)

## eclipse-context

### `compareWithHistory`

Shows a unified diff between the current file content and a Local History version, with the line counts and both versions compared. Use getFileHistory to find the historyTimestamp.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project |
| `filePath` | \* | Path to the file relative to the project root |
| `historyTimestamp` | \* | The historyTimestamp to compare against, from getFileHistory |

**Returns** [`DiffResponse`](#diffresponse)

### `getCacheStats`

Gets resource cache statistics: the number of cached resources and the estimated tokens they occupy, each against the limit at which the cache starts evicting the least recently used entry. Use listCachedResources for what is actually in there.

**Returns** [`CacheStatsResponse`](#cachestatsresponse)

### `getCachedResource`

Gets the content of a specific cached resource by URI without re-reading from disk. Use listCachedResources first to see available URIs. Returns the cached version - fast, no I/O - and says whether it is still what the workspace holds.

| Parameter | | Description |
|---|---|---|
| `resourceUri` | \* | The URI of the cached resource (e.g. 'workspace:///ProjectName/src/File.java' or 'jdt:///com.example.MyClass') |

**Returns** [`ResourceReadResult`](#resourcereadresult)

### `getFileHistory`

Lists the Local History versions of a file maintained by Eclipse. Shows the historyTimestamp, date and size of each stored version. Eclipse saves file history on every modification through the IDE. Pass a historyTimestamp from this listing to the other history tools.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project |
| `filePath` | \* | Path to the file relative to the project root |
| `maxEntries` |  | Maximum number of history entries to show (default: 20) |

**Returns** [`FileHistoryResponse`](#filehistoryresponse)

### `getFileHistoryContent`

Gets the content of a specific Local History version of a file. Returns the exact stored content, the range it covers and the version that addresses it again. Use getFileHistory first to see the available historyTimestamp values.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project |
| `filePath` | \* | Path to the file relative to the project root |
| `historyTimestamp` | \* | The historyTimestamp of the version, from getFileHistory. Identifies the same content even after further saves, which a positional index does not. |

**Returns** [`ResourceReadResult`](#resourcereadresult)

### `listCachedResources`

Lists all resources currently cached in the Eclipse workspace context. Each entry gives the URI getCachedResource takes, the resource type, the project and project-relative filePath when it is a workspace file, when it was cached, its modificationStamp and an estimated token count. Use this to see what files, classes, and data the user has been working with.

**Returns** [`CachedResourcesResponse`](#cachedresourcesresponse)

### `restoreFileVersion`

Restores a file to a specific Local History version. The current content becomes a new history entry first, so the restore is itself undoable: the returned undoHistoryTimestamp addresses it. Use getFileHistory to find the historyTimestamp.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project |
| `filePath` | \* | Path to the file relative to the project root |
| `historyTimestamp` | \* | The historyTimestamp of the version to restore, from getFileHistory |

**Returns** [`EditResult`](#editresult)

## eclipse-git

### `gitAdd`

Stages files for the next commit. Paths are relative to the Eclipse project - the filePath gitStatus reports - and may be comma-separated; '.' stages every change under the project (new, modified and deleted files). Reports the files whose index entry actually changed, each naming its Eclipse projectName and project-relative filePath as well as the repository-relative repoPath. A pattern that matches no changed file is totalFiles=0 with an empty list - Git does not fail on it, so check the count rather than assuming the pattern matched.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `filePattern` | \* | Comma-separated files or folders relative to the Eclipse project (e.g. 'src/com/example/MyClass.java'), or '.' for the whole project |

**Returns** [`GitStageResponse`](#gitstageresponse)

### `gitBranch`

Lists the branches of the repository. Local branches are in 'branches', each with a 'current' flag for the checked-out one, and remote-tracking branches are in 'remoteBranches'. Branch 'name' is what gitCheckout, gitCreateBranch and gitDeleteBranch take.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `includeRemote` |  | If 'true', includes remote-tracking branches. Default: false |

**Returns** [`GitBranchResponse`](#gitbranchresponse)

### `gitCheckout`

Checks out a branch, switching the working tree to that branch. status is SWITCHED or BLOCKED: when local changes would be overwritten nothing is switched, blockingFiles names them (projectName, filePath, repoPath) and a CHECKOUT_CONFLICT diagnostic is attached. A checkout rewrites the whole repository, so refreshedProjects lists every Eclipse project that was refreshed, not only the one that was named.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `branchName` | \* | The branch name to checkout |

**Returns** [`GitCheckoutResponse`](#gitcheckoutresponse)

### `gitCherryPick`

Applies the changes of one or more existing commits to the checked-out branch as new commits. status APPLIED lists the created commits (createdCommits, in the shape gitLog uses); CONFLICTED means a commit did not apply cleanly - conflicting names the files, the commits before it are already on the branch, and the repository is mid-operation until they are resolved, staged and committed, or abandoned with gitResetToRevision HEAD mode HARD; BLOCKED means local changes (blockingFiles) would be overwritten and nothing was applied.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `commits` | \* | Comma-separated commit shas or refs to apply, oldest first |

**Returns** [`GitApplyCommitsResponse`](#gitapplycommitsresponse)

### `gitCommit`

Commits the currently staged changes with the given message. With all='true' every tracked file's modifications and deletions are staged first, repository-wide ('git commit -a'; new files still need gitAdd). With amend='true' the previous commit is replaced instead of a new one being added. Returns the new commit as sha, shortSha, author, authorEmail, authorTimeMillis, message and shortMessage - the same shape gitLog reports - so the sha is a field rather than a prefix of a sentence.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `message` | \* | The commit message |
| `amend` |  | If 'true', replaces the previous commit. Default: false |
| `all` |  | If 'true', stages all tracked modifications and deletions before committing. Default: false |

**Returns** [`GitCommitResponse`](#gitcommitresponse)

### `gitCreateBranch`

Creates a new branch. Does not switch to it - use gitCheckout to switch.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `branchName` | \* | Name of the new branch to create |
| `startPoint` |  | Optional start point (branch name, tag, or commit SHA). Defaults to HEAD. |

**Returns** `String`

### `gitDeleteBranch`

Deletes a branch. Cannot delete the currently checked-out branch. deleted says whether the branch is gone and deletedRefs lists the refs that were removed. A branch that is not fully merged is refused with deleted=false and a BRANCH_NOT_MERGED diagnostic; retry with force='true' to delete it anyway.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `branchName` | \* | Name of the branch to delete |
| `force` |  | If 'true', force-deletes even if the branch is not fully merged. Default: false |

**Returns** [`GitDeleteBranchResponse`](#gitdeletebranchresponse)

### `gitDeleteTag`

Deletes a local tag. Reports what was known about the tag before it was removed. A tag that does not exist is an error.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `tagName` | \* | The tag name |

**Returns** [`GitTagResponse`](#gittagresponse)

### `gitDiff`

Shows a unified diff. Without revisions it compares the index with the working tree, or with staged='true' HEAD with the index. With fromRevision and/or toRevision it compares two revisions, or a revision with the working tree when toRevision is omitted (fromRevision defaults to HEAD when only toRevision is given). Optionally limited to comma-separated project-relative files/directories, with whitespace changes ignored. The hunks are in unifiedDiff, which names paths from the repository root; the files list additionally resolves each of them to an Eclipse projectName and project-relative filePath that the reading and editing tools accept, with per-file addedLines/removedLines. identical=true means the two sides are the same, and baseRevision is null in a repository with no commits.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `staged` |  | If 'true', shows staged (cached) changes instead of unstaged. Ignored when a revision is given. Default: false |
| `pathFilter` |  | Optional comma-separated file or directory paths relative to the Eclipse project |
| `ignoreWhitespace` |  | If 'true', ignores whitespace when formatting hunks. Default: false |
| `fromRevision` |  | Optional older side of the comparison: branch, tag or commit (e.g. 'main', 'HEAD~3') |
| `toRevision` |  | Optional newer side of the comparison; the working tree when omitted |

**Returns** [`GitDiffResponse`](#gitdiffresponse)

### `gitDiscardChanges`

Discards uncommitted modifications of tracked files by restoring their working-tree content from the index ('git checkout -- <path>'). Staged changes and untracked files are left alone. Paths are relative to the Eclipse project and may be comma-separated; '.' covers the whole project. This cannot be undone, so gitStash first when in doubt. Reports the files that were actually restored, each naming its Eclipse projectName, project-relative filePath and repoPath; a pattern matching no changed tracked file is totalFiles=0.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `filePattern` | \* | Comma-separated files or folders relative to the Eclipse project, or '.' for the whole project |

**Returns** [`GitDiscardResponse`](#gitdiscardresponse)

### `gitFetch` *(long)*

Fetches from a remote without touching the working tree. status is UPDATED (updates lists every remote-tracking ref that moved, with oldSha/newSha), UP_TO_DATE, or FAILED with a REMOTE_OPERATION_FAILED diagnostic when the remote could not be reached or refused the credentials. HTTPS credentials come from EGit's secure store, the same place the IDE's own fetch uses; SSH uses the IDE's keys and agent.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `remote` |  | The remote name. Default: origin |
| `prune` |  | If 'true', removes remote-tracking refs the remote no longer has. Default: false |

**Returns** [`GitFetchResponse`](#gitfetchresponse)

### `gitLog`

Lists the most recent commits reachable from a revision - the current branch by default - optionally only those touching some paths. Each commit reports sha, shortSha, author, authorEmail, authorTimeMillis (epoch milliseconds), the full message and its first line. The truncated flag says whether the history goes further back than maxCount.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `maxCount` |  | Maximum number of commits to show (default: 20) |
| `revision` |  | Branch, tag or commit to walk back from. Default: HEAD |
| `pathFilter` |  | Optional comma-separated files or folders relative to the Eclipse project; only commits touching them are listed |

**Returns** [`GitLogResponse`](#gitlogresponse)

### `gitMerge`

Merges a branch, tag or commit into the checked-out branch. status is FAST_FORWARD, MERGED (commit carries the new merge commit), ALREADY_UP_TO_DATE, MERGED_NOT_COMMITTED (a squash: the result is staged, gitCommit it), CONFLICTED (conflict markers are in the working tree; conflicting names the files with projectName/filePath/repoPath and a MERGE_CONFLICT diagnostic is attached - resolve them, gitAdd them and gitCommit, or gitResetToRevision HEAD with mode HARD to abandon), BLOCKED (local changes named in blockingFiles would be overwritten; nothing was merged) or FAILED. Every Eclipse project mapped into the repository is refreshed afterwards.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `ref` | \* | The branch, tag or commit to merge into the current branch |
| `fastForward` |  | FF (fast-forward when possible, default), NO_FF (always create a merge commit) or FF_ONLY (refuse unless a fast-forward) |
| `squash` |  | If 'true', stages the merged result without committing it. Default: false |
| `message` |  | Optional message for the merge commit |

**Returns** [`GitMergeResponse`](#gitmergeresponse)

### `gitPull` *(long)*

Fetches and then merges - or with rebase='true' rebases onto - the upstream of the checked-out branch. status is FAST_FORWARD, MERGED, REBASED, ALREADY_UP_TO_DATE, CONFLICTED (conflicting names the files; the repository is mid-merge or mid-rebase - finish it with gitAdd and gitCommit or gitRebase CONTINUE, or abandon it), BLOCKED (local changes in blockingFiles would be overwritten; only the fetch happened) or FAILED. A branch with no configured upstream needs remoteBranch, or a gitPush with setUpstream='true' first. Every Eclipse project mapped into the repository is refreshed afterwards.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `remote` |  | The remote name. Default: the branch's configured remote, else origin |
| `remoteBranch` |  | The branch on the remote. Default: the branch's configured upstream |
| `rebase` |  | If 'true', rebases local commits onto the fetched ones instead of merging. Default: false |

**Returns** [`GitPullResponse`](#gitpullresponse)

### `gitPush` *(long)*

Pushes a local branch - the checked-out one by default - to a remote. status is PUSHED, UP_TO_DATE, REJECTED (the remote has commits the branch does not: gitPull first, or push again with force='true' to overwrite; a PUSH_REJECTED diagnostic says so) or FAILED (REMOTE_OPERATION_FAILED: unreachable, or credentials refused). updates reports each ref's outcome in Git's own words. setUpstream='true' makes the pushed branch the local branch's upstream, so later gitPull and gitPush calls need no arguments. HTTPS credentials come from EGit's secure store; SSH uses the IDE's keys and agent.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `remote` |  | The remote name. Default: origin |
| `branch` |  | The local branch to push. Default: the checked-out branch |
| `setUpstream` |  | If 'true', records the remote branch as the local branch's upstream ('git push -u'). Default: false |
| `force` |  | If 'true', overwrites the remote branch even when it has commits the local one does not. Default: false |

**Returns** [`GitPushResponse`](#gitpushresponse)

### `gitReadFile`

Reads a UTF-8 text file from a Git revision without changing the working tree. The path is relative to the Eclipse project. Use revision 'INDEX' to read the staged version; otherwise revision defaults to HEAD and may be a branch, tag, or commit.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `filePath` | \* | File path relative to the Eclipse project |
| `revision` |  | Git branch, tag, commit, or 'INDEX'. Default: HEAD |

**Returns** `String`

### `gitRebase`

Rebases the checked-out branch onto an upstream, or drives a rebase already in progress. operation BEGIN (default, needs upstream) starts it; on a conflict status is STOPPED, stoppedAt names the commit being replayed, conflicting names the files (projectName/filePath/repoPath) and the repository stays mid-rebase - resolve and gitAdd them, then call again with operation CONTINUE, or SKIP that commit, or ABORT to put the branch back. Other statuses: OK, UP_TO_DATE, FAST_FORWARD, ABORTED, NOTHING_TO_COMMIT (the replayed commit became empty: SKIP or ABORT), UNCOMMITTED_CHANGES and BLOCKED (local changes in blockingFiles stop it; commit or stash them first) and FAILED. Every Eclipse project mapped into the repository is refreshed afterwards.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `upstream` |  | The branch, tag or commit to rebase onto (e.g. 'main', 'origin/main'). Required for BEGIN, ignored otherwise |
| `operation` |  | BEGIN, CONTINUE, SKIP or ABORT. Default: BEGIN |

**Returns** [`GitRebaseResponse`](#gitrebaseresponse)

### `gitRemoteList`

Lists the remotes configured for the repository, each with its name - what gitFetch, gitPull and gitPush take - and its fetch and push URLs.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |

**Returns** [`GitRemoteListResponse`](#gitremotelistresponse)

### `gitReset`

Unstages files from the index (equivalent to 'git reset HEAD <file>'). Does not modify the working tree. Paths are relative to the Eclipse project and may be comma-separated; '.' unstages everything under the project. Reports the index entries that actually left the staged set, each naming its Eclipse projectName and project-relative filePath plus the repository-relative repoPath, with changeType being what the file had been staged as. A pattern matching nothing is totalFiles=0. To move the branch itself - undo a commit, discard everything - use gitResetToRevision.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `filePattern` | \* | Comma-separated files or folders relative to the Eclipse project, or '.' for the whole project |

**Returns** [`GitStageResponse`](#gitstageresponse)

### `gitResetToRevision`

Moves the checked-out branch to a revision ('git reset'). mode SOFT keeps the index and working tree, so the commits between become staged changes - the way to undo a commit while keeping its work ('HEAD~1'); MIXED (default) also resets the index; HARD also rewrites the working tree, which discards uncommitted work, and is how a conflicted merge, cherry-pick or revert is abandoned (HARD to HEAD). previousHead is the handle for undoing the reset. Every Eclipse project mapped into the repository is refreshed afterwards.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `revision` |  | Branch, tag or commit to move to (e.g. 'HEAD~1', 'origin/main', a sha). Default: HEAD |
| `mode` |  | SOFT, MIXED or HARD. Default: MIXED |

**Returns** [`GitResetResponse`](#gitresetresponse)

### `gitRevert`

Undoes the changes of one or more existing commits with new commits that apply the reverse patch, leaving history intact. status APPLIED lists the created commits; CONFLICTED means a reverse patch did not apply cleanly - conflicting names the files and the repository is mid-operation until they are resolved, staged and committed, or abandoned with gitResetToRevision HEAD mode HARD; BLOCKED means local changes (blockingFiles) would be overwritten and nothing was applied.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `commits` | \* | Comma-separated commit shas or refs to undo |

**Returns** [`GitApplyCommitsResponse`](#gitapplycommitsresponse)

### `gitShow`

Shows one commit and the change it introduced against its first parent - what 'git show' prints. The commit has the shape gitLog uses and the change the shape gitDiff uses: per-file entries resolved to an Eclipse projectName and project-relative filePath with addedLines/removedLines, plus the unifiedDiff with repository-relative paths. parentShas is empty for a root commit and has two entries for a merge commit.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `revision` |  | Branch, tag or commit sha. Default: HEAD |
| `pathFilter` |  | Optional comma-separated files or folders relative to the Eclipse project |

**Returns** [`GitShowResponse`](#gitshowresponse)

### `gitStagePatch`

Stages specific changes from a unified diff patch into the index without modifying the working tree. Use this to stage partial file changes for selective commits. The patch must be in standard unified diff format with file headers (--- a/path and +++ b/path) and @@ hunk headers. IMPORTANT: patch paths are relative to the REPOSITORY root, not to the Eclipse project - unlike gitDiff, gitReadFile and the editing tools, which take project-relative paths. The two differ whenever the project does not sit at the repository root; gitStatus reports both forms as filePath and repoPath, and the unifiedDiff of gitDiff already uses the repository form. status is STAGED or FAILED, files lists what actually reached the index, and workingTreePreserved says whether the uncommitted content of every touched file was put back.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `patch` | \* | A unified diff patch string to stage. Must include file headers (--- a/path, +++ b/path) and @@ hunk headers. |

**Returns** [`GitStagePatchResponse`](#gitstagepatchresponse)

### `gitStash`

Stashes the current working directory and index changes, reverting the working tree to HEAD. stashed=false with a null stash means the working tree was already clean - an outcome, not a failure. When something was stashed, stash carries its index, its stash@{n} ref, the commit sha it is stored as and its message, the same shape gitStashList reports.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `message` |  | Optional message to describe the stash |

**Returns** [`GitStashResponse`](#gitstashresponse)

### `gitStashList`

Lists the stash entries, most recent first. Each entry reports its index, its stash@{n} ref, the commit sha it is stored as, and its message. An empty stash is totalStashes=0 with an empty list.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |

**Returns** [`GitStashListResponse`](#gitstashlistresponse)

### `gitStashPop`

Applies the most recent stash entry and, if that succeeded, removes it. status is APPLIED, CONFLICTED or NOTHING_TO_APPLY. On CONFLICTED the stash was kept (dropped=false), the working tree holds conflict markers, conflicting names the affected files and a MERGE_CONFLICT diagnostic is attached - do not treat it as done.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |

**Returns** [`GitStashPopResponse`](#gitstashpopresponse)

### `gitStatus`

Reports the working tree status of the Git repository associated with the project: separate staged, unstaged, untracked and conflicting lists, the current branch and its distance from its upstream. Every entry names its Eclipse projectName and a project-relative filePath, which the reading and editing tools take, plus the repository-relative repoPath the Git tools take. A clean working tree is reported as clean=true with empty lists.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name (use listProjects to find it) |

**Returns** [`GitStatusResponse`](#gitstatusresponse)

### `gitTag`

Creates a tag at a revision (HEAD by default): annotated when a message is given, lightweight otherwise. Reports the tag in the shape gitTagList uses.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |
| `tagName` | \* | The tag name (e.g. 'v1.2.0') |
| `message` |  | Optional message; given, the tag is annotated |
| `revision` |  | Branch, tag or commit to tag. Default: HEAD |

**Returns** [`GitTagResponse`](#gittagresponse)

### `gitTagList`

Lists the repository's tags. Each reports name (what every revision parameter and gitDeleteTag take), fullName, sha, targetSha (the commit it points at), whether it is annotated, and the annotated tag's message.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name |

**Returns** [`GitTagListResponse`](#gittaglistresponse)

## eclipse-ide

### `executeQuickFix` *(long)*

Applies one quick fix proposal to a compilation problem. Use getCompilationErrors first for the markerId and the proposal index. status is APPLIED, MARKER_NOT_FOUND (the id is stale - re-run getCompilationErrors), NO_PROPOSALS, INVALID_PROPOSAL_INDEX (pick from availableProposals) or APPLY_FAILED. On APPLIED, markerResolved says whether the problem actually went away.

| Parameter | | Description |
|---|---|---|
| `markerId` | \* | The Marker ID of the problem (from getCompilationErrors or getQuickFixes) |
| `proposalIndex` | \* | The 0-based index of the quick fix proposal to apply (from the quick fixes list) |

**Returns** [`QuickFixResponse`](#quickfixresponse)

### `explainTypeResolution`

Explains how a Java type resolves on one Eclipse project's classpath: which classpath root and entry supplied it, whether that root is a workspace folder or an external archive, whether source is attached, and where its class file is. sourceOrigin is the same enum getSource and readProjectResource report - WORKSPACE_SOURCE, ATTACHED_SOURCE or DECOMPILED_CLASS - and says what getSource would return. A type backed by a workspace file also reports projectName and a project-relative filePath the reading and editing tools take. status separates a type that is not on the classpath from a project name that does not exist.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The exact open Eclipse Java project name |
| `fullyQualifiedClassName` | \* | The fully qualified Java type name |

**Returns** [`TypeResolutionResponse`](#typeresolutionresponse)

### `fileSearch` *(long)*

Searches for a plain substring in workspace files using Eclipse's text search engine. Each match reports projectName, filePath and a 1-based lineNumber, which can be passed straight to the reading and editing tools.

| Parameter | | Description |
|---|---|---|
| `containingText` | \* | Text that must be contained in a line (plain substring, not regex) |
| `fileNamePatterns` |  | Optional comma-separated file name patterns (e.g. "*.java,*.xml"). If omitted, all files are searched. |
| `maxResults` |  | Maximum number of matches to return (default: 200). The response reports whether it was truncated. |

**Returns** [`SearchResponse`](#searchresponse)

### `fileSearchRegExp` *(long)*

Searches workspace files using a Java regular expression via Eclipse's text search engine. Each match reports projectName, filePath and a 1-based lineNumber.

| Parameter | | Description |
|---|---|---|
| `pattern` | \* | Java regular expression |
| `fileNamePatterns` |  | Optional comma-separated file name patterns (e.g. "*.java,*.xml"). If omitted, all files are searched. |
| `maxResults` |  | Maximum number of matches to return (default: 200). The response reports whether it was truncated. |

**Returns** [`SearchResponse`](#searchresponse)

### `findFiles`

Finds workspace files matching the given glob patterns. Each file reports projectName and a project-relative filePath, which is what the reading and editing tools take.

| Parameter | | Description |
|---|---|---|
| `fileNamePatterns` |  | Comma-separated glob patterns (e.g. "*.java, pom.xml"). If omitted, defaults to '*' |
| `maxResults` |  | Maximum number of results to return (default: 200) |

**Returns** [`FileListResponse`](#filelistresponse)

### `findReferences` *(long)*

Finds all references/usages of a Java type, method, or field across the entire workspace. Essential before renaming or deleting code elements: totalReferences of 0 means nothing uses it. Each reference reports projectName, filePath and a 1-based lineNumber.

| Parameter | | Description |
|---|---|---|
| `fullyQualifiedClassName` | \* | The fully qualified name of the class containing the element |
| `elementName` |  | Optional method or field name to search for. If omitted, searches for references to the class itself. |

**Returns** [`ReferencesResponse`](#referencesresponse)

### `findTestClasses`

Finds test classes and separates plain JUnit tests from PDE harness tests, which must follow the *PDETest naming convention. Flags likely PDE runtime usage in incorrectly named tests. Each class carries the project-relative path of its source file.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The exact Eclipse project name to search (use listProjects to find it) |

**Returns** [`TestClassesResponse`](#testclassesresponse)

### `formatCode`

Formats code according to the current Eclipse formatter settings.

| Parameter | | Description |
|---|---|---|
| `code` | \* | The code to be formatted |
| `projectName` |  | Optional project name to use project-specific formatter settings |

**Returns** `String`

### `getClassOutline`

Returns the outline of a Java class: its declaration plus fields, method signatures (no bodies) and inner types. Every entry carries a 1-based startLine and endLine, so one member can be read with readProjectResource(projectName, filePath, startLine, endLine) instead of fetching the whole file. Much cheaper than getSource; use this first, then getMethodSource or readProjectResource for the member you want. status reports TYPE_NOT_FOUND, NO_SOURCE or ACCESS_DENIED rather than an empty outline.

| Parameter | | Description |
|---|---|---|
| `fullyQualifiedClassName` | \* | A fully qualified class name (e.g. 'com.example.MyClass') |
| `includeFields` |  | Whether to include field declarations (default: true) |

**Returns** [`ClassOutlineResponse`](#classoutlineresponse)

### `getCompilationErrors`

Retrieves compilation errors and problems from the current workspace, a specific project, or one folder or file within it. Reports errorCount/warningCount for everything that matched, before any truncation, so 'are there errors?' is answerable even from a shortened listing. Each problem carries its markerId and quick-fix indices for executeQuickFix.

| Parameter | | Description |
|---|---|---|
| `projectName` |  | The name of the specific project to check (optional, leave empty for all projects) |
| `resourcePath` |  | Project-relative path of a folder or file to restrict the report to, e.g. 'src/com/example' or 'src/com/example/Foo.java' (optional, requires projectName) |
| `severity` |  | Filter by severity level: 'ERROR', 'WARNING', 'INFO' or 'ALL' (default) |
| `maxResults` |  | Maximum number of problems to return (default: 50) |

**Returns** [`CompilationProblemsResponse`](#compilationproblemsresponse)

### `getConsoleOutput`

Retrieves the recent output of Eclipse console(s). A console is read from its end, so returnedRange says which lines came back out of totalLines and truncated says whether maxLines left earlier ones out - raise maxLines to reach them, a console has no line-range read. totalConsoles says how many consoles exist, so you can tell the only console from one of several.

| Parameter | | Description |
|---|---|---|
| `consoleName` |  | Name of the specific console to retrieve (optional, leave empty for all or most recent console) |
| `maxLines` |  | Maximum number of lines to retrieve (default: 100) |
| `includeAllConsoles` |  | If 'true', includes output from all available consoles. Default: 'false' |

**Returns** [`ConsoleOutputResponse`](#consoleoutputresponse)

### `getCurrentlyOpenedFile`

Gets the file the user currently has open in the Eclipse editor, with its exact content. projectName and filePath are what the reading and editing tools take, and version.modificationStamp is the token an edit passes as expectedModificationStamp. status is FAILED when no workspace file is open - a state of the workbench, not an error.

**Returns** [`ResourceReadResult`](#resourcereadresult)

### `getEditorSelection`

Gets the text the user has selected in the active editor, as a range read of the open file. returnedRange gives the exact 1-based start and end line and column of the selection, and totalLines the size of the whole file. Nothing selected is an OK result with a zero-width range and empty content; status is FAILED only when no text editor is open.

**Returns** [`ResourceReadResult`](#resourcereadresult)

### `getEffectivePom` *(long)*

Gets the effective POM for a Maven project.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name (use listMavenProjects to find it); a Maven artifactId or groupId:artifactId is accepted when no project has that name |

**Returns** `String`

### `getFilteredSource`

Returns one class's source with the import block and the bodies of the methods you did not ask for left out. The content is exact - no line-number prefixes and no '// ... collapsed' comments - and every omission is a range in omittedRanges, so a caller that wants one back reads it with readProjectResource(projectName, filePath, startLine, endLine). status is PARTIAL whenever anything was omitted.

| Parameter | | Description |
|---|---|---|
| `fullyQualifiedClassName` | \* | A fully qualified class name (e.g. 'com.example.MyClass') |
| `excludeImports` |  | Whether to collapse the import block (default: true) |
| `methodNames` |  | Comma-separated method names to fully expand. Methods not listed are collapsed to signatures. If omitted, all methods are expanded. |

**Returns** [`ResourceReadResult`](#resourcereadresult)

### `getImportSuggestions` *(long)*

Finds import candidates for the unresolved types in a Java file. Each candidate is a bare fully qualified name, ready to use. totalUnresolvedTypes of 0 means the file has no unresolved names; totalCandidates of 0 means it has some but the workspace offers nothing for them. status separates PROJECT_NOT_FOUND from PROJECT_CLOSED, which is one openProject call away.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the file |
| `filePath` | \* | The path to the Java file relative to the project root |

**Returns** [`ImportSuggestionsResponse`](#importsuggestionsresponse)

### `getJavaDoc`

Gets the JavaDoc of a Java type as Markdown, with each of its members' declarations. A member type of class A in package x.y is named x.y.A.B, and a type name must match its compilation unit name to be found. status separates the three cases that used to share one sentence: OK, NO_JAVADOC (the type exists and is undocumented - read the source instead) and TYPE_NOT_FOUND (no open project resolves the name - fix it). projectName says which project answered.

| Parameter | | Description |
|---|---|---|
| `fullyQualifiedName` | \* | A fully qualified name of the compilation unit |

**Returns** [`JavaDocResponse`](#javadocresponse)

### `getMarkdownOutline`

Returns the heading structure (table of contents) of a Markdown file. Each heading carries its level, its 1-based index - which is what getMarkdownSection takes, and unambiguous where two sections share a title - and the line range of the section it opens. A file with no headings comes back as an empty list, not as a failure. Use this to understand a large Markdown document before fetching sections with getMarkdownSection.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the Markdown file |
| `resourcePath` | \* | The path to the Markdown file relative to the project root (e.g., 'docs/README.md') |

**Returns** [`MarkdownOutlineResponse`](#markdownoutlineresponse)

### `getMarkdownSection`

Reads one section of a Markdown file, addressed by heading text or by its 1-based index in the outline. Returns the exact section text with no line-number prefixes: returnedRange says which lines of the file it is, out of totalLines, and version.modificationStamp is the token an edit passes as expectedModificationStamp. Use getMarkdownOutline first to see available headings.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the Markdown file |
| `resourcePath` | \* | The path to the Markdown file relative to the project root |
| `heading` | \* | The heading to find â either a 1-based index from the outline, or a text substring to match (case-insensitive) |
| `includeSubsections` |  | If 'true', includes all subsections under the matched heading. If 'false', returns only the content up to the next heading of any level. Default: true |

**Returns** [`ResourceReadResult`](#resourcereadresult)

### `getMethodCallHierarchy` *(long)*

Finds the callers of a method, and what that method calls, to understand how it is used. Each node reports projectName, filePath and a 1-based lineNumber - the same location triple findReferences returns - so a caller can be opened without a follow-up search. depth is a field: 1 is a direct caller, 2 a caller of one of those. status distinguishes an unknown type from an unknown method.

| Parameter | | Description |
|---|---|---|
| `fullyQualifiedClassName` | \* | The fully qualified name of the class containing the method |
| `methodName` | \* | The name of the method to analyze |
| `methodSignature` |  | The signature of the method (optional, required if method is overloaded) |
| `maxDepth` |  | Maximum depth of the call hierarchy to retrieve (default: 3) |

**Returns** [`CallHierarchyResponse`](#callhierarchyresponse)

### `getMethodSource`

Returns the source of specific method(s) of one class. Accepts comma-separated method names to retrieve several in one call. Each method comes back as exact source with its own 1-based range, so its lines can be passed straight to the editing tools; a requested name that matches nothing is listed in notFound rather than mentioned in a comment. version.modificationStamp is the token an edit passes as expectedModificationStamp. Use after getClassOutline to read only the methods you need.

| Parameter | | Description |
|---|---|---|
| `fullyQualifiedClassName` | \* | A fully qualified class name (e.g. 'com.example.MyClass') |
| `methodNames` | \* | Comma-separated method names to retrieve (e.g. 'findById,save,delete') |
| `methodSignature` |  | Optional parameter type hint to disambiguate overloaded methods (e.g. 'String') |
| `includeJavadoc` |  | Whether to include Javadoc comments (default: true) |

**Returns** [`MethodSourceResponse`](#methodsourceresponse)

### `getPackageSummary` *(long)*

Returns a table-of-contents for a Java package: every type's name, kind (class/interface/enum/record), Javadoc first sentence, method count, field count, and implemented interfaces — all in one call. Use this after searchTypes or getWorkspaceOverview identifies a relevant package, to understand what the package contains without reading each file individually. The Javadoc summaries help you decide which types are relevant to the user's request. Follow up with getClassOutline or getMethodSource on specific types of interest.

| Parameter | | Description |
|---|---|---|
| `packageName` | \* | Fully qualified package name (e.g. 'com.example.payment', 'org.acme.auth.service') |
| `projectName` |  | Optional project name to narrow the search. Useful in multi-project workspaces. |

**Returns** [`PackageSummaryResponse`](#packagesummaryresponse)

### `getProjectDependencies` *(long)*

Lists the dependencies one project's pom declares. These come from the Maven project model - what the pom declares after inheritance from its parent - and not from the resolved transitive graph; for the fully resolved form use getEffectivePom. version is null when the pom does not state one here, which is the ordinary case for a dependency managed by a parent's dependencyManagement. scope is 'compile' when the pom omits it, the default Maven itself applies.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project name (use listMavenProjects to find it); a Maven artifactId or groupId:artifactId is accepted when no project has that name |

**Returns** [`MavenDependenciesResponse`](#mavendependenciesresponse)

### `getProjectLayout`

Gets the file and folder tree of a project as nested nodes. Every node carries the project-relative filePath the reading and editing tools take, and a folder reports childCount even when the walk stopped at it - so 'is there more under here?' is answerable. truncated says whether maxDepth cut the listing short, and excludedCount how many entries .aiignore kept out. For large projects use scopePath to limit to a subdirectory and/or maxDepth to limit tree depth.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project to analyze |
| `scopePath` |  | Optional path relative to the project root to limit the listing (e.g., 'src/main/java/com/example'). If omitted, shows the entire project. |
| `maxDepth` |  | Optional maximum depth of the directory tree to display (e.g., '3' for 3 levels deep). If omitted, shows all levels. |

**Returns** [`ProjectLayoutResponse`](#projectlayoutresponse)

### `getProjectProperties`

Gets how a project is configured: its nature ids, the build descriptors in its root, and for a Java project its compiler compliance level, output location and source folders. sourceFolders is the answer to 'where may a new class go?' and, like outputLocation, is project-relative - the form the reading and editing tools take. status separates a name that does not exist (fix the name; listProjects has the real ones) from a project that is closed (call openProject on its directory).

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project to analyze |

**Returns** [`ProjectPropertiesResponse`](#projectpropertiesresponse)

### `getSource`

Get source for a workspace or referenced-library class. Prefers original/attached source and decompiles binary classes when source is unavailable. origin says which of the three it is: only WORKSPACE_SOURCE can be edited, and version.modificationStamp is the token an edit passes as expectedModificationStamp.

| Parameter | | Description |
|---|---|---|
| `fullyQualifiedClassName` | \* | A fully qualified class name of the Java class |

**Returns** [`ResourceReadResult`](#resourcereadresult)

### `getTypeHierarchy` *(long)*

Retrieves the type hierarchy of a Java class or interface as three separate lists: superclasses (nearest first), implemented interfaces and subtypes. A type whose source is in the workspace also reports the projectName and project-relative filePath the reading and editing tools take; one from a JAR or the JRE reports neither. status is TYPE_NOT_FOUND when no open Java project knows the name.

| Parameter | | Description |
|---|---|---|
| `fullyQualifiedClassName` | \* | The fully qualified name of the class (e.g., 'com.example.MyClass') |

**Returns** [`TypeHierarchyResponse`](#typehierarchyresponse)

### `getWorkspaceOverview` *(long)*

Returns a high-level architectural map of the workspace: all projects, their source packages, and the type names in each package. Use this as the FIRST tool when orienting in an unfamiliar codebase or when you need to understand the overall project structure before making changes. Typical workflow: getWorkspaceOverview -> identify relevant packages -> getPackageSummary on those packages -> getClassOutline/getMethodSource on specific types. For large workspaces, use projectFilter to focus on specific projects.

| Parameter | | Description |
|---|---|---|
| `projectFilter` |  | Optional substring to filter projects by name (e.g. 'payment' shows only payment-related projects, 'service' shows service projects). Leave empty to see all projects. |
| `maxPackagesPerProject` |  | Maximum number of packages to show per project (default: 50). Lower this for large projects to get a quick overview. |

**Returns** [`WorkspaceOverviewResponse`](#workspaceoverviewresponse)

### `listMavenProjects`

Lists the Maven projects m2e knows about in the workspace. Each entry reports both names: the Eclipse projectName every other tool takes, and the groupId/artifactId/version/packaging a Maven command line takes. The two are frequently different strings.

**Returns** [`MavenProjectListResponse`](#mavenprojectlistresponse)

### `listProjects`

Lists the workspace projects. Each entry reports the projectName every other tool takes, whether the project is open (a closed one cannot be read, searched or built until openProject runs), its nature ids (org.eclipse.jdt.core.javanature for Java, org.eclipse.m2e.core.maven2Nature for Maven) and its filesystem location.

**Returns** [`ProjectListResponse`](#projectlistresponse)

### `openProject`

Opens or imports a directory into the Eclipse workspace as a project. If the directory contains a .project file it is imported as-is; if not, a description is created from the directory name. projectName is the name Eclipse assigned - taken from .project or from the directory name, and not necessarily the last segment of directoryPath - and it is the argument every other tool takes next. status says which of three things happened: IMPORTED (the workspace did not have it), OPENED (it had it, closed) or ALREADY_OPEN (nothing changed, which is an answer and not a failure).

| Parameter | | Description |
|---|---|---|
| `directoryPath` | \* | The absolute filesystem path to the directory to open as a project |

**Returns** [`OpenProjectResponse`](#openprojectresponse)

### `readImageResource`

Reads a raster image from an Eclipse workspace project and returns it as MCP image content. Supported extensions: png, jpg, jpeg, gif, bmp, tif, tiff and ico. Maximum size: 20 MiB.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the image |
| `resourcePath` | \* | The image path relative to the project root |

**Returns** [`McpSchemaImageContent`](#mcpschemaimagecontent)

### `readProjectResource`

Read the content of a text resource from a specified project. Returns the exact source text with no fence or line-number prefixes: the line the content starts at is returnedRange.startLine. version.modificationStamp is the token to pass back as expectedModificationStamp when editing, so a write is rejected if the file changed since the read. Supports line ranges and collapsing Java imports, which are reported in omittedRanges.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the resource |
| `resourcePath` | \* | The path to the resource relative to the project root |
| `startLine` |  | Optional 1-based start line to read from. If omitted, reads from the beginning. |
| `endLine` |  | Optional 1-based end line to read to (inclusive). If omitted, reads to the end. |
| `excludeImports` |  | If 'true', omits a Java import block to save tokens. The omitted lines are reported in omittedRanges. Default: 'false' |

**Returns** [`ResourceReadResult`](#resourcereadresult)

### `runJUnitTests` *(long)*

Starts a JUnit test run asynchronously and returns an operationId for polling. Scope is inferred from parameters: className+methodName=single method, className=single class, packageName=package, none=all tests in project. Use getOperationStatus to poll progress and results. For PDE plug-in tests, use runJUnitPluginTests in the eclipse-pde server instead. Publishes typed intermediate results while running: 'summary' (pass/fail counts) and 'results' (per-test details). getOperationStatus will show these automatically while the run is in progress.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The exact Eclipse project name containing the test classes (use listProjects to find it) |
| `className` |  | The fully qualified class name (e.g. 'com.example.MyServiceTest'). If omitted, runs all tests or package tests. |
| `methodName` |  | The test method name (e.g. 'testCreate'). Requires className. |
| `packageName` |  | The fully qualified package name (e.g. 'com.example.service'). Ignored if className is set. |
| `timeout` |  | Maximum time in seconds to wait for test completion (default: 60) |
| `withCoverage` |  | If 'true', runs tests with code coverage (requires EclEmma/JaCoCo installed). Default: false |
| `launcherName` |  | Optional name of a saved launch configuration to use as the base (use (eclipse-runner MCP server).listLaunchConfigurations with typeFilter='junit' to find it). When set, all settings from that config are reused (VM args, classpath, env vars, etc.) and only the test target is overridden. |

**Returns** [`TestRunResponse`](#testrunresponse)

### `runMavenBuild` *(long)*

Runs a Maven build on a project exactly the way the IDE's Run As > Maven build does: through m2e's Maven launch configuration, in a separate JVM with the configured Maven runtime, so the complete Maven log - [INFO] and [ERROR] lines, compiler and test output, the reactor summary - lands in the Console view under the launch's name. goals takes everything you would type after 'mvn', options included, and passes it to Maven verbatim: 'clean verify', 'test -Dtest=FooTest', 'install -DskipTests -pl module -am'. The result reports status (SUCCESS, FAILURE, RUNNING when the wait ran out, CANCELLED, FAILED_TO_START), the exit code, mavenCommand (the command line as Maven received it), errorLines (every distinct [ERROR] line, in order) and output (the last lines of the log, where the verdict and reactor summary are). The whole log is in the console named launchName: read it with getConsoleOutput(consoleName=launchName). The launch configuration is saved, so the build can be rerun from the IDE.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project to build (use listMavenProjects to find it). A Maven artifactId or groupId:artifactId is accepted when no project has that name. |
| `goals` | \* | Everything after 'mvn': phases, goals and any options, passed to Maven verbatim, e.g. 'clean verify' or 'test -Dtest=FooTest -DfailIfNoTests=false' |
| `profiles` |  | Optional comma-separated profiles to activate (Maven's -P) |
| `properties` |  | Optional comma-separated key=value system properties, each passed as -Dkey=value. A value that itself contains a comma goes in goals instead, as -Dkey=value. |
| `pomDirectory` |  | Optional project-relative directory holding the pom.xml to build - a module of a multi-module project. Default: the project root |
| `offline` |  | If 'true', works offline (-o). Default: false |
| `updateSnapshots` |  | If 'true', forces a check for updated snapshots and releases (-U). Default: false |
| `skipTests` |  | If 'true', neither compiles nor runs tests (-Dmaven.test.skip=true -DskipTests). Default: false |
| `debugOutput` |  | If 'true', asks Maven for debug output and full stack traces (-X -e). Default: false |
| `timeout` |  | Seconds to wait for the build before this call returns an operationId instead. The build carries on; getOperationStatus reports its output and, once it ends, this same result. Default: 60 |

**Returns** [`MavenBuildResponse`](#mavenbuildresponse)

### `searchAndReplace` *(long)*

Search and replace across multiple files in the workspace using Eclipse's text search engine. Reports per file how many occurrences were found and how many were replaced; the two differ when a file could not be fully updated.

| Parameter | | Description |
|---|---|---|
| `containingText` | \* | Plain text to find (not regex) |
| `replacementText` | \* | Replacement text (can be empty) |
| `fileNamePatterns` |  | Optional comma-separated file name patterns (e.g. "*.java,*.xml"). If omitted, all files are searched. |

**Returns** [`SearchReplaceResponse`](#searchreplaceresponse)

### `searchMethods` *(long)*

Searches for methods by name pattern across the entire workspace. Use this when you know (or can guess) a method name but don't know which class contains it. For example, if a user says 'fix the error handling', search for '*error*' or 'handle*' to find relevant methods. Supports wildcards (* and ?), CamelCase matching, and prefix matching. Optionally filter by declaring type to narrow results. Returns the method name, declaring class, package, parameter types, and return type. After finding a method, use getMethodSource to read its implementation.

| Parameter | | Description |
|---|---|---|
| `pattern` | \* | Method name pattern. Supports: wildcards (handle*Error, get*, *Payment, process*), CamelCase (pP -> processPayment — requires 2+ uppercase letters), or prefix (handle -> handleError, handleTimeout, ...). Note: CamelCase and prefix patterns are case-sensitive; use wildcards (*foo*) for case-insensitive matching. |
| `declaringTypePattern` |  | Optional pattern to filter by declaring type name (e.g. '*Service', 'Payment*'). Useful when the method name is common (e.g. 'get*') and you want to narrow to specific classes. |
| `maxResults` |  | Maximum number of results to return (default: 100) |

**Returns** [`MethodSearchResponse`](#methodsearchresponse)

### `searchTypes` *(long)*

Searches for Java types (classes, interfaces, enums, records, annotations) by name pattern. This is the primary discovery tool — use it FIRST when a user mentions a concept (e.g. 'payment handling', 'authentication') and you need to find which classes implement it. Supports wildcards (* and ?), CamelCase matching (e.g. 'PS' finds 'PaymentService'), and prefix matching. Prefer this over fileSearch for finding types: it searches the JDT index (instant) rather than file contents, and supports CamelCase patterns that text search cannot. After finding types, use getClassOutline or getPackageSummary to understand them, then getMethodSource to read specific methods.

| Parameter | | Description |
|---|---|---|
| `pattern` | \* | Type name pattern. Supports: wildcards (*Payment*, *Service, Error*), CamelCase (PS -> PaymentService, TxH -> TransactionHandler, CC -> CreditCard), prefix (Payment -> PaymentService, PaymentProcessor, ...), or package-qualified (com.example.*Service). Tips: try multiple patterns for a concept — e.g. for 'payment' try '*Payment*', '*Billing*', '*Transaction*'. |
| `maxResults` |  | Maximum number of results to return (default: 100) |

**Returns** [`TypeSearchResponse`](#typesearchresponse)

### `updateMavenProject` *(long)*

Runs the equivalent of the IDE's 'Maven > Update Project' action: re-reads the pom, re-resolves dependencies and reconfigures the project's classpath. Use this after editing a pom.xml - until it runs, the workspace does not see the change, so a newly added dependency is not on the classpath and code using it still fails to compile.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The Eclipse project to update (use listMavenProjects to find it); a Maven artifactId or groupId:artifactId is accepted when no project has that name |
| `forceDependencyUpdate` |  | If 'true', re-resolves snapshots and releases even when already cached (the 'Force Update of Snapshots/Releases' checkbox). Default: false |
| `offline` |  | If 'true', resolves only from the local repository without reaching the network. Default: false |

**Returns** `String`

## eclipse-pde

### `getActiveTarget`

Gets the Eclipse target platform the workspace is building against: its name, memento, whether it still exists, whether it is resolved and how many bundles it resolved to. status is RUNNING_PLATFORM when no .target file is set - an ordinary state, not a failure - and bundleCount is null unless the target is resolved.

**Returns** [`ActiveTargetResponse`](#activetargetresponse)

### `reloadTarget` *(long)*

Reloads the currently active Eclipse target platform and describes the result. Useful after target contents change on disk. With no .target file set there is nothing to reload: that is reported as status RUNNING_PLATFORM, not as an error.

**Returns** [`ActiveTargetResponse`](#activetargetresponse)

### `reloadWorkspaceBundle`

Schedules an OSGi update of a bundle backed by an open Eclipse workspace project. The reload starts after the current response completes; MCP clients may need to reconnect when reloading AssistAI itself.

| Parameter | | Description |
|---|---|---|
| `symbolicName` | \* | Bundle symbolic name; it must also name an open workspace project |
| `delayMillis` |  | Delay before reload, from 500 to 30000 ms. Default: 1500 |

**Returns** `String`

### `restartMcpServers`

Safely rebuilds the AssistAI HTTP MCP servers after the current response completes. Existing MCP connections are interrupted and may need to reconnect.

| Parameter | | Description |
|---|---|---|
| `delayMillis` |  | Delay before restart, from 500 to 30000 ms. Default: 1500 |

**Returns** `String`

### `runJUnitPluginTests` *(long)*

Starts a JUnit Plug-in Test run asynchronously using the PDE launcher and returns an operationId for polling. Scope is inferred from parameters: className+methodName=single method, className=single class (or comma-separated for multiple classes in one launch), packageName=package, none=all tests in project. Use getOperationStatus to poll progress and results. Publishes typed intermediate results while running: 'summary' (pass/fail counts) and 'results' (per-test details). getOperationStatus will show these automatically while the run is in progress.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The exact Eclipse project name containing the plug-in test classes |
| `className` |  | Fully qualified class name (e.g. 'com.example.MyPluginTest'), or comma-separated names for running multiple classes in one PDE launch. If omitted, runs all tests or the packageName scope. |
| `methodName` |  | The test method name (e.g. 'testCreate'). Requires a single className. |
| `packageName` |  | The fully qualified package name (e.g. 'com.example.service'). Ignored if className is set. |
| `timeout` |  | Maximum time in seconds to wait for test completion (default: 60) |
| `withCoverage` |  | If 'true', runs tests with code coverage (requires EclEmma/JaCoCo installed). Default: false |
| `includeAllPlugins` |  | If 'true', launches with all workspace and target platform plug-ins (USE_DEFAULT mode). If 'false' (default), auto-resolves required dependencies. |
| `additionalBundles` |  | Comma-separated additional bundle/plug-in symbolic names to include (only used when includeAllPlugins is false). |
| `launcherName` |  | Optional name of a saved launch configuration to use as the base (use (eclipse-runner MCP server).listLaunchConfigurations with typeFilter='junit-plugin' to find it). When set, all settings from that config are reused (VM args, program args, bundle selection, etc.) and only the test target is overridden. includeAllPlugins and additionalBundles are ignored when set. |

**Returns** [`TestRunResponse`](#testrunresponse)

### `setActiveTarget` *(long)*

Sets the active Eclipse target platform from a .target file, waits for it to load, and describes the target that is in force afterwards. status FAILED with a diagnostic means the load did not happen and the previous target is still active - check it before launching anything.

| Parameter | | Description |
|---|---|---|
| `targetFilePath` | \* | The workspace-relative or absolute path to the .target file (e.g., '/MyProject/myplatform.target') |

**Returns** [`ActiveTargetResponse`](#activetargetresponse)

## eclipse-runner

### `debugJavaApplication` *(long)*

Launches a Java application in debug mode. The application will stop at breakpoints. Use toggleBreakpoint to set breakpoints before launching. Same result shape as runJavaApplication: status, exitCode (null when still running), timedOut, and separate stdout/stderr with truncation counts.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the main class |
| `mainClass` | \* | The fully qualified name of the main class (e.g., 'com.example.Main') |
| `programArgs` |  | Optional program arguments passed to the main method |
| `vmArgs` |  | Optional JVM arguments (e.g., '-Xmx512m -Dfoo=bar') |
| `timeout` |  | Timeout in seconds to wait for completion. Use '0' to launch in background without waiting. Default: '0' |

**Returns** [`LaunchResponse`](#launchresponse)

### `evaluateExpression`

Evaluates a Java expression in a suspended debug frame. The application must be stopped at a breakpoint. value and declaredType are separate fields, so a result whose toString() contains a parenthesis is still readable, and nullResult distinguishes the null reference from a String holding "null". status is OK only when there is a value: COMPILE_ERROR puts the compiler's own messages in errorMessages, EVALUATION_FAILED means the expression threw, and TIMED_OUT / NO_SUSPENDED_THREAD / THREAD_NOT_FOUND / SESSION_NOT_FOUND each say why there is none. threadName and frame name the context the expression was evaluated in.

| Parameter | | Description |
|---|---|---|
| `nameOrClass` | \* | A substring to match against the debug session name or main class |
| `expression` | \* | The Java expression to evaluate (e.g., 'myList.size()', 'x + y', 'this.toString()') |
| `threadName` |  | Optional: the suspended thread whose top frame to evaluate in. Omit to take the first suspended thread |

**Returns** [`EvaluationResponse`](#evaluationresponse)

### `getStackTrace`

Gets the stack trace of every thread of a debug session, plus the local variables of the top frame. Each frame reports declaringType, methodName, projectName and a project-relative filePath with a 1-based lineNumber, so it can be opened with the reading tools; a frame outside the workspace, such as a JRE or library frame, reports no path. sessionFound says whether any debug session matched and anyThreadSuspended whether the program is stopped at a breakpoint - neither is an error.

| Parameter | | Description |
|---|---|---|
| `nameOrClass` | \* | A substring to match against the debug session name or main class |

**Returns** [`StackTraceResponse`](#stacktraceresponse)

### `hotCodeReplace` *(long)*

Rebuilds the debugged project and reports whether the new bytecode actually reached the running JVM - the observed outcome, not that a build was triggered. status is SUCCEEDED when the VM took it, OBSOLETE_METHODS when it did but frames already on the stack still run the old code, FAILED when the VM refused (a schema change: the running code is unchanged), NOT_SUPPORTED when the VM cannot hot swap at all, IN_SYNC when nothing needed replacing, and TIMED_OUT when the VM is out of sync and reported nothing. projectName is the project that was rebuilt; null means the launch named none and the whole workspace was built.

| Parameter | | Description |
|---|---|---|
| `nameOrClass` | \* | A substring to match against the debug session name or main class |

**Returns** [`HotCodeReplaceResponse`](#hotcodereplaceresponse)

### `launchConfiguration` *(long)*

Launches an existing saved launch configuration by name, exactly as it would run from Eclipse's Run/Debug Configurations dialog (reusing its classpath, program/VM arguments, environment variables, working directory, and agent settings such as JRebel). Use listLaunchConfigurations to find the name. Unlike runJavaApplication/debugJavaApplication, this does NOT create a throwaway configuration. If timeout > 0, waits for the process to finish; if timeout = 0, launches in background and returns immediately. Reports status, exitCode (null when still running), timedOut, and separate stdout/stderr with truncation counts. For JUnit test launches (plain tests or plug-in tests), use the dedicated runJUnitTests (eclipse-ide) or runJUnitPluginTests (eclipse-pde) tools instead — they provide structured test results, per-test status, and polling support that this generic launcher does not.

| Parameter | | Description |
|---|---|---|
| `configurationName` | \* | The exact name of the launch configuration to launch (e.g., 'Run Snapshot App No Data Compass Local') |
| `mode` |  | Launch mode: 'run' or 'debug'. Default: 'run' |
| `timeout` |  | Timeout in seconds to wait for completion. Use '0' to launch in background without waiting. Default: '0' |

**Returns** [`LaunchResponse`](#launchresponse)

### `listActiveLaunches`

Lists the applications Eclipse is currently running or debugging. Each launch reports name, mode (run/debug), mainType, projectName, a terminated flag, and its processes with the operating system pid where the debug plug-in recorded one. Nothing running is an empty launches list with totalLaunches = 0, not a message.

**Returns** [`ActiveLaunchesResponse`](#activelaunchesresponse)

### `listBreakpoints`

Lists all breakpoints currently set in the workspace. Each breakpoint reports projectName and a project-relative filePath, which the reading and editing tools take directly, plus typeName, a 1-based lineNumber, enabled, condition and hitCount. No breakpoints is an empty breakpoints list with totalBreakpoints = 0, not a message.

**Returns** [`BreakpointsResponse`](#breakpointsresponse)

### `listLaunchConfigurations`

Lists all saved launch configurations in the workspace (name, type, and for Java applications the project and main class). Each entry has: name, typeId, typeName, projectName, mainClass. Use this to discover the exact name to pass to launchConfiguration, (eclipse-ide MCP server).runJUnitTests (launcherName), or (eclipse-pde MCP server).runJUnitPluginTests (launcherName). Use typeFilter to narrow results: 'junit' for plain JUnit runs, 'junit-plugin' for PDE plug-in tests, or any substring of the type ID for other types.

| Parameter | | Description |
|---|---|---|
| `typeFilter` |  | Optional filter: 'junit' (org.eclipse.jdt.junit.launchconfig), 'junit-plugin' (org.eclipse.pde.ui.JunitLaunchConfig), 'all' or omit for everything, or any substring of the type ID. |

**Returns** [`LaunchConfigurationsResponse`](#launchconfigurationsresponse)

### `removeAllBreakpoints`

Removes all breakpoints from the workspace.

**Returns** `String`

### `resumeDebug` *(long)*

Resumes a suspended debug session and waits for it to stop at the next breakpoint. Returns the location the program is at afterwards, not a confirmation: frame carries declaringType, methodName, projectName, a project-relative filePath, a 1-based lineNumber and the frame's local variables, so no follow-up getStackTrace is needed. status is SUSPENDED when it stopped again, TERMINATED when the program ended, TIMED_OUT when it had not stopped within the timeout (retryable), RUNNING when timeout was 0 and nothing was waited for, NO_SUSPENDED_THREAD / THREAD_NOT_FOUND when there was nothing to act on, and SESSION_NOT_FOUND when no debug session matched - which a caller must never read as success. threadName names the thread acted on.

| Parameter | | Description |
|---|---|---|
| `nameOrClass` | \* | A substring to match against the debug session name or main class |
| `threadName` |  | Optional: the thread to resume. Omit to resume the whole session |
| `timeout` |  | Seconds to wait for the next suspend. Use '0' to resume without waiting. Default: '10' |

**Returns** [`StepResponse`](#stepresponse)

### `runJavaApplication` *(long)*

Launches a Java application in run mode. Specify the project and fully qualified main class. If timeout > 0, waits for the process to finish; if timeout = 0, launches in background and returns immediately. exitCode is the one fact that says whether the program worked and is a field of its own, null when the process is still running or the VM reported none - never a sentinel. timedOut says the wait ran out rather than the program finishing. stdout and stderr are separate, each with its own truncated flag and pre-truncation totalChars.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the main class |
| `mainClass` | \* | The fully qualified name of the main class (e.g., 'com.example.Main') |
| `programArgs` |  | Optional program arguments passed to the main method |
| `vmArgs` |  | Optional JVM arguments (e.g., '-Xmx512m -Dfoo=bar') |
| `timeout` |  | Timeout in seconds to wait for completion. Use '0' to launch in background without waiting. Default: '30' |

**Returns** [`LaunchResponse`](#launchresponse)

### `setConditionalBreakpoint`

Sets a breakpoint that only triggers when a condition evaluates to true, replacing any breakpoint already at that location. The condition comes back in its own field of the reported breakpoint, so a condition containing ':' no longer has to be recovered by splitting a sentence. action is SET or REPLACED. The location is validated first: TYPE_NOT_FOUND or INVALID_LINE means nothing was created.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the source file |
| `typeName` | \* | The fully qualified type name (e.g., 'com.example.Main') |
| `lineNumber` | \* | The 1-based line number where the breakpoint should be set |
| `condition` | \* | A Java boolean expression (e.g., 'i > 100', 'name.equals("test")') |
| `hitCount` |  | Optional: breakpoint triggers only after being hit N times. Default: '0' (disabled) |

**Returns** [`BreakpointResponse`](#breakpointresponse)

### `stepInto` *(long)*

Steps into the method call at the current line in a suspended debug session. Returns the location the program is at afterwards, not a confirmation: frame carries declaringType, methodName, projectName, a project-relative filePath, a 1-based lineNumber and the frame's local variables, so no follow-up getStackTrace is needed. status is SUSPENDED when it stopped again, TERMINATED when the program ended, TIMED_OUT when it had not stopped within the timeout (retryable), RUNNING when timeout was 0 and nothing was waited for, NO_SUSPENDED_THREAD / THREAD_NOT_FOUND when there was nothing to act on, and SESSION_NOT_FOUND when no debug session matched - which a caller must never read as success. threadName names the thread acted on.

| Parameter | | Description |
|---|---|---|
| `nameOrClass` | \* | A substring to match against the debug session name or main class |
| `threadName` |  | Optional: the thread to step. Omit to take the first suspended thread |
| `timeout` |  | Seconds to wait for the step to complete. Use '0' not to wait. Default: '10' |

**Returns** [`StepResponse`](#stepresponse)

### `stepOver` *(long)*

Steps over the current line in a suspended debug session, executing it without entering method calls. Returns the location the program is at afterwards, not a confirmation: frame carries declaringType, methodName, projectName, a project-relative filePath, a 1-based lineNumber and the frame's local variables, so no follow-up getStackTrace is needed. status is SUSPENDED when it stopped again, TERMINATED when the program ended, TIMED_OUT when it had not stopped within the timeout (retryable), RUNNING when timeout was 0 and nothing was waited for, NO_SUSPENDED_THREAD / THREAD_NOT_FOUND when there was nothing to act on, and SESSION_NOT_FOUND when no debug session matched - which a caller must never read as success. threadName names the thread acted on.

| Parameter | | Description |
|---|---|---|
| `nameOrClass` | \* | A substring to match against the debug session name or main class |
| `threadName` |  | Optional: the thread to step. Omit to take the first suspended thread |
| `timeout` |  | Seconds to wait for the step to complete. Use '0' not to wait. Default: '10' |

**Returns** [`StepResponse`](#stepresponse)

### `stepReturn` *(long)*

Runs until the current method returns to its caller, in a suspended debug session. Returns the location the program is at afterwards, not a confirmation: frame carries declaringType, methodName, projectName, a project-relative filePath, a 1-based lineNumber and the frame's local variables, so no follow-up getStackTrace is needed. status is SUSPENDED when it stopped again, TERMINATED when the program ended, TIMED_OUT when it had not stopped within the timeout (retryable), RUNNING when timeout was 0 and nothing was waited for, NO_SUSPENDED_THREAD / THREAD_NOT_FOUND when there was nothing to act on, and SESSION_NOT_FOUND when no debug session matched - which a caller must never read as success. threadName names the thread acted on.

| Parameter | | Description |
|---|---|---|
| `nameOrClass` | \* | A substring to match against the debug session name or main class |
| `threadName` |  | Optional: the thread to step. Omit to take the first suspended thread |
| `timeout` |  | Seconds to wait for the step to complete. Use '0' not to wait. Default: '10' |

**Returns** [`StepResponse`](#stepresponse)

### `stopApplication`

Stops the running or debugging Java applications matching the launch configuration name or main class name (substring match, case-insensitive). status is NO_MATCH when nothing was running that matched - a state, not a failure - OK when at least one was stopped, FAILED when matches existed and none could be. terminated is a list of launches, so a name containing a comma is still one entry; totalMatched beside it shows a partial stop.

| Parameter | | Description |
|---|---|---|
| `nameOrClass` | \* | A substring to match against the application name or main class (e.g., 'Main' or 'com.example') |

**Returns** [`StopApplicationResponse`](#stopapplicationresponse)

### `toggleBreakpoint`

Sets a line breakpoint at the given location, or removes the one already there. action says which way it went - SET, REMOVED or NONE - so a caller never has to read that out of a sentence. The location is validated first: status is TYPE_NOT_FOUND when the project resolves no such type (a breakpoint there would never bind) and INVALID_LINE when the line is past the end of the file; in both cases nothing is created. The resulting breakpoint is reported in the same shape listBreakpoints returns, with projectName and a project-relative filePath.

| Parameter | | Description |
|---|---|---|
| `projectName` | \* | The name of the project containing the source file |
| `typeName` | \* | The fully qualified type name (e.g., 'com.example.Main') |
| `lineNumber` | \* | The 1-based line number where the breakpoint should be set |

**Returns** [`BreakpointResponse`](#breakpointresponse)

## memory

### `completion_meta`

Internal sink for code completion. Use this tool to output any non-code text (markdown, explanations, reasoning, meta commentary) instead of writing it into the completion CONTENT stream. The code completion CONTENT stream must contain ONLY the exact source code to insert.

| Parameter | | Description |
|---|---|---|
| `text` | \* | Non-code meta text that should not appear in the completion output |

**Returns** `String`

### `think`

Use this tool to think about something. It will not obtain new information or perform changes, but will put your thought into a log, so that it is accessible to you. Use it for complex reasoning or as memory cache when you need to store some temporary information that you may consider useful to complete the task.

| Parameter | | Description |
|---|---|---|
| `thought` | \* | A thought or information worth using in solving a task |

**Returns** `String`

## time

### `convertTimeZone`

Converts time from one time zone to another. Returns a converted time in the yyyy-MM-dd HH:mm:ss z format.

| Parameter | | Description |
|---|---|---|
| `time` | \* | Date/time in the format yyyy-MM-dd HH:mm:ss |
| `sourceZone` |  | Source time zone id such as, such as Europe/Paris or CST. Default: system time zone |
| `targetZone` |  | Target time zone id, such as Europer/Paris or CST. Default: UTC |

**Returns** `String`

### `currentTime`

Returns the current date and time in the following format: yyyy-MM-dd HH:mm:ss

**Returns** `String`

## webpage-reader

### `readWebPage`

Reads the content of the given web page and returns it as markdown, together with the HTTP status, the URL the request ended at after redirects, the content type and the page title. Check statusCode: an error page converts to plausible-looking prose just as a real one does.

| Parameter | | Description |
|---|---|---|
| `url` | \* | A web site URL |

**Returns** [`WebPageResponse`](#webpageresponse)

## Result shapes

### `WebSearchResponse`

| Field | Type |
|---|---|
| `query` | `String` |
| `totalResults` | `int` |
| `results` | [`WebSearchResponseResult`](#websearchresponseresult)[] |
| `summaryText` | `String` |

### `EditResult`

| Field | Type |
|---|---|
| `status` | [`EditResultEditStatus`](#editresulteditstatus) |
| `projectName` | `String` |
| `filePath` | `String` |
| `versionBefore` | [`ResourceVersion`](#resourceversion) |
| `versionAfter` | [`ResourceVersion`](#resourceversion) |
| `edits` | [`EditResultAppliedEdit`](#editresultappliededit)[] |
| `unifiedDiff` | `String` |
| `affectedResources` | [`EditResultAffectedResource`](#editresultaffectedresource)[] |
| `editorReveal` | [`EditResultEditorReveal`](#editresulteditorreveal) |
| `undoHistoryTimestamp` | `long` |
| `workspaceState` | [`EditResultWorkspaceSync`](#editresultworkspacesync) |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |

### `LineDelimiterPreference`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `delimiter` | `String` |
| `name` | [`LineDelimiterPreferenceDelimiterName`](#linedelimiterpreferencedelimitername) |
| `source` | [`LineDelimiterPreferenceSource`](#linedelimiterpreferencesource) |

### `DiffResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `filePath` | `String` |
| `fromLabel` | `String` |
| `toLabel` | `String` |
| `fromVersion` | [`ResourceVersion`](#resourceversion) |
| `toVersion` | [`ResourceVersion`](#resourceversion) |
| `identical` | `boolean` |
| `addedLines` | `int` |
| `removedLines` | `int` |
| `unifiedDiff` | `String` |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |

### `CacheStatsResponse`

| Field | Type |
|---|---|
| `resourceCount` | `int` |
| `maxResources` | `int` |
| `totalEstimatedTokens` | `int` |
| `maxTotalTokens` | `int` |
| `summaryText` | `String` |

### `ResourceReadResult`

| Field | Type |
|---|---|
| `status` | [`ResourceReadResultReadStatus`](#resourcereadresultreadstatus) |
| `uri` | `String` |
| `projectName` | `String` |
| `filePath` | `String` |
| `language` | `String` |
| `version` | [`ResourceVersion`](#resourceversion) |
| `returnedRange` | [`ContentRange`](#contentrange) |
| `totalLines` | `int` |
| `content` | `String` |
| `origin` | [`SourceOrigin`](#sourceorigin) |
| `readOnly` | `boolean` |
| `truncated` | `boolean` |
| `omittedRanges` | [`ContentRange`](#contentrange)[] |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |

### `FileHistoryResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `filePath` | `String` |
| `totalVersions` | `int` |
| `versions` | [`FileHistoryResponseHistoryEntry`](#filehistoryresponsehistoryentry)[] |
| `truncated` | `boolean` |
| `summaryText` | `String` |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |

### `CachedResourcesResponse`

| Field | Type |
|---|---|
| `totalResources` | `int` |
| `totalEstimatedTokens` | `int` |
| `resources` | [`CachedResourcesResponseCachedEntry`](#cachedresourcesresponsecachedentry)[] |
| `summaryText` | `String` |

### `GitStageResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `operation` | [`GitStageResponseStageOperation`](#gitstageresponsestageoperation) |
| `pathspec` | `String` |
| `totalFiles` | `int` |
| `files` | [`GitStatusResponseGitFileChange`](#gitstatusresponsegitfilechange)[] |
| `summaryText` | `String` |

### `GitBranchResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `currentBranch` | `String` |
| `branches` | [`GitBranchResponseGitBranch`](#gitbranchresponsegitbranch)[] |
| `remoteBranches` | [`GitBranchResponseGitBranch`](#gitbranchresponsegitbranch)[] |
| `totalBranches` | `int` |
| `summaryText` | `String` |

### `GitCheckoutResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `status` | [`GitCheckoutResponseCheckoutStatus`](#gitcheckoutresponsecheckoutstatus) |
| `requestedBranch` | `String` |
| `previousBranch` | `String` |
| `currentBranch` | `String` |
| `headSha` | `String` |
| `blockingFiles` | [`GitStatusResponseGitFileChange`](#gitstatusresponsegitfilechange)[] |
| `refreshedProjects` | `String`[] |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |
| `summaryText` | `String` |

### `GitApplyCommitsResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `operation` | [`GitApplyCommitsResponseApplyOperation`](#gitapplycommitsresponseapplyoperation) |
| `status` | [`GitApplyCommitsResponseApplyStatus`](#gitapplycommitsresponseapplystatus) |
| `requestedCommits` | `String`[] |
| `previousHead` | `String` |
| `newHead` | `String` |
| `createdCommits` | [`GitLogResponseGitCommit`](#gitlogresponsegitcommit)[] |
| `conflicting` | [`GitStatusResponseGitFileChange`](#gitstatusresponsegitfilechange)[] |
| `blockingFiles` | [`GitStatusResponseGitFileChange`](#gitstatusresponsegitfilechange)[] |
| `refreshedProjects` | `String`[] |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |
| `summaryText` | `String` |

### `GitCommitResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `branch` | `String` |
| `commit` | [`GitLogResponseGitCommit`](#gitlogresponsegitcommit) |
| `summaryText` | `String` |

### `GitDeleteBranchResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `branchName` | `String` |
| `forced` | `boolean` |
| `deleted` | `boolean` |
| `deletedRefs` | `String`[] |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |
| `summaryText` | `String` |

### `GitTagResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `operation` | [`GitTagResponseTagOperation`](#gittagresponsetagoperation) |
| `tag` | [`GitTagListResponseGitTag`](#gittaglistresponsegittag) |
| `summaryText` | `String` |

### `GitDiffResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `staged` | `boolean` |
| `fromLabel` | `String` |
| `toLabel` | `String` |
| `baseRevision` | `String` |
| `identical` | `boolean` |
| `totalFiles` | `int` |
| `addedLines` | `int` |
| `removedLines` | `int` |
| `files` | [`GitDiffResponseGitFileDiff`](#gitdiffresponsegitfilediff)[] |
| `unifiedDiff` | `String` |
| `summaryText` | `String` |

### `GitDiscardResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `pathspec` | `String` |
| `totalFiles` | `int` |
| `files` | [`GitStatusResponseGitFileChange`](#gitstatusresponsegitfilechange)[] |
| `refreshedProjects` | `String`[] |
| `summaryText` | `String` |

### `GitFetchResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `remote` | `String` |
| `status` | [`GitFetchResponseFetchStatus`](#gitfetchresponsefetchstatus) |
| `totalUpdates` | `int` |
| `updates` | [`GitFetchResponseGitRefUpdate`](#gitfetchresponsegitrefupdate)[] |
| `messages` | `String` |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |
| `summaryText` | `String` |

### `GitLogResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `branch` | `String` |
| `commitCount` | `int` |
| `commits` | [`GitLogResponseGitCommit`](#gitlogresponsegitcommit)[] |
| `truncated` | `boolean` |
| `summaryText` | `String` |

### `GitMergeResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `status` | [`GitMergeResponseMergeStatus`](#gitmergeresponsemergestatus) |
| `mergedRef` | `String` |
| `previousHead` | `String` |
| `newHead` | `String` |
| `commit` | [`GitLogResponseGitCommit`](#gitlogresponsegitcommit) |
| `conflicting` | [`GitStatusResponseGitFileChange`](#gitstatusresponsegitfilechange)[] |
| `blockingFiles` | [`GitStatusResponseGitFileChange`](#gitstatusresponsegitfilechange)[] |
| `refreshedProjects` | `String`[] |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |
| `summaryText` | `String` |

### `GitPullResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `remote` | `String` |
| `remoteBranch` | `String` |
| `rebase` | `boolean` |
| `status` | [`GitPullResponsePullStatus`](#gitpullresponsepullstatus) |
| `fetchedUpdates` | `int` |
| `previousHead` | `String` |
| `newHead` | `String` |
| `conflicting` | [`GitStatusResponseGitFileChange`](#gitstatusresponsegitfilechange)[] |
| `blockingFiles` | [`GitStatusResponseGitFileChange`](#gitstatusresponsegitfilechange)[] |
| `refreshedProjects` | `String`[] |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |
| `summaryText` | `String` |

### `GitPushResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `remote` | `String` |
| `status` | [`GitPushResponsePushStatus`](#gitpushresponsepushstatus) |
| `updates` | [`GitPushResponseGitPushUpdate`](#gitpushresponsegitpushupdate)[] |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |
| `summaryText` | `String` |

### `GitRebaseResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `operation` | [`GitRebaseResponseRebaseOperation`](#gitrebaseresponserebaseoperation) |
| `status` | [`GitRebaseResponseRebaseStatus`](#gitrebaseresponserebasestatus) |
| `upstream` | `String` |
| `currentBranch` | `String` |
| `headSha` | `String` |
| `stoppedAt` | [`GitLogResponseGitCommit`](#gitlogresponsegitcommit) |
| `conflicting` | [`GitStatusResponseGitFileChange`](#gitstatusresponsegitfilechange)[] |
| `blockingFiles` | [`GitStatusResponseGitFileChange`](#gitstatusresponsegitfilechange)[] |
| `refreshedProjects` | `String`[] |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |
| `summaryText` | `String` |

### `GitRemoteListResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `totalRemotes` | `int` |
| `remotes` | [`GitRemoteListResponseGitRemote`](#gitremotelistresponsegitremote)[] |
| `summaryText` | `String` |

### `GitResetResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `mode` | [`GitResetResponseResetMode`](#gitresetresponseresetmode) |
| `revision` | `String` |
| `previousHead` | `String` |
| `newHead` | `String` |
| `refreshedProjects` | `String`[] |
| `summaryText` | `String` |

### `GitShowResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `revision` | `String` |
| `commit` | [`GitLogResponseGitCommit`](#gitlogresponsegitcommit) |
| `parentShas` | `String`[] |
| `totalFiles` | `int` |
| `addedLines` | `int` |
| `removedLines` | `int` |
| `files` | [`GitDiffResponseGitFileDiff`](#gitdiffresponsegitfilediff)[] |
| `unifiedDiff` | `String` |
| `summaryText` | `String` |

### `GitStagePatchResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `status` | [`GitStagePatchResponsePatchStatus`](#gitstagepatchresponsepatchstatus) |
| `totalFiles` | `int` |
| `files` | [`GitStatusResponseGitFileChange`](#gitstatusresponsegitfilechange)[] |
| `workingTreePreserved` | `boolean` |
| `restoredPaths` | `String`[] |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |
| `summaryText` | `String` |

### `GitStashResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `stashed` | `boolean` |
| `stash` | [`GitStashListResponseGitStash`](#gitstashlistresponsegitstash) |
| `totalStashes` | `int` |
| `summaryText` | `String` |

### `GitStashListResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `totalStashes` | `int` |
| `stashes` | [`GitStashListResponseGitStash`](#gitstashlistresponsegitstash)[] |
| `summaryText` | `String` |

### `GitStashPopResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `status` | [`GitStashPopResponsePopStatus`](#gitstashpopresponsepopstatus) |
| `dropped` | `boolean` |
| `stashRef` | `String` |
| `stashSha` | `String` |
| `stashMessage` | `String` |
| `conflicting` | [`GitStatusResponseGitFileChange`](#gitstatusresponsegitfilechange)[] |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |
| `summaryText` | `String` |

### `GitStatusResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `branch` | `String` |
| `upstreamBranch` | `String` |
| `aheadCount` | `Integer` |
| `behindCount` | `Integer` |
| `staged` | [`GitStatusResponseGitFileChange`](#gitstatusresponsegitfilechange)[] |
| `unstaged` | [`GitStatusResponseGitFileChange`](#gitstatusresponsegitfilechange)[] |
| `untracked` | [`GitStatusResponseGitFileChange`](#gitstatusresponsegitfilechange)[] |
| `conflicting` | [`GitStatusResponseGitFileChange`](#gitstatusresponsegitfilechange)[] |
| `totalChanges` | `int` |
| `clean` | `boolean` |
| `summaryText` | `String` |

### `GitTagListResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `totalTags` | `int` |
| `tags` | [`GitTagListResponseGitTag`](#gittaglistresponsegittag)[] |
| `summaryText` | `String` |

### `QuickFixResponse`

| Field | Type |
|---|---|
| `status` | [`QuickFixResponseStatus`](#quickfixresponsestatus) |
| `markerId` | `long` |
| `projectName` | `String` |
| `filePath` | `String` |
| `requestedIndex` | `int` |
| `appliedLabel` | `String` |
| `markerResolved` | `Boolean` |
| `availableProposals` | [`CompilationProblemsResponseQuickFixOption`](#compilationproblemsresponsequickfixoption)[] |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |

### `TypeResolutionResponse`

| Field | Type |
|---|---|
| `status` | [`TypeResolutionResponseStatus`](#typeresolutionresponsestatus) |
| `requestedTypeName` | `String` |
| `resolvedTypeName` | `String` |
| `searchedProjectName` | `String` |
| `sourceOrigin` | [`SourceOrigin`](#sourceorigin) |
| `projectName` | `String` |
| `filePath` | `String` |
| `rootKind` | [`TypeResolutionResponseRootKind`](#typeresolutionresponserootkind) |
| `packageFragmentRoot` | `String` |
| `sourceAttachmentPath` | `String` |
| `classpathEntryKind` | [`TypeResolutionResponseClasspathEntryKind`](#typeresolutionresponseclasspathentrykind) |
| `classpathEntryPath` | `String` |
| `classFilePath` | `String` |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |

### `SearchResponse`

| Field | Type |
|---|---|
| `query` | `String` |
| `totalMatches` | `int` |
| `filesMatched` | `int` |
| `matches` | [`SearchResponseSearchMatch`](#searchresponsesearchmatch)[] |
| `truncated` | `boolean` |
| `summaryText` | `String` |

### `FileListResponse`

| Field | Type |
|---|---|
| `patterns` | `String`[] |
| `totalFiles` | `int` |
| `files` | [`FileListResponseWorkspaceFile`](#filelistresponseworkspacefile)[] |
| `truncated` | `boolean` |
| `summaryText` | `String` |

### `ReferencesResponse`

| Field | Type |
|---|---|
| `target` | `String` |
| `totalReferences` | `int` |
| `filesAffected` | `int` |
| `references` | [`ReferencesResponseReference`](#referencesresponsereference)[] |
| `truncated` | `boolean` |
| `summaryText` | `String` |

### `TestClassesResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `totalClasses` | `int` |
| `plainTests` | [`TestClassesResponseTestClass`](#testclassesresponsetestclass)[] |
| `pdeTests` | [`TestClassesResponseTestClass`](#testclassesresponsetestclass)[] |
| `namingWarnings` | `String`[] |
| `summaryText` | `String` |

### `ClassOutlineResponse`

| Field | Type |
|---|---|
| `typeName` | `String` |
| `status` | [`ClassOutlineResponseStatus`](#classoutlineresponsestatus) |
| `projectName` | `String` |
| `filePath` | `String` |
| `declaration` | [`ClassOutlineResponseMember`](#classoutlineresponsemember) |
| `fields` | [`ClassOutlineResponseMember`](#classoutlineresponsemember)[] |
| `methods` | [`ClassOutlineResponseMember`](#classoutlineresponsemember)[] |
| `innerTypes` | [`ClassOutlineResponseMember`](#classoutlineresponsemember)[] |
| `summaryText` | `String` |

### `CompilationProblemsResponse`

| Field | Type |
|---|---|
| `scope` | `String` |
| `totalProblems` | `int` |
| `errorCount` | `int` |
| `warningCount` | `int` |
| `infoCount` | `int` |
| `files` | [`CompilationProblemsResponseFileProblems`](#compilationproblemsresponsefileproblems)[] |
| `truncated` | `boolean` |
| `summaryText` | `String` |

### `ConsoleOutputResponse`

| Field | Type |
|---|---|
| `status` | [`ConsoleOutputResponseStatus`](#consoleoutputresponsestatus) |
| `totalConsoles` | `int` |
| `consoles` | [`ConsoleOutputResponseConsoleOutput`](#consoleoutputresponseconsoleoutput)[] |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |

### `ImportSuggestionsResponse`

| Field | Type |
|---|---|
| `status` | [`ImportSuggestionsResponseStatus`](#importsuggestionsresponsestatus) |
| `projectName` | `String` |
| `filePath` | `String` |
| `totalUnresolvedTypes` | `int` |
| `totalCandidates` | `int` |
| `unresolvedTypes` | [`ImportSuggestionsResponseUnresolvedType`](#importsuggestionsresponseunresolvedtype)[] |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |

### `JavaDocResponse`

| Field | Type |
|---|---|
| `status` | [`JavaDocResponseStatus`](#javadocresponsestatus) |
| `typeName` | `String` |
| `projectName` | `String` |
| `markdown` | `String` |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |

### `MarkdownOutlineResponse`

| Field | Type |
|---|---|
| `status` | [`MarkdownOutlineResponseStatus`](#markdownoutlineresponsestatus) |
| `projectName` | `String` |
| `filePath` | `String` |
| `totalLines` | `int` |
| `headings` | [`MarkdownOutlineResponseHeading`](#markdownoutlineresponseheading)[] |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |

### `CallHierarchyResponse`

| Field | Type |
|---|---|
| `status` | [`CallHierarchyResponseStatus`](#callhierarchyresponsestatus) |
| `target` | `String` |
| `methodName` | `String` |
| `declaringType` | `String` |
| `maxDepth` | `int` |
| `totalCallers` | `int` |
| `totalCallees` | `int` |
| `callers` | [`CallHierarchyResponseCallNode`](#callhierarchyresponsecallnode)[] |
| `callees` | [`CallHierarchyResponseCallNode`](#callhierarchyresponsecallnode)[] |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |

### `MethodSourceResponse`

| Field | Type |
|---|---|
| `status` | [`MethodSourceResponseStatus`](#methodsourceresponsestatus) |
| `className` | `String` |
| `projectName` | `String` |
| `filePath` | `String` |
| `version` | [`ResourceVersion`](#resourceversion) |
| `methods` | [`MethodSourceResponseMethodSource`](#methodsourceresponsemethodsource)[] |
| `notFound` | `String`[] |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |

### `PackageSummaryResponse`

| Field | Type |
|---|---|
| `packageName` | `String` |
| `projectName` | `String` |
| `totalTypes` | `int` |
| `types` | [`PackageSummaryResponseTypeSummary`](#packagesummaryresponsetypesummary)[] |
| `summaryText` | `String` |

### `MavenDependenciesResponse`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `totalDependencies` | `int` |
| `dependencies` | [`MavenDependenciesResponseMavenDependency`](#mavendependenciesresponsemavendependency)[] |
| `summaryText` | `String` |

### `ProjectLayoutResponse`

| Field | Type |
|---|---|
| `status` | [`ProjectLayoutResponseStatus`](#projectlayoutresponsestatus) |
| `projectName` | `String` |
| `scopePath` | `String` |
| `maxDepth` | `Integer` |
| `root` | [`ProjectLayoutResponseNode`](#projectlayoutresponsenode) |
| `listedFiles` | `int` |
| `listedFolders` | `int` |
| `excludedCount` | `int` |
| `truncated` | `boolean` |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |

### `ProjectPropertiesResponse`

| Field | Type |
|---|---|
| `status` | [`ProjectPropertiesResponseStatus`](#projectpropertiesresponsestatus) |
| `projectName` | `String` |
| `location` | `String` |
| `natures` | `String`[] |
| `buildFiles` | `String`[] |
| `java` | [`ProjectPropertiesResponseJavaProperties`](#projectpropertiesresponsejavaproperties) |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |

### `TypeHierarchyResponse`

| Field | Type |
|---|---|
| `typeName` | `String` |
| `status` | [`TypeHierarchyResponseStatus`](#typehierarchyresponsestatus) |
| `superclasses` | [`TypeHierarchyResponseHierarchyType`](#typehierarchyresponsehierarchytype)[] |
| `interfaces` | [`TypeHierarchyResponseHierarchyType`](#typehierarchyresponsehierarchytype)[] |
| `subtypes` | [`TypeHierarchyResponseHierarchyType`](#typehierarchyresponsehierarchytype)[] |
| `summaryText` | `String` |

### `WorkspaceOverviewResponse`

| Field | Type |
|---|---|
| `totalProjects` | `int` |
| `totalPackages` | `int` |
| `totalTypes` | `int` |
| `projects` | [`WorkspaceOverviewResponseProjectOverview`](#workspaceoverviewresponseprojectoverview)[] |
| `summaryText` | `String` |

### `MavenProjectListResponse`

| Field | Type |
|---|---|
| `totalProjects` | `int` |
| `projects` | [`MavenProjectListResponseMavenProject`](#mavenprojectlistresponsemavenproject)[] |
| `summaryText` | `String` |

### `ProjectListResponse`

| Field | Type |
|---|---|
| `totalProjects` | `int` |
| `openProjects` | `int` |
| `projects` | [`ProjectListResponseWorkspaceProject`](#projectlistresponseworkspaceproject)[] |
| `summaryText` | `String` |

### `OpenProjectResponse`

| Field | Type |
|---|---|
| `status` | [`OpenProjectResponseStatus`](#openprojectresponsestatus) |
| `projectName` | `String` |
| `directoryPath` | `String` |
| `location` | `String` |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |

### `McpSchemaImageContent`

| Field | Type |
|---|---|
| `annotations` | [`McpSchemaAnnotations`](#mcpschemaannotations) |
| `data` | `String` |
| `mimeType` | `String` |
| `meta` | Map&lt;`String`, `Object`&gt; |

### `TestRunResponse`

| Field | Type |
|---|---|
| `status` | [`TestRunResponseRunStatus`](#testrunresponserunstatus) |
| `projectName` | `String` |
| `requestedClasses` | `String`[] |
| `summary` | [`TestRunResponseTestSummary`](#testrunresponsetestsummary) |
| `failedTests` | [`TestRunResponseTestCaseResult`](#testrunresponsetestcaseresult)[] |
| `skippedTests` | [`TestRunResponseSkippedTestResult`](#testrunresponseskippedtestresult)[] |
| `coverage` | [`TestRunResponseCoverageResult`](#testrunresponsecoverageresult) |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |
| `summaryText` | `String` |
| `durationMillis` | `long` |

### `MavenBuildResponse`

| Field | Type |
|---|---|
| `status` | [`MavenBuildResponseBuildStatus`](#mavenbuildresponsebuildstatus) |
| `projectName` | `String` |
| `pomDirectory` | `String` |
| `launchName` | `String` |
| `mavenCommand` | `String` |
| `exitCode` | `Integer` |
| `timedOut` | `boolean` |
| `durationMillis` | `long` |
| `errorLines` | `String`[] |
| `errorLinesTruncated` | `boolean` |
| `output` | [`MavenBuildResponseOutputTail`](#mavenbuildresponseoutputtail) |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |
| `summaryText` | `String` |

### `SearchReplaceResponse`

| Field | Type |
|---|---|
| `searchText` | `String` |
| `replacementText` | `String` |
| `filesChanged` | `int` |
| `totalMatches` | `int` |
| `totalReplacements` | `int` |
| `files` | [`SearchReplaceResponseFileReplacement`](#searchreplaceresponsefilereplacement)[] |
| `summaryText` | `String` |

### `MethodSearchResponse`

| Field | Type |
|---|---|
| `pattern` | `String` |
| `totalMatches` | `int` |
| `methods` | [`MethodSearchResponseMethodMatch`](#methodsearchresponsemethodmatch)[] |
| `truncated` | `boolean` |
| `summaryText` | `String` |

### `TypeSearchResponse`

| Field | Type |
|---|---|
| `pattern` | `String` |
| `totalMatches` | `int` |
| `types` | [`TypeSearchResponseTypeMatch`](#typesearchresponsetypematch)[] |
| `truncated` | `boolean` |
| `summaryText` | `String` |

### `ActiveTargetResponse`

| Field | Type |
|---|---|
| `status` | [`ActiveTargetResponseTargetStatus`](#activetargetresponsetargetstatus) |
| `explicitTarget` | `boolean` |
| `name` | `String` |
| `memento` | `String` |
| `exists` | `boolean` |
| `resolved` | `boolean` |
| `bundleCount` | `Integer` |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |

### `LaunchResponse`

| Field | Type |
|---|---|
| `status` | [`LaunchResponseStatus`](#launchresponsestatus) |
| `launchName` | `String` |
| `mode` | `String` |
| `projectName` | `String` |
| `mainClass` | `String` |
| `pid` | `Long` |
| `exitCode` | `Integer` |
| `timedOut` | `boolean` |
| `durationMillis` | `long` |
| `stdout` | [`LaunchResponseProcessOutput`](#launchresponseprocessoutput) |
| `stderr` | [`LaunchResponseProcessOutput`](#launchresponseprocessoutput) |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |
| `summaryText` | `String` |

### `EvaluationResponse`

| Field | Type |
|---|---|
| `status` | [`EvaluationResponseStatus`](#evaluationresponsestatus) |
| `nameOrClass` | `String` |
| `expression` | `String` |
| `launchName` | `String` |
| `threadName` | `String` |
| `frame` | [`StackTraceResponseFrame`](#stacktraceresponseframe) |
| `value` | `String` |
| `declaredType` | `String` |
| `nullResult` | `boolean` |
| `errorMessages` | `String`[] |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |
| `summaryText` | `String` |

### `StackTraceResponse`

| Field | Type |
|---|---|
| `nameOrClass` | `String` |
| `sessionFound` | `boolean` |
| `launchName` | `String` |
| `mainType` | `String` |
| `anyThreadSuspended` | `boolean` |
| `totalThreads` | `int` |
| `threads` | [`StackTraceResponseThreadTrace`](#stacktraceresponsethreadtrace)[] |
| `summaryText` | `String` |

### `HotCodeReplaceResponse`

| Field | Type |
|---|---|
| `status` | [`HotCodeReplaceResponseStatus`](#hotcodereplaceresponsestatus) |
| `nameOrClass` | `String` |
| `launchName` | `String` |
| `projectName` | `String` |
| `waitedMillis` | `long` |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |
| `summaryText` | `String` |

### `ActiveLaunchesResponse`

| Field | Type |
|---|---|
| `totalLaunches` | `int` |
| `launches` | [`ActiveLaunchesResponseActiveLaunch`](#activelaunchesresponseactivelaunch)[] |
| `summaryText` | `String` |

### `BreakpointsResponse`

| Field | Type |
|---|---|
| `totalBreakpoints` | `int` |
| `enabledCount` | `int` |
| `breakpoints` | [`BreakpointsResponseBreakpointInfo`](#breakpointsresponsebreakpointinfo)[] |
| `summaryText` | `String` |

### `LaunchConfigurationsResponse`

| Field | Type |
|---|---|
| `typeFilter` | `String` |
| `totalConfigurations` | `int` |
| `configurations` | [`LaunchConfigurationsResponseLaunchConfigurationInfo`](#launchconfigurationsresponselaunchconfigurationinfo)[] |
| `summaryText` | `String` |

### `StepResponse`

| Field | Type |
|---|---|
| `status` | [`StepResponseStatus`](#stepresponsestatus) |
| `kind` | [`StepResponseKind`](#stepresponsekind) |
| `nameOrClass` | `String` |
| `launchName` | `String` |
| `threadName` | `String` |
| `frame` | [`StackTraceResponseFrame`](#stacktraceresponseframe) |
| `waitedMillis` | `long` |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |
| `summaryText` | `String` |

### `BreakpointResponse`

| Field | Type |
|---|---|
| `status` | [`BreakpointResponseStatus`](#breakpointresponsestatus) |
| `action` | [`BreakpointResponseAction`](#breakpointresponseaction) |
| `projectName` | `String` |
| `typeName` | `String` |
| `lineNumber` | `int` |
| `breakpoint` | [`BreakpointsResponseBreakpointInfo`](#breakpointsresponsebreakpointinfo) |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |
| `summaryText` | `String` |

### `StopApplicationResponse`

| Field | Type |
|---|---|
| `status` | [`StopApplicationResponseStatus`](#stopapplicationresponsestatus) |
| `nameOrClass` | `String` |
| `totalMatched` | `int` |
| `terminated` | [`StopApplicationResponseTerminatedLaunch`](#stopapplicationresponseterminatedlaunch)[] |
| `diagnostics` | [`Diagnostic`](#diagnostic)[] |
| `summaryText` | `String` |

### `WebPageResponse`

| Field | Type |
|---|---|
| `requestedUrl` | `String` |
| `finalUrl` | `String` |
| `statusCode` | `int` |
| `contentType` | `String` |
| `title` | `String` |
| `content` | `String` |

### `WebSearchResponseResult`

| Field | Type |
|---|---|
| `title` | `String` |
| `url` | `String` |
| `snippet` | `String` |

### `EditResultEditStatus`

`APPLIED` \| `APPLIED_WITH_WARNINGS` \| `REJECTED` \| `PREVIEW`

### `ResourceVersion`

| Field | Type |
|---|---|
| `modificationStamp` | `Long` |
| `localTimeStamp` | `long` |
| `historyTimestamp` | `Long` |
| `inSyncWithFileSystem` | `boolean` |

### `EditResultAppliedEdit`

| Field | Type |
|---|---|
| `oldRange` | [`ContentRange`](#contentrange) |
| `newRange` | [`ContentRange`](#contentrange) |
| `insertedCharacters` | `int` |
| `deletedCharacters` | `int` |

### `EditResultAffectedResource`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `filePath` | `String` |
| `kind` | [`EditResultChangeKind`](#editresultchangekind) |
| `version` | [`ResourceVersion`](#resourceversion) |

### `EditResultEditorReveal`

| Field | Type |
|---|---|
| `opened` | `boolean` |
| `revealedRange` | [`ContentRange`](#contentrange) |
| `caret` | [`EditResultEditorPosition`](#editresulteditorposition) |

### `EditResultWorkspaceSync`

| Field | Type |
|---|---|
| `savedToDisk` | `boolean` |
| `cacheUpdated` | `boolean` |
| `jdtConsistent` | `String` |

### `Diagnostic`

| Field | Type |
|---|---|
| `code` | [`DiagnosticCode`](#diagnosticcode) |
| `message` | `String` |
| `retryable` | `boolean` |

### `LineDelimiterPreferenceDelimiterName`

`LF` \| `CRLF` \| `CR` \| `OTHER`

### `LineDelimiterPreferenceSource`

`PROJECT` \| `WORKSPACE` \| `DEFAULT`

### `ResourceReadResultReadStatus`

`OK` \| `PARTIAL` \| `FAILED`

### `ContentRange`

| Field | Type |
|---|---|
| `startLine` | `int` |
| `startColumn` | `int` |
| `endLine` | `int` |
| `endColumn` | `int` |

### `SourceOrigin`

`WORKSPACE_SOURCE` \| `ATTACHED_SOURCE` \| `DECOMPILED_CLASS` \| `LOCAL_HISTORY`

### `FileHistoryResponseHistoryEntry`

| Field | Type |
|---|---|
| `historyTimestamp` | `long` |
| `storedAt` | `String` |
| `sizeBytes` | `long` |
| `exists` | `boolean` |

### `CachedResourcesResponseCachedEntry`

| Field | Type |
|---|---|
| `uri` | `String` |
| `type` | [`ResourceDescriptorResourceType`](#resourcedescriptorresourcetype) |
| `displayName` | `String` |
| `projectName` | `String` |
| `filePath` | `String` |
| `cachedAt` | `String` |
| `cachedAtEpochMilli` | `long` |
| `modificationStamp` | `Long` |
| `estimatedTokens` | `int` |
| `cacheRevision` | `int` |

### `GitStageResponseStageOperation`

`STAGE` \| `UNSTAGE`

### `GitStatusResponseGitFileChange`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `filePath` | `String` |
| `repoPath` | `String` |
| `changeType` | [`GitStatusResponseChangeType`](#gitstatusresponsechangetype) |

### `GitBranchResponseGitBranch`

| Field | Type |
|---|---|
| `name` | `String` |
| `fullName` | `String` |
| `sha` | `String` |
| `current` | `boolean` |

### `GitCheckoutResponseCheckoutStatus`

`SWITCHED` \| `BLOCKED`

### `GitApplyCommitsResponseApplyOperation`

`CHERRY_PICK` \| `REVERT`

### `GitApplyCommitsResponseApplyStatus`

`APPLIED` \| `CONFLICTED` \| `BLOCKED` \| `FAILED`

### `GitLogResponseGitCommit`

| Field | Type |
|---|---|
| `sha` | `String` |
| `shortSha` | `String` |
| `author` | `String` |
| `authorEmail` | `String` |
| `authorTimeMillis` | `long` |
| `message` | `String` |
| `shortMessage` | `String` |

### `GitTagResponseTagOperation`

`CREATED` \| `DELETED`

### `GitTagListResponseGitTag`

| Field | Type |
|---|---|
| `name` | `String` |
| `fullName` | `String` |
| `sha` | `String` |
| `targetSha` | `String` |
| `annotated` | `boolean` |
| `message` | `String` |

### `GitDiffResponseGitFileDiff`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `filePath` | `String` |
| `repoPath` | `String` |
| `oldRepoPath` | `String` |
| `changeType` | [`GitDiffResponseFileChangeType`](#gitdiffresponsefilechangetype) |
| `addedLines` | `int` |
| `removedLines` | `int` |
| `binary` | `boolean` |

### `GitFetchResponseFetchStatus`

`UPDATED` \| `UP_TO_DATE` \| `FAILED`

### `GitFetchResponseGitRefUpdate`

| Field | Type |
|---|---|
| `localRef` | `String` |
| `remoteRef` | `String` |
| `oldSha` | `String` |
| `newSha` | `String` |
| `result` | `String` |

### `GitMergeResponseMergeStatus`

`FAST_FORWARD` \| `MERGED` \| `ALREADY_UP_TO_DATE` \| `MERGED_NOT_COMMITTED` \| `CONFLICTED` \| `BLOCKED` \| `FAILED`

### `GitPullResponsePullStatus`

`FAST_FORWARD` \| `MERGED` \| `REBASED` \| `ALREADY_UP_TO_DATE` \| `CONFLICTED` \| `BLOCKED` \| `FAILED`

### `GitPushResponsePushStatus`

`PUSHED` \| `UP_TO_DATE` \| `REJECTED` \| `FAILED`

### `GitPushResponseGitPushUpdate`

| Field | Type |
|---|---|
| `localRef` | `String` |
| `remoteRef` | `String` |
| `status` | `String` |
| `message` | `String` |

### `GitRebaseResponseRebaseOperation`

`BEGIN` \| `CONTINUE` \| `SKIP` \| `ABORT`

### `GitRebaseResponseRebaseStatus`

`OK` \| `UP_TO_DATE` \| `FAST_FORWARD` \| `STOPPED` \| `ABORTED` \| `NOTHING_TO_COMMIT` \| `UNCOMMITTED_CHANGES` \| `BLOCKED` \| `FAILED`

### `GitRemoteListResponseGitRemote`

| Field | Type |
|---|---|
| `name` | `String` |
| `fetchUrl` | `String` |
| `pushUrl` | `String` |

### `GitResetResponseResetMode`

`SOFT` \| `MIXED` \| `HARD`

### `GitStagePatchResponsePatchStatus`

`STAGED` \| `FAILED`

### `GitStashListResponseGitStash`

| Field | Type |
|---|---|
| `index` | `int` |
| `ref` | `String` |
| `sha` | `String` |
| `message` | `String` |

### `GitStashPopResponsePopStatus`

`APPLIED` \| `CONFLICTED` \| `NOTHING_TO_APPLY`

### `QuickFixResponseStatus`

`APPLIED` \| `MARKER_NOT_FOUND` \| `NO_PROPOSALS` \| `INVALID_PROPOSAL_INDEX` \| `APPLY_FAILED`

### `CompilationProblemsResponseQuickFixOption`

| Field | Type |
|---|---|
| `index` | `int` |
| `label` | `String` |
| `description` | `String` |

### `TypeResolutionResponseStatus`

`OK` \| `TYPE_NOT_RESOLVED` \| `PROJECT_NOT_FOUND`

### `TypeResolutionResponseRootKind`

`WORKSPACE_FOLDER` \| `WORKSPACE_ARCHIVE` \| `EXTERNAL_FOLDER` \| `EXTERNAL_ARCHIVE`

### `TypeResolutionResponseClasspathEntryKind`

`SOURCE` \| `PROJECT` \| `LIBRARY` \| `VARIABLE` \| `CONTAINER` \| `UNKNOWN`

### `SearchResponseSearchMatch`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `filePath` | `String` |
| `lineNumber` | `int` |
| `lineContent` | `String` |

### `FileListResponseWorkspaceFile`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `filePath` | `String` |

### `ReferencesResponseReference`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `filePath` | `String` |
| `lineNumber` | `int` |
| `enclosingElement` | `String` |
| `lineContent` | `String` |

### `TestClassesResponseTestClass`

| Field | Type |
|---|---|
| `className` | `String` |
| `filePath` | `String` |
| `likelyRequiresPdeHarness` | `boolean` |

### `ClassOutlineResponseStatus`

`OK` \| `TYPE_NOT_FOUND` \| `NO_SOURCE` \| `ACCESS_DENIED`

### `ClassOutlineResponseMember`

| Field | Type |
|---|---|
| `name` | `String` |
| `label` | `String` |
| `startLine` | `int` |
| `endLine` | `int` |

### `CompilationProblemsResponseFileProblems`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `filePath` | `String` |
| `problems` | [`CompilationProblemsResponseProblem`](#compilationproblemsresponseproblem)[] |

### `ConsoleOutputResponseStatus`

`OK` \| `FAILED`

### `ConsoleOutputResponseConsoleOutput`

| Field | Type |
|---|---|
| `consoleName` | `String` |
| `returnedRange` | [`ContentRange`](#contentrange) |
| `totalLines` | `int` |
| `truncated` | `boolean` |
| `text` | `String` |

### `ImportSuggestionsResponseStatus`

`OK` \| `PROJECT_NOT_FOUND` \| `PROJECT_CLOSED` \| `FILE_NOT_FOUND` \| `FAILED`

### `ImportSuggestionsResponseUnresolvedType`

| Field | Type |
|---|---|
| `typeName` | `String` |
| `lineNumber` | `int` |
| `message` | `String` |
| `candidates` | `String`[] |

### `JavaDocResponseStatus`

`OK` \| `NO_JAVADOC` \| `TYPE_NOT_FOUND`

### `MarkdownOutlineResponseStatus`

`OK` \| `FAILED`

### `MarkdownOutlineResponseHeading`

| Field | Type |
|---|---|
| `index` | `int` |
| `level` | `int` |
| `text` | `String` |
| `range` | [`ContentRange`](#contentrange) |

### `CallHierarchyResponseStatus`

`OK` \| `TYPE_NOT_FOUND` \| `METHOD_NOT_FOUND` \| `FAILED`

### `CallHierarchyResponseCallNode`

| Field | Type |
|---|---|
| `depth` | `int` |
| `methodName` | `String` |
| `declaringType` | `String` |
| `signature` | `String` |
| `projectName` | `String` |
| `filePath` | `String` |
| `lineNumber` | `int` |

### `MethodSourceResponseStatus`

`OK` \| `PARTIAL` \| `FAILED`

### `MethodSourceResponseMethodSource`

| Field | Type |
|---|---|
| `methodName` | `String` |
| `parameters` | `String` |
| `range` | [`ContentRange`](#contentrange) |
| `source` | `String` |

### `PackageSummaryResponseTypeSummary`

| Field | Type |
|---|---|
| `simpleName` | `String` |
| `typeKind` | `String` |
| `javadocSummary` | `String` |
| `methodCount` | `int` |
| `fieldCount` | `int` |
| `superInterfaces` | `String`[] |

### `MavenDependenciesResponseMavenDependency`

| Field | Type |
|---|---|
| `groupId` | `String` |
| `artifactId` | `String` |
| `version` | `String` |
| `scope` | `String` |

### `ProjectLayoutResponseStatus`

`OK` \| `FAILED`

### `ProjectLayoutResponseNode`

| Field | Type |
|---|---|
| `name` | `String` |
| `filePath` | `String` |
| `type` | [`ProjectLayoutResponseNodeType`](#projectlayoutresponsenodetype) |
| `childCount` | `int` |
| `children` | [`ProjectLayoutResponseNode`](#projectlayoutresponsenode)[] |

### `ProjectPropertiesResponseStatus`

`OK` \| `PROJECT_NOT_FOUND` \| `PROJECT_CLOSED` \| `FAILED`

### `ProjectPropertiesResponseJavaProperties`

| Field | Type |
|---|---|
| `complianceLevel` | `String` |
| `sourceCompatibility` | `String` |
| `targetCompatibility` | `String` |
| `outputLocation` | `String` |
| `sourceFolders` | `String`[] |
| `referencedProjects` | `String`[] |
| `referencedLibraries` | `String`[] |

### `TypeHierarchyResponseStatus`

`OK` \| `TYPE_NOT_FOUND`

### `TypeHierarchyResponseHierarchyType`

| Field | Type |
|---|---|
| `fullyQualifiedName` | `String` |
| `projectName` | `String` |
| `filePath` | `String` |

### `WorkspaceOverviewResponseProjectOverview`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `packageCount` | `int` |
| `typeCount` | `int` |
| `packages` | [`WorkspaceOverviewResponsePackageOverview`](#workspaceoverviewresponsepackageoverview)[] |

### `MavenProjectListResponseMavenProject`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `groupId` | `String` |
| `artifactId` | `String` |
| `version` | `String` |
| `packaging` | `String` |

### `ProjectListResponseWorkspaceProject`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `open` | `boolean` |
| `natures` | `String`[] |
| `location` | `String` |

### `OpenProjectResponseStatus`

`IMPORTED` \| `OPENED` \| `ALREADY_OPEN` \| `FAILED`

### `McpSchemaAnnotations`

| Field | Type |
|---|---|
| `audience` | [`McpSchemaRole`](#mcpschemarole)[] |
| `priority` | `Double` |
| `lastModified` | `String` |

### `TestRunResponseRunStatus`

`RUNNING` \| `COMPLETED` \| `COMPLETED_WITH_FAILURES` \| `FAILED_TO_START` \| `TIMED_OUT` \| `CANCELLED`

### `TestRunResponseTestSummary`

| Field | Type |
|---|---|
| `total` | `int` |
| `passed` | `int` |
| `failed` | `int` |
| `errors` | `int` |
| `skipped` | `int` |

### `TestRunResponseTestCaseResult`

| Field | Type |
|---|---|
| `className` | `String` |
| `methodName` | `String` |
| `status` | [`TestRunResponseTestStatus`](#testrunresponseteststatus) |
| `message` | `String` |
| `failureTrace` | `String` |
| `traceTruncated` | `boolean` |
| `source` | [`TestRunResponseSourceLocation`](#testrunresponsesourcelocation) |
| `durationSeconds` | `double` |

### `TestRunResponseSkippedTestResult`

| Field | Type |
|---|---|
| `className` | `String` |
| `methodName` | `String` |
| `reason` | `String` |

### `TestRunResponseCoverageResult`

| Field | Type |
|---|---|
| `requested` | `boolean` |
| `available` | `boolean` |
| `execFilePath` | `String` |
| `report` | `String` |

### `MavenBuildResponseBuildStatus`

`SUCCESS` \| `FAILURE` \| `RUNNING` \| `CANCELLED` \| `FAILED_TO_START`

### `MavenBuildResponseOutputTail`

| Field | Type |
|---|---|
| `text` | `String` |
| `totalLines` | `int` |
| `truncated` | `boolean` |

### `SearchReplaceResponseFileReplacement`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `filePath` | `String` |
| `matchesFound` | `int` |
| `replacementsMade` | `int` |

### `MethodSearchResponseMethodMatch`

| Field | Type |
|---|---|
| `methodName` | `String` |
| `declaringType` | `String` |
| `packageName` | `String` |
| `projectName` | `String` |
| `returnType` | `String` |
| `parameterTypes` | `String`[] |

### `TypeSearchResponseTypeMatch`

| Field | Type |
|---|---|
| `fullyQualifiedName` | `String` |
| `simpleName` | `String` |
| `packageName` | `String` |
| `projectName` | `String` |
| `typeKind` | `String` |

### `ActiveTargetResponseTargetStatus`

`ACTIVE` \| `RUNNING_PLATFORM` \| `FAILED`

### `LaunchResponseStatus`

`RUNNING` \| `COMPLETED` \| `FAILED_TO_START`

### `LaunchResponseProcessOutput`

| Field | Type |
|---|---|
| `text` | `String` |
| `truncated` | `boolean` |
| `totalChars` | `int` |

### `EvaluationResponseStatus`

`OK` \| `COMPILE_ERROR` \| `EVALUATION_FAILED` \| `TIMED_OUT` \| `NO_SUSPENDED_THREAD` \| `THREAD_NOT_FOUND` \| `SESSION_NOT_FOUND`

### `StackTraceResponseFrame`

| Field | Type |
|---|---|
| `index` | `int` |
| `declaringType` | `String` |
| `methodName` | `String` |
| `projectName` | `String` |
| `filePath` | `String` |
| `lineNumber` | `int` |
| `nativeMethod` | `boolean` |
| `synthetic` | `boolean` |
| `variables` | [`StackTraceResponseVariable`](#stacktraceresponsevariable)[] |

### `StackTraceResponseThreadTrace`

| Field | Type |
|---|---|
| `name` | `String` |
| `suspended` | `boolean` |
| `totalFrames` | `int` |
| `frames` | [`StackTraceResponseFrame`](#stacktraceresponseframe)[] |

### `HotCodeReplaceResponseStatus`

`SUCCEEDED` \| `OBSOLETE_METHODS` \| `FAILED` \| `NOT_SUPPORTED` \| `IN_SYNC` \| `TIMED_OUT` \| `SESSION_NOT_FOUND` \| `NO_JAVA_TARGET`

### `ActiveLaunchesResponseActiveLaunch`

| Field | Type |
|---|---|
| `name` | `String` |
| `mode` | `String` |
| `terminated` | `boolean` |
| `mainType` | `String` |
| `projectName` | `String` |
| `pid` | `Long` |
| `processes` | [`ActiveLaunchesResponseLaunchProcess`](#activelaunchesresponselaunchprocess)[] |

### `BreakpointsResponseBreakpointInfo`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `filePath` | `String` |
| `typeName` | `String` |
| `lineNumber` | `int` |
| `enabled` | `boolean` |
| `condition` | `String` |
| `hitCount` | `int` |
| `modelIdentifier` | `String` |

### `LaunchConfigurationsResponseLaunchConfigurationInfo`

| Field | Type |
|---|---|
| `name` | `String` |
| `typeId` | `String` |
| `typeName` | `String` |
| `projectName` | `String` |
| `mainClass` | `String` |

### `StepResponseStatus`

`SUSPENDED` \| `RUNNING` \| `TERMINATED` \| `TIMED_OUT` \| `NO_SUSPENDED_THREAD` \| `THREAD_NOT_FOUND` \| `SESSION_NOT_FOUND` \| `FAILED`

### `StepResponseKind`

`STEP_OVER` \| `STEP_INTO` \| `STEP_RETURN` \| `RESUME`

### `BreakpointResponseStatus`

`OK` \| `PROJECT_NOT_FOUND` \| `TYPE_NOT_FOUND` \| `INVALID_LINE` \| `FAILED`

### `BreakpointResponseAction`

`SET` \| `REMOVED` \| `REPLACED` \| `NONE`

### `StopApplicationResponseStatus`

`OK` \| `NO_MATCH` \| `FAILED`

### `StopApplicationResponseTerminatedLaunch`

| Field | Type |
|---|---|
| `launchName` | `String` |
| `mainType` | `String` |
| `mode` | `String` |

### `EditResultChangeKind`

`MODIFIED` \| `CREATED` \| `DELETED` \| `MOVED`

### `EditResultEditorPosition`

| Field | Type |
|---|---|
| `line` | `int` |
| `column` | `int` |

### `DiagnosticCode`

`RESOURCE_NOT_FOUND` \| `RESOURCE_NOT_ACCESSIBLE` \| `RESOURCE_ALREADY_EXISTS` \| `READ_ONLY_RESOURCE` \| `INVALID_RANGE` \| `VERSION_CONFLICT` \| `RESOURCE_VERSION_EXPIRED` \| `RESOURCE_OUT_OF_SYNC` \| `HISTORY_UNAVAILABLE` \| `TEXT_NOT_FOUND` \| `AMBIGUOUS_MATCH` \| `OVERLAPPING_EDITS` \| `INVALID_JAVA_EDIT` \| `REFACTORING_PRECONDITION_FAILED` \| `EDITOR_REVEAL_FAILED` \| `FORMATTER_FAILED` \| `PATCH_APPLY_FAILED` \| `MERGE_CONFLICT` \| `CHECKOUT_CONFLICT` \| `BRANCH_NOT_MERGED` \| `REVISION_NOT_FOUND` \| `WRONG_REPOSITORY_STATE` \| `UNCOMMITTED_CHANGES` \| `PUSH_REJECTED` \| `REMOTE_OPERATION_FAILED` \| `PROJECT_NOT_FOUND` \| `TEST_CLASS_NOT_FOUND` \| `TEST_PACKAGE_NOT_FOUND` \| `PDE_LAUNCH_TYPE_MISSING` \| `MAVEN_LAUNCH_TYPE_MISSING` \| `LAUNCH_CONFIGURATION_NOT_FOUND` \| `WORKSPACE_LOCKED` \| `OPERATION_TIMED_OUT` \| `DEPENDENCY_RESOLUTION_FAILED` \| `TEST_RESULTS_NOT_REPORTED` \| `COVERAGE_UNAVAILABLE` \| `VALIDATION_ERROR` \| `INTERNAL_ERROR`

### `ResourceDescriptorResourceType`

`WORKSPACE_FILE` \| `JAVA_TYPE` \| `PROJECT_LAYOUT` \| `CONSOLE_OUTPUT` \| `EXTERNAL_FILE` \| `QUERY_RESULT` \| `TRANSIENT`

### `GitStatusResponseChangeType`

`ADDED` \| `MODIFIED` \| `DELETED` \| `UNTRACKED` \| `CONFLICTING`

### `GitDiffResponseFileChangeType`

`ADDED` \| `MODIFIED` \| `DELETED` \| `RENAMED` \| `COPIED`

### `CompilationProblemsResponseProblem`

| Field | Type |
|---|---|
| `severity` | [`CompilationProblemsResponseSeverity`](#compilationproblemsresponseseverity) |
| `lineNumber` | `int` |
| `message` | `String` |
| `markerId` | `long` |
| `problemId` | `Integer` |
| `contextSnippet` | `String` |
| `contextLanguage` | `String` |
| `quickFixes` | [`CompilationProblemsResponseQuickFixOption`](#compilationproblemsresponsequickfixoption)[] |

### `ProjectLayoutResponseNodeType`

`PROJECT` \| `FOLDER` \| `FILE`

### `WorkspaceOverviewResponsePackageOverview`

| Field | Type |
|---|---|
| `packageName` | `String` |
| `typeCount` | `int` |
| `typeNames` | `String`[] |

### `McpSchemaRole`

`USER` \| `ASSISTANT`

### `TestRunResponseTestStatus`

`PASSED` \| `FAILED` \| `ERROR` \| `SKIPPED` \| `UNKNOWN`

### `TestRunResponseSourceLocation`

| Field | Type |
|---|---|
| `projectName` | `String` |
| `filePath` | `String` |
| `line` | `Integer` |

### `StackTraceResponseVariable`

| Field | Type |
|---|---|
| `name` | `String` |
| `typeName` | `String` |
| `value` | `String` |

### `ActiveLaunchesResponseLaunchProcess`

| Field | Type |
|---|---|
| `label` | `String` |
| `terminated` | `boolean` |
| `pid` | `Long` |

### `CompilationProblemsResponseSeverity`

`ERROR` \| `WARNING` \| `INFO` \| `UNKNOWN`
