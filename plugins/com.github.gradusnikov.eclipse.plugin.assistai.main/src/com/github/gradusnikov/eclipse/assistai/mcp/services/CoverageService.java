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

    public String waitForLatestCoverageFile( long launchStartTime, int maxWaitMs )
    {
        Path basePath = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath()
            .resolve( ".metadata" ).resolve( ".plugins" ).resolve( ECLEMMA_CORE_BUNDLE );

        if ( !Files.exists( basePath ) )
        {
            return null;
        }

        int elapsed = 0;
        int pollInterval = 500;
        while ( elapsed < maxWaitMs )
        {
            try
            {
                var execFile = Files.walk( basePath, 2 )
                    .filter( p -> p.toString().endsWith( ".exec" ) )
                    .filter( p -> {
                        try
                        {
                            return Files.getLastModifiedTime( p ).toMillis() >= launchStartTime;
                        }
                        catch ( IOException e )
                        {
                            return false;
                        }
                    } )
                    .findFirst();
                if ( execFile.isPresent() )
                {
                    return execFile.get().toString();
                }
            }
            catch ( IOException e )
            {
                logger.error( "Error searching for coverage files", e );
                return null;
            }
            try
            {
                Thread.sleep( pollInterval );
            }
            catch ( InterruptedException e )
            {
                Thread.currentThread().interrupt();
                return null;
            }
            elapsed += pollInterval;
        }
        logger.warn( "Coverage exec file not found within " + maxWaitMs + "ms" );
        return null;
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
        return formatCoverageInfo( execFilePath, null );
    }

    public String formatCoverageInfo( String execFilePath, String projectName )
    {
        if ( execFilePath == null )
        {
            return "";
        }

        if ( projectName != null && isCoverageAnalysisAvailable() )
        {
            try
            {
                return new JacocoCoverageAnalyzer( logger ).analyzeCoverage( execFilePath, projectName );
            }
            catch ( Exception | LinkageError e )
            {
                if ( logger != null )
                {
                    try { logger.error( "Error analyzing coverage data, falling back to basic info", e ); }
                    catch ( Exception logEx ) { /* logger not fully initialized */ }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append( "\n--- Coverage ---\n" );
        sb.append( "Coverage data collected. View in Eclipse via Coverage view.\n" );
        sb.append( "Coverage data file: " ).append( execFilePath ).append( "\n" );
        return sb.toString();
    }

    /**
     * Tells whether the optional <code>org.jacoco.core</code> packages are wired
     * to this bundle. They are an optional import, so on an installation without
     * coverage tooling they are absent and {@link JacocoCoverageAnalyzer} cannot
     * be loaded. Callers must consult this before touching that class, otherwise
     * the resulting {@link LinkageError} propagates out of dependency injection
     * and takes down every service that depends on this one.
     */
    boolean isCoverageAnalysisAvailable()
    {
        try
        {
            Class.forName( "org.jacoco.core.analysis.Analyzer", false, getClass().getClassLoader() );
            return true;
        }
        catch ( ClassNotFoundException | LinkageError e )
        {
            return false;
        }
    }
}
