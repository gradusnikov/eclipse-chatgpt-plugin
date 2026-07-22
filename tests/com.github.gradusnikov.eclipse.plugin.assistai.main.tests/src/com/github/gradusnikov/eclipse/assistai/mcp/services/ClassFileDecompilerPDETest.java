package com.github.gradusnikov.eclipse.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;

import org.eclipse.core.runtime.ILog;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IOrdinaryClassFile;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.Test;

public class ClassFileDecompilerPDETest
{
    @Test
    public void decompilesClassBytesWhenSourceIsUnavailable() throws IOException
    {
        byte[] bytecode;
        try (InputStream input = getClass().getResourceAsStream( "DecompilerFixture.class" ))
        {
            assertTrue( input != null );
            bytecode = input.readAllBytes();
        }

        Optional<String> source = new ClassFileDecompiler().decompile( bytecode, "DecompilerFixture.class" );

        assertTrue( source.isPresent() );
        assertTrue( source.get().contains( "Decompiled by Vineflower" ) );
        assertTrue( source.get().contains( "class DecompilerFixture" ) );
        assertTrue( source.get().contains( "return value + 1" ) );
    }

    @Test
    public void getSourceFallsBackToBinaryClassDecompilation() throws Exception
    {
        byte[] bytecode = readFixtureBytecode();
        IOrdinaryClassFile classFile = proxy( IOrdinaryClassFile.class, ( proxy, method, arguments ) -> switch ( method.getName() )
        {
            case "getBytes" -> bytecode;
            case "getElementName" -> "DecompilerFixture.class";
            default -> defaultValue( method.getReturnType() );
        } );
        IType type = proxy( IType.class, ( proxy, method, arguments ) -> switch ( method.getName() )
        {
            case "getClassFile" -> classFile;
            case "getFullyQualifiedName" -> "example.DecompilerFixture";
            case "getElementName" -> "DecompilerFixture";
            default -> defaultValue( method.getReturnType() );
        } );
        IJavaProject javaProject = proxy( IJavaProject.class,
                ( proxy, method, arguments ) -> method.getName().equals( "findType" ) ? type : defaultValue( method.getReturnType() ) );
        JavaDocService service = new JavaDocService()
        {
            @Override
            public List<IJavaProject> getAvailableJavaProjects()
            {
                return List.of( javaProject );
            }
        };
        Field decompilerField = JavaDocService.class.getDeclaredField( "classFileDecompiler" );
        decompilerField.setAccessible( true );
        decompilerField.set( service, new ClassFileDecompiler() );
        Field loggerField = JavaDocService.class.getDeclaredField( "logger" );
        loggerField.setAccessible( true );
        loggerField.set( service, proxy( ILog.class, ( proxy, method, arguments ) -> {
            if ( arguments != null && arguments.length > 1 && arguments[1] instanceof Throwable cause )
            {
                throw new AssertionError( "Unexpected source lookup error", cause );
            }
            return defaultValue( method.getReturnType() );
        } ) );

        String source = service.getSource( "example.DecompilerFixture" );

        assertTrue( source.contains( "Decompiled by Vineflower" ) );
        assertTrue( source.contains( "class DecompilerFixture" ) );
    }

    @Test
    public void getSourceUsesAttachedSourceBeforeDecompilation()
    {
        String attachedSource = "package example; public class AttachedLibraryClass {}";
        IOrdinaryClassFile classFile = proxy( IOrdinaryClassFile.class, ( proxy, method, arguments ) -> switch ( method.getName() )
        {
            case "getSource" -> attachedSource;
            case "getElementName" -> "AttachedLibraryClass.class";
            default -> defaultValue( method.getReturnType() );
        } );
        IType type = proxy( IType.class, ( proxy, method, arguments ) -> switch ( method.getName() )
        {
            case "getSource" -> throw new AssertionError( "Class-file source should be preferred" );
            case "getClassFile" -> classFile;
            case "getFullyQualifiedName" -> "example.AttachedLibraryClass";
            case "getElementName" -> "AttachedLibraryClass";
            default -> defaultValue( method.getReturnType() );
        } );
        IJavaProject javaProject = proxy( IJavaProject.class,
                ( proxy, method, arguments ) -> method.getName().equals( "findType" ) ? type : defaultValue( method.getReturnType() ) );
        JavaDocService service = new JavaDocService()
        {
            @Override
            public List<IJavaProject> getAvailableJavaProjects()
            {
                return List.of( javaProject );
            }
        };

        String source = service.getSource( "example.AttachedLibraryClass" );

        assertTrue( source.equals( attachedSource ) );
    }

    private byte[] readFixtureBytecode() throws IOException
    {
        try (InputStream input = getClass().getResourceAsStream( "DecompilerFixture.class" ))
        {
            assertTrue( input != null );
            return input.readAllBytes();
        }
    }

    @SuppressWarnings( "unchecked" )
    private static <T> T proxy( Class<T> type, java.lang.reflect.InvocationHandler handler )
    {
        return (T) Proxy.newProxyInstance( type.getClassLoader(), new Class<?>[] { type }, handler );
    }

    private static Object defaultValue( Class<?> type )
    {
        if ( !type.isPrimitive() )
        {
            return null;
        }
        if ( type == boolean.class )
        {
            return false;
        }
        if ( type == char.class )
        {
            return '\0';
        }
        return 0;
    }
}

class DecompilerFixture
{
    int increment( int value )
    {
        return value + 1;
    }
}
