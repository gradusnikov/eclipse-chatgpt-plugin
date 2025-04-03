package com.github.gradusnikov.eclipse.assistai.handlers;

import java.io.IOException;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;

import com.github.gradusnikov.eclipse.assistai.tools.ResourceUtilities;

/**
 * Formats the content of Eclipse resource files with line numbers for display.
 * This class provides functionality to format either the entire file or a
 * specific range of lines with proper line numbering and formatting.
 */
public class ResourceFormatter
{
    /**
     * Enumeration defining line numbering options for the formatter.
     */
    public enum Indexing
    {
        /**
         * Zero-based indexing (first line is numbered 0)
         */
        BASED_0(0),

        /**
         * One-based indexing (first line is numbered 1)
         */
        BASED_1(1);

        final int addToIndex;

        /**
         * Constructor for the Indexing enum.
         * 
         * @param addToIndex
         *            The value to add to the index when formatting line numbers
         */
        private Indexing( int addToIndex )
        {
            this.addToIndex = addToIndex;
        }

        /**
         * Applies the indexing offset to a line number.
         * 
         * @param lineNumber
         *            The zero-based line number to adjust
         * @return The adjusted line number according to the indexing style
         */
        public int applyToLine( int lineNumber )
        {
            return lineNumber + addToIndex;
        }
    }

    private final IFile file;

    private Indexing    indexing = Indexing.BASED_1;

    /**
     * Creates a new ResourceFormatter for the specified file.
     * 
     * @param file
     *            The Eclipse IFile resource to format
     */
    public ResourceFormatter( IFile file )
    {
        this.file = file;
    }

    /**
     * Formats the entire content of the file with line numbers.
     * 
     * @return A formatted string containing the file content with line numbers
     * @throws IOException
     *             If an I/O error occurs while reading the file
     * @throws CoreException
     *             If a Core exception occurs
     */
    public String formatFile() throws IOException, CoreException
    {
        var lines = ResourceUtilities.readFileLines( file );
        return formatLines( lines, 0, lines.size() - 1 );
    }

    /**
     * Formats a specific range of lines from the file.
     * 
     * @param from
     *            The zero-based starting line number (inclusive)
     * @param to
     *            The zero-based ending line number (inclusive)
     * @return A formatted string containing the specified range of lines with
     *         line numbers
     * @throws IOException
     *             If an I/O error occurs while reading the file
     * @throws CoreException
     *             If a Core exception occurs
     */
    public String format( int from, int to ) throws IOException, CoreException
    {
        var lines = ResourceUtilities.readFileLines( file );
        return formatLines( lines, from, to );
    }

    /**
     * Formats a list of lines with line numbers.
     * 
     * @param lines
     *            The list of lines to format
     * @param from
     *            The zero-based starting line index (inclusive)
     * @param to
     *            The zero-based ending line index (inclusive)
     * @return A formatted string with line numbers and content
     * @throws IllegalArgumentException
     *             If the line range is invalid
     */
    private String formatLines( List<String> lines, int from, int to )
    {
        if ( from < 0 || from >= lines.size() || to < 0 || to >= lines.size() || from > to )
        {
            throw new IllegalArgumentException( "Illegal line range" );
        }

        var numDigits = Integer.toString( lines.size() ).length();

        var out = new StringBuilder();
        // append header
        out.append( "=== " );
        out.append( " PROJECT: " );
        out.append( file.getProject().getName() );
        out.append( " FILE: " );
        out.append( file.getProjectRelativePath() );
        if ( from > 0 || to < lines.size() - 1 )
        {
            out.append( " (PARTIAL)" );
        }
        out.append( " ===" );
        out.append( " \n" );
        for ( int i = from; i <= to; i++ )
        {
            var lineNumber = indexing.applyToLine( i );
            out.append( String.format( "%0" + numDigits + "d: %s\n", lineNumber, lines.get( i ) ) );
        }

        out.append( " === END FILE === " );
        out.append( " \n" );
        return out.toString();
    }
}
