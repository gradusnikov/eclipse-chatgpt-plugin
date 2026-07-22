---
name: eclipse-debug
description: Debug Java applications in Eclipse — set breakpoints, launch in debug mode, step through code, inspect stack traces, evaluate expressions, and hot-swap code changes.
argument-hint: "[project] [main class]"
allowed-tools: mcp__eclipse-runner__debugJavaApplication, mcp__eclipse-runner__launchConfiguration, mcp__eclipse-runner__listLaunchConfigurations, mcp__eclipse-runner__stopApplication, mcp__eclipse-runner__listActiveLaunches, mcp__eclipse-runner__toggleBreakpoint, mcp__eclipse-runner__setConditionalBreakpoint, mcp__eclipse-runner__listBreakpoints, mcp__eclipse-runner__removeAllBreakpoints, mcp__eclipse-runner__getStackTrace, mcp__eclipse-runner__evaluateExpression, mcp__eclipse-runner__resumeDebug, mcp__eclipse-runner__stepOver, mcp__eclipse-runner__stepInto, mcp__eclipse-runner__stepReturn, mcp__eclipse-runner__hotCodeReplace, mcp__eclipse-ide__getConsoleOutput, mcp__eclipse-pde__restartMcpServers, mcp__eclipse-pde__reloadWorkspaceBundle
---

# Debug Java Applications in Eclipse

Full interactive debugging using Eclipse's JDT debugger. Set breakpoints, step through code, inspect variables, evaluate expressions, and hot-swap changes.

## Breakpoints

- **toggleBreakpoint** — Set or remove a line breakpoint. Provide `projectName`, `typeName` (fully qualified), and `lineNumber`.
- **setConditionalBreakpoint** — Set a breakpoint with a condition. Only triggers when the condition is true. Also supports `hitCount` (trigger after N hits).
- **listBreakpoints** — Show all breakpoints with location, enabled status, and conditions.
- **removeAllBreakpoints** — Clear all breakpoints.

## Launch

- **debugJavaApplication** — Launch in debug mode. Same parameters as `runJavaApplication` but defaults to `timeout=0` (background). The app stops at breakpoints.
- **launchConfiguration** — Debug an *existing saved* launch configuration by name with `mode="debug"`, preserving its classpath, VM args, environment variables, working directory, and agent settings (e.g. JRebel). Use `listLaunchConfigurations` to find the name. Breakpoints set via `toggleBreakpoint` still apply.

## Stepping (requires suspended thread)

- **stepOver** — Execute current line, don't enter method calls.
- **stepInto** — Enter the method call on the current line.
- **stepReturn** — Run until the current method returns.
- **resumeDebug** — Continue execution until next breakpoint or termination.

## Inspection (requires suspended thread)

- **getStackTrace** — Show all threads with call stack. For the top frame, also shows local variables with their values and types.
- **evaluateExpression** — Evaluate any Java expression in the context of the suspended frame. Examples: `myList.size()`, `x + y`, `this.toString()`, `System.getProperty("user.dir")`.

## Hot Code Replace

- **hotCodeReplace** — Push code changes into the running debug session without restarting. Triggers an incremental build and the JVM reloads changed classes.

## Structural Reloads

- **restartMcpServers** — Rebuild the HTTP MCP server registry after a 500–30,000 ms delay. The delay lets the invoking response complete; clients may need to reconnect.
- **reloadWorkspaceBundle** — Schedule an OSGi update for a bundle whose symbolic name maps to an open workspace project. System bundles, fragments, and installed-only bundles are rejected.
- Use `hotCodeReplace` for supported method-body changes. Use `reloadWorkspaceBundle` when structural changes (new methods, fields, or MCP tools) cannot be hot-swapped, then `restartMcpServers` if the HTTP registry still needs rebuilding.

## Typical Debug Workflow

1. **Set breakpoints** where you want to pause:
   ```
   toggleBreakpoint(projectName="myapp", typeName="com.example.Main", lineNumber="42")
   ```

2. **Launch in debug mode:**
   ```
   debugJavaApplication(projectName="myapp", mainClass="com.example.Main", timeout="0")
   ```

3. **Wait for breakpoint hit**, then inspect:
   ```
   getStackTrace(nameOrClass="Main")
   ```

4. **Evaluate expressions** to understand state:
   ```
   evaluateExpression(nameOrClass="Main", expression="myList.size()")
   ```

5. **Step through code:**
   ```
   stepOver(nameOrClass="Main")
   getStackTrace(nameOrClass="Main")
   ```

6. **Fix code and hot-swap** without restarting:
   ```
   # ... edit the file using eclipse-coder tools ...
   hotCodeReplace(nameOrClass="Main")
   resumeDebug(nameOrClass="Main")
   ```

7. **Clean up:**
   ```
   stopApplication(nameOrClass="Main")
   removeAllBreakpoints()
   ```

## Notes

- All stepping/inspection tools match debug sessions by substring against the launch name or main class (`nameOrClass` parameter)
- `getStackTrace` shows local variables only for the top frame to keep output manageable
- `evaluateExpression` has a 10-second timeout
- Hot code replace works on most JVMs but has limitations (can't change method signatures or add/remove fields in some cases)
- Reload tools are deliberately deferred and may disconnect MCP clients; do not use them while another critical MCP operation is in flight
