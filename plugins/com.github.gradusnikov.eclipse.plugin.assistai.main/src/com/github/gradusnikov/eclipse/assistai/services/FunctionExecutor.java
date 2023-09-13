package com.github.gradusnikov.eclipse.assistai.services;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
    
    public Object getFunctions()
    {
        return functions;
    }
    
    

    public CompletableFuture<Object> call( String name, Map<String, String> args )
    {
        Method method = getMethod( name ).orElseThrow( () -> new RuntimeException("Function " + name + " not found!" ) ); 
        method.getAnnotationsByType( com.github.gradusnikov.eclipse.assistai.services.FunctionParam.class );
        Object[] argValues = mapArguments( method, args );
            CompletableFuture<Object> future = CompletableFuture.supplyAsync( () -> {
                try
                {
                    return method.invoke( functions, argValues );
                }
                catch ( IllegalAccessException | IllegalArgumentException | InvocationTargetException e )
                {
                    throw new RuntimeException( e );
                }
            } );
            return future;
    }

    
    public CompletableFuture<Object> call( String name, String[] args )
    {
        return call( name, toMap(args) );
    }
    
    public Object[] mapArguments( Method method, Map<String, String> argMap )
    {
        var values = new ArrayList<Object>();
        for ( var parameter : method.getParameters() )
        {
            var annotation = parameter.getAnnotation( com.github.gradusnikov.eclipse.assistai.services.FunctionParam.class );
            if ( annotation != null )
            {
                String paramName = Optional.ofNullable( annotation.name() )
                                           .filter( Predicate.not(String::isBlank))
                                           .orElse( parameter.getName() );
                values.add(  argMap.get( paramName ) );
            }
        }
        return values.toArray();
        
    }
    
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
    
    public Optional<Method> getMethod( String name )
    {
        return Arrays.stream( functions.getClass().getDeclaredMethods() )
                    .filter( method -> {
                        var annotation = method.getAnnotation( com.github.gradusnikov.eclipse.assistai.services.Function.class );
                        var functionName  = Optional.ofNullable( annotation.name() ).filter( Predicate.not(String::isBlank)).orElse( method.getName() );
                        return Objects.nonNull( annotation ) && functionName.equals( name );
                    }).findFirst();
    }
    
}
