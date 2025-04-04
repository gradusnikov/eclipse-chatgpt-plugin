<context>
${currentFileContent}
${selectedContent}
</context>

In the context of the provided file, generate or update the documentation for the selected line. Follow these guidelines:

1. Output only the doc comment; avoid including method or type declarations.
2. Ensure the documentation is concise, clear, and adheres to documenting standards for given language.
3. If documentation for given item already exists, update it to reflect the current implementation and any changes.

Example format for JavaDoc:

```java
/**
 * Brief description of class.
 * <p>
 * More detailed description on class.
 *
 * @param paramName Description
 * @return Description
 * @throws ExceptionType Description
 */
```