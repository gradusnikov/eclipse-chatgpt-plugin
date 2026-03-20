---
name: eclipse-run
description: Launch and stop Java applications in Eclipse. Run with program/VM arguments, capture stdout/stderr, or launch in background.
argument-hint: [project] [main class]
allowed-tools: mcp__eclipse-runner__runJavaApplication, mcp__eclipse-runner__stopApplication, mcp__eclipse-runner__listActiveLaunches, mcp__eclipse-ide__getConsoleOutput
---

# Run Java Applications in Eclipse

Launch Java applications using Eclipse's launch infrastructure. Applications run inside Eclipse with full classpath resolution.

## Tools

- **runJavaApplication** — Launch a Java app in run mode.
  - `projectName` — Eclipse project name
  - `mainClass` — Fully qualified main class (e.g., `com.example.Main`)
  - `programArgs` — Optional program arguments
  - `vmArgs` — Optional JVM arguments (e.g., `-Xmx512m -Dfoo=bar`)
  - `timeout` — Seconds to wait for completion. `0` = launch in background without waiting. Default: `30`

- **stopApplication** — Terminate a running application by name/class substring match.
  - `nameOrClass` — Substring to match (case-insensitive) against launch name or main class

- **listActiveLaunches** — Show all currently running/debugging sessions with status.

- **getConsoleOutput** — Read output from Eclipse consoles (use after background launches).

## Examples

**Run and capture output:**
```
runJavaApplication(projectName="myapp", mainClass="com.example.Main", timeout="30")
```

**Run in background:**
```
runJavaApplication(projectName="myapp", mainClass="com.example.Server", timeout="0")
```

**Run with arguments:**
```
runJavaApplication(projectName="myapp", mainClass="com.example.CLI", programArgs="--verbose input.txt", vmArgs="-Xmx1g")
```

**Stop a running app:**
```
stopApplication(nameOrClass="Main")
```

## Notes

- When `timeout > 0`, the tool waits and returns stdout/stderr and exit code
- When `timeout = 0`, the app runs in background — use `getConsoleOutput` to see output and `stopApplication` to terminate
- Output is truncated at 5000 chars (stdout) / 2000 chars (stderr) to fit context
