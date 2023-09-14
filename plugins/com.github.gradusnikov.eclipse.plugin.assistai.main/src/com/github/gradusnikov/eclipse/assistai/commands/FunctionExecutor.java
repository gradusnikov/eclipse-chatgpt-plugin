package com.github.gradusnikov.eclipse.assistai.commands;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

public class FunctionExecutor
{
    Object functions;
    
    public FunctionExecutor( Object functions )
    {
        this.functions = functions;
    }
    
    /**
     * Retrieves an array of {@link Method}s that are declared as a function_call
     * callback with the {@link Function} annotation.
     * 
     * @return
     */
    public Method[] getFunctions()
    {
        return Arrays.stream( functions.getClass().getDeclaredMethods() )
                .filter( method -> Objects.nonNull( method.getAnnotation( com.github.gradusnikov.eclipse.assistai.commands.Function.class ) ) )
                .toArray( Method[]::new );
    }
    
    

    public CompletableFuture<Object> call( String name, Map<String, String> args )
    {
        Method method = getFunctionCallbackByName( name ).orElseThrow( () -> new RuntimeException("Function " + name + " not found!" ) ); 
        method.getAnnotationsByType( com.github.gradusnikov.eclipse.assistai.commands.FunctionParam.class );
        Object[] argValues = mapArguments( method, args );
        CompletableFuture<Object> future = CompletableFuture.supplyAsync( () -> invokeMethod( method, argValues ) );
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
    public Object[] mapArguments( Method method, Map<String, String> argMap )
    {
        return Arrays.stream( method.getParameters() )
                    .map( FunctionExecutor::toParamName )
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
    public Map<String, String> toMap( String[] keyVal )
    {
        if ( keyVal.length % 2 != 0 )
        {
            throw new IllegalArgumentException("Not a key-val array");
        }
        var map = new HashMap<String, String>();
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
        return Optional.ofNullable( parameter.getAnnotation( com.github.gradusnikov.eclipse.assistai.commands.FunctionParam.class ) )
                    .map( FunctionParam::name )
                    .filter( Predicate.not( String::isBlank ) )
                    .orElse( parameter.getName() );
    }
    /**
     * Retrieves the name of the function based on the provided Method object.
     *
     * @param method the Method object representing the function
     * @return the name of the function, or the annotated name if present, or the default name if no annotation is found
     */
    public static String toFunctionName( Method method )
    {
        return Optional.ofNullable( method.getAnnotation( com.github.gradusnikov.eclipse.assistai.commands.Function.class ) )
                .map( Function::name )
                .filter( Predicate.not(String::isBlank))
                .orElse( method.getName() );
    }
    
}
