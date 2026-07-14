package com.github.gradusnikov.eclipse.assistai.mcp.annotations;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Retention( RUNTIME )
@Target( METHOD )
public @interface Tool
{
    public String name() default "";
    public String description();
    public String type() default "object";

    /**
     * Marks a tool whose execution can outlive an MCP client's tool call timeout
     * (test runs, builds, target platform resolution, workspace-wide searches and
     * refactorings, network calls).
     * <p>
     * Such a tool is run as an Operation: the framework waits inline for at most
     * {@link #inlineWait()} seconds and, if the tool has not finished by then,
     * returns an operation id instead of an error. The tool keeps running, its
     * result is kept, and the caller polls it with getOperationStatus or aborts
     * it with cancelOperation. Without this flag a slow tool is silently
     * abandoned by the client while it carries on running invisibly.
     * <p>
     * The tool method itself stays synchronous - it just returns its result
     * whenever it is ready.
     */
    public boolean longExecution() default false;

    /**
     * Seconds to wait inline before handing the caller an operation id, for tools
     * declaring {@link #longExecution()}. A tool that takes a timeout argument
     * lets the caller override this per call, where timeout=0 hands back the id
     * immediately. Ignored unless {@link #longExecution()} is set.
     */
    public int inlineWait() default 60;

    /**
     * Name of the tool argument, in seconds, that lets the caller override
     * {@link #inlineWait()} for a single call. Set to the empty string for a tool
     * whose own timeout argument means something else - runMavenBuild counts in
     * minutes - so that its value is not misread as an inline wait.
     */
    public String inlineWaitParam() default "timeout";
}
