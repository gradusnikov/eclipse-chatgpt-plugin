package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.JavaModelException;
import org.jetbrains.java.decompiler.api.Decompiler;
import org.jetbrains.java.decompiler.main.decompiler.DirectoryResultSaver;

import jakarta.inject.Inject;

import org.eclipse.core.runtime.ILog;

/**
 * Decompiles a JDT binary class when its original source is not attached.
 */
@Creatable
public class ClassFileDecompiler
{
    private static final String DECOMPILED_HEADER = "/* Decompiled by Vineflower. Original source was not attached; names and formatting may differ. */\n";

    @Inject
    private ILog                logger;

    /**
     * Decompiles one class file into Java-like source.
     *
     * @param classFile
     *            the binary class to decompile
     * @return the decompiled source, or an empty optional if decompilation
     *         failed
     */
    public Optional<String> decompile( IClassFile classFile )
    {
        if ( classFile == null )
        {
            return Optional.empty();
        }

        try
        {
            return decompile( classFile.getBytes(), classFile.getElementName() );
        }
        catch ( JavaModelException e )
        {
            logger.error( "Could not read bytecode for " + classFile.getElementName(), e );
            return Optional.empty();
        }
    }

    Optional<String> decompile( byte[] bytecode, String classFileName )
    {
        if ( bytecode == null || bytecode.length == 0 || classFileName == null || classFileName.isBlank() )
        {
            return Optional.empty();
        }

        Path workingDirectory = null;
        try
        {
            workingDirectory = Files.createTempDirectory( "assistai-vineflower-" );
            Path inputFile = workingDirectory.resolve( new File( classFileName ).getName() );
            Path outputDirectory = Files.createDirectory( workingDirectory.resolve( "source" ) );
            Files.write( inputFile, bytecode );

            Decompiler decompiler = new Decompiler.Builder().inputs( inputFile.toFile() ).output( new DirectoryResultSaver( outputDirectory.toFile() ) )
                    .build();
            decompiler.decompile();

            try (Stream<Path> files = Files.walk( outputDirectory ))
            {
                Optional<Path> sourceFile = files.filter( Files::isRegularFile ).filter( path -> path.getFileName().toString().endsWith( ".java" ) )
                        .findFirst();
                if ( sourceFile.isPresent() )
                {
                    String source = Files.readString( sourceFile.get() );
                    if ( !source.isBlank() )
                    {
                        return Optional.of( DECOMPILED_HEADER + source );
                    }
                }
            }
        }
        catch ( Exception e )
        {
            logger.error( "Could not decompile " + classFileName, e );
        }
        finally
        {
            deleteWorkingDirectory( workingDirectory );
        }

        return Optional.empty();
    }

    private void deleteWorkingDirectory( Path workingDirectory )
    {
        if ( workingDirectory == null )
        {
            return;
        }

        try (Stream<Path> paths = Files.walk( workingDirectory ))
        {
            paths.sorted( Comparator.reverseOrder() ).forEach( path -> {
                try
                {
                    Files.deleteIfExists( path );
                }
                catch ( IOException e )
                {
                    logger.warn( "Could not delete decompiler temporary file " + path, e );
                }
            } );
        }
        catch ( IOException e )
        {
            logger.warn( "Could not clean decompiler temporary directory " + workingDirectory, e );
        }
    }
}
