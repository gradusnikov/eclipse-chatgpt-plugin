---
name: eclipse-test
description: Run JUnit tests in Eclipse projects — all tests, by package, by class, or individual test methods. Also build Maven projects.
argument-hint: [project] [class or package]
allowed-tools: mcp__eclipse-ide__runAllTests, mcp__eclipse-ide__runPackageTests, mcp__eclipse-ide__runClassTests, mcp__eclipse-ide__runTestMethod, mcp__eclipse-ide__findTestClasses, mcp__eclipse-ide__runMavenBuild, mcp__eclipse-ide__getConsoleOutput, mcp__eclipse-ide__getEffectivePom, mcp__eclipse-ide__listMavenProjects, mcp__eclipse-ide__getProjectDependencies, mcp__eclipse-ide__getCompilationErrors
---

# Run Tests & Build in Eclipse

Execute JUnit tests and Maven builds using Eclipse's built-in infrastructure.

## Test Tools

- **runAllTests** — Run all tests in a project. Provide `projectName` and optional `timeout` (default: 60s).
- **runPackageTests** — Run tests in a specific package. Provide `projectName` and `packageName`.
- **runClassTests** — Run all tests in a specific test class. Provide `projectName` and `className` (fully qualified).
- **runTestMethod** — Run a single test method. Provide `projectName`, `className`, and `methodName`.
- **findTestClasses** — Find all test classes in a project.

## Build Tools

- **runMavenBuild** — Run Maven with specified goals. Provide `projectName`, `goals` (e.g., `clean install`), optional `profiles`, and `timeout`.
- **getEffectivePom** — Get the effective POM for a Maven project.
- **listMavenProjects** — List all Maven projects in the workspace.
- **getProjectDependencies** — Get Maven dependencies for a project.

## Typical Workflow

1. **Find test classes:**
   ```
   findTestClasses(projectName="myapp")
   ```

2. **Run specific test after code change:**
   ```
   runClassTests(projectName="myapp", className="com.example.MyServiceTest", timeout="60")
   ```

3. **Run all tests before committing:**
   ```
   runAllTests(projectName="myapp", timeout="120")
   ```

4. **Build with Maven:**
   ```
   runMavenBuild(projectName="myapp", goals="clean verify", timeout="300")
   ```

5. **Check build output:**
   ```
   getConsoleOutput()
   ```

## Notes

- Tests run using JUnit 5 by default
- Test results include pass/fail status, execution time, and failure traces
- Use `getCompilationErrors` first to ensure code compiles before running tests
- Maven build output goes to the Eclipse console — use `getConsoleOutput` to retrieve it
