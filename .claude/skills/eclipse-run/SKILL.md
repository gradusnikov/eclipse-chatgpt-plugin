---
name: eclipse-run
description: Launch and stop Java applications in Eclipse. Run with program/VM arguments, capture stdout/stderr, or launch in background.
argument-hint: "[project] [main class]"
allowed-tools: mcp__eclipse-runner__runJavaApplication, mcp__eclipse-runner__launchConfiguration, mcp__eclipse-runner__listLaunchConfigurations, mcp__eclipse-runner__stopApplication, mcp__eclipse-runner__listActiveLaunches, mcp__eclipse-ide__getConsoleOutput
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

- **launchConfiguration** — Launch an *existing saved* launch configuration by name, exactly as it would run from the Run/Debug Configurations dialog. Reuses the configuration's full setup (classpath, VM args, environment variables, working directory, JRebel/agent settings) instead of building a throwaway one.
  - `configurationName` — Exact name of the saved configuration (use `listLaunchConfigurations` to find it)
  - `mode` — `run` or `debug`. Default: `run`
  - `timeout` — Seconds to wait for completion. `0` = background. Default: `0`

- **listLaunchConfigurations** — List saved launch configurations (name, type, and project/main class for Java apps). Use this to discover the exact name for `launchConfiguration`.

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

**Launch an existing saved configuration (preserves its env vars, VM args, JRebel, etc.):**
```
listLaunchConfigurations()
launchConfiguration(configurationName="Run Snapshot App No Data Compass Local", mode="debug", timeout="0")
```

**Stop a running app:**
```
stopApplication(nameOrClass="Main")
```

## Notes

- When `timeout > 0`, the tool waits and returns stdout/stderr and exit code
- When `timeout = 0`, the app runs in background — use `getConsoleOutput` to see output and `stopApplication` to terminate
- Output is truncated at 5000 chars (stdout) / 2000 chars (stderr) to fit context
