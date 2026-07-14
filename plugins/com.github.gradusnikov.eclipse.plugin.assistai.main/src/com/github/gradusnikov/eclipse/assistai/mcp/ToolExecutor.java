package com.github.gradusnikov.eclipse.assistai.mcp;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.github.gradusnikov.eclipse.assistai.mcp.operations.Operation;
import com.github.gradusnikov.eclipse.assistai.mcp.operations.OperationContext;
import java.util.function.Predicate;

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;

public class ToolExecutor
{
    /**
     * Tool bodies run here rather than on {@link java.util.concurrent.ForkJoinPool}'s
     * common pool, which they used to occupy: a long execution tool parks its worker
     * for as long as the underlying build, search or test run takes, and the common
     * pool is shared with the rest of the JVM.
     * <p>
     * The pool grows on demand on purpose. A bounded one would let a handful of slow
     * tools fill every slot and queue up the very calls needed to poll or cancel them.
     */
    private static final ExecutorService TOOL_EXECUTOR = Executors.newCachedThreadPool( new ToolThreadFactory() );

    Object functions;
    
    public ToolExecutor( Object functions )
    {
        this.functions = functions;
    }
    
    /**
     * Retrieves an array of {@link Method}s that are declared as a function_call
     * callback with the {@link Tool} annotation.
     * 
     * @return
     */
    public Method[] getFunctions()
    {
        return Arrays.stream( functions.getClass().getDeclaredMethods() )
                .filter( method -> Objects.nonNull( method.getAnnotation( com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool.class ) ) )
                .toArray( Method[]::new );
    }
    
    

    public CompletableFuture<Object> call( String name, Map<String, Object> args )
    {
        return call( name, args, null );
    }

    /**
     * Invokes a tool, optionally as an {@link Operation}.
     * <p>
     * When an operation is given it is bound to the worker thread for the duration of
     * the call, so the tool - or any service beneath it - can reach it through
     * {@link OperationContext} to publish progress, attach output or register a cancel
     * hook, without any of them having to take it as a parameter.
     */
    public CompletableFuture<Object> call( String name, Map<String, Object> args, Operation operation )
    {
        Method method = getFunctionCallbackByName( name ).orElseThrow( () -> new RuntimeException("Tool " + name + " not found!" ) ); 
        method.getAnnotationsByType( com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam.class );
        Object[] argValues = mapArguments( method, args );
        Supplier<Object> body = () -> invokeMethod( method, argValues );
        Supplier<Object> task = operation == null ? body : () -> {
            // The worker has to be reachable for cancellation to interrupt it.
            operation.attachWorkerThread( Thread.currentThread() );
            try
            {
                return OperationContext.callWith( operation, body );
            }
            finally
            {
                operation.attachWorkerThread( null );
                // Do not leave a pending interrupt on a pooled thread.
                Thread.interrupted();
            }
        };
        CompletableFuture<Object> future = CompletableFuture.supplyAsync( task, TOOL_EXECUTOR );
        return future;
    }
    private Object invokeMethod( Method method, Object[] args )
    {
        try
        {
            return method.invoke( functions, args );
        }
        catch ( IllegalAccessException | IllegalArgumentException | InvocationTargetException e )
        {
            throw new RuntimeException( e );
        }
        
    }
    
    public CompletableFuture<Object> call( String name, String[] args )
    {
        return call( name, toMap(args) );
    }
    
    /**
     * Creates an array of parameter values as declared by the callback {@link Method}
     * 
     * @param method
     * @param argMap
     * @return
     */
    public Object[] mapArguments( Method method, Map<String, Object> argMap )
    {
        return Arrays.stream( method.getParameters() )
                    .map( ToolExecutor::toParamName )
                    .map( argMap::get )
                    .toArray();
        
    }

    /**
     * Converts a String array of key-value pairs into a Map.
     * 
     * @param keyVal the String array of key-value pairs
     * @return the Map representation of the key-value pairs
     * @throws IllegalArgumentException if the input array is not a key-value array
     */
    public Map<String, Object> toMap( String[] keyVal )
    {
        if ( keyVal.length % 2 != 0 )
        {
            throw new IllegalArgumentException("Not a key-val array");
        }
        var map = new HashMap<String, Object>();
        for (int i = 0; i < keyVal.length; i += 2) 
        {
            map.put(keyVal[i], keyVal[i + 1]);
        }
        return map;
    }

    /**
     * Retrieves the function callback method with the specified name.
     *
     * @param name the name of the function
     * @return an Optional containing the function callback method, or an empty Optional if the function is not found
     */
    public Optional<Method> getFunctionCallbackByName( String name )
    {
        return Arrays.stream( getFunctions() )
                     .filter( method -> toFunctionName( method ).equals( name ) )
                     .findFirst();
    }
    /**
     * Converts a Parameter object to its corresponding parameter name.
     *
     * @param parameter the Parameter object
     * @return the parameter name, or the annotated name if present, or the default name if no annotation is found
     */
    public static String toParamName( Parameter parameter )
    {
        return Optional.ofNullable( parameter.getAnnotation( com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam.class ) )
                    .map( ToolParam::name )
                    .filter( Predicate.not( String::isBlank ) )
                    .orElse( parameter.getName() );
    }
    /**
     * The {@link Tool} annotation of a tool, which carries whether it may run long and
     * how long to wait for it inline before handing the caller an operation id.
     */
    public Optional<Tool> getToolAnnotation( String name )
    {
        return getFunctionCallbackByName( name )
                .map( method -> method.getAnnotation( com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool.class ) );
    }

    /** Names the tool threads, so a stuck tool is identifiable in a thread dump. */
    private static final class ToolThreadFactory implements ThreadFactory
    {
        private final AtomicLong counter = new AtomicLong();

        @Override
        public Thread newThread( Runnable runnable )
        {
            Thread thread = new Thread( runnable, "assistai-mcp-tool-" + counter.incrementAndGet() );
            thread.setDaemon( true );
            return thread;
        }
    }

    /**
     * Retrieves the name of the function based on the provided Method object.
     *
     * @param method the Method object representing the function
     * @return the name of the function, or the annotated name if present, or the default name if no annotation is found
     */
    public static String toFunctionName( Method method )
    {
        return Optional.ofNullable( method.getAnnotation( com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool.class ) )
                .map( Tool::name )
                .filter( Predicate.not(String::isBlank))
                .orElse( method.getName() );
    }
    
}
