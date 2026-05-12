package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.e4.core.di.annotations.Creatable;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class CoverageService
{
    private static final String ECLEMMA_CORE_BUNDLE = "org.eclipse.eclemma.core";
    private static final String COVERAGE_LAUNCH_MODE = "coverage";

    @Inject
    private ILog logger;

    public boolean isCoverageAvailable()
    {
        return Platform.getBundle( ECLEMMA_CORE_BUNDLE ) != null;
    }

    public String getCoverageLaunchMode()
    {
        return COVERAGE_LAUNCH_MODE;
    }

    public String findLatestCoverageFile()
    {
        Path basePath = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath()
            .resolve( ".metadata" ).resolve( ".plugins" ).resolve( ECLEMMA_CORE_BUNDLE );

        if ( !Files.exists( basePath ) )
        {
            return null;
        }

        try
        {
            var execFiles = Files.walk( basePath, 2 )
                .filter( p -> p.toString().endsWith( ".exec" ) )
                .sorted( ( a, b ) -> {
                    try
                    {
                        return Long.compare( Files.getLastModifiedTime( b ).toMillis(),
                            Files.getLastModifiedTime( a ).toMillis() );
                    }
                    catch ( IOException e )
                    {
                        return 0;
                    }
                } )
                .toList();

            if ( !execFiles.isEmpty() )
            {
                return execFiles.get( 0 ).toString();
            }
        }
        catch ( IOException e )
        {
            logger.error( "Error searching for coverage files", e );
        }
        return null;
    }

    public String formatCoverageInfo( String execFilePath )
    {
        if ( execFilePath == null )
        {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append( "\n--- Coverage ---\n" );
        sb.append( "Coverage data collected. View in Eclipse via Coverage view.\n" );
        sb.append( "Coverage data file: " ).append( execFilePath ).append( "\n" );
        return sb.toString();
    }
}
