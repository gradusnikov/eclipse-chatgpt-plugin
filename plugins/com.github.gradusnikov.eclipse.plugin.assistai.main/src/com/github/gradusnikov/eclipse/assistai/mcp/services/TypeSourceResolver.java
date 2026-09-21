package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.Optional;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;

import com.github.gradusnikov.eclipse.assistai.resources.SourceOrigin;
import com.github.gradusnikov.eclipse.assistai.services.AiIgnoreService;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Finds the text a Java type can be read from, whatever form the type takes.
 * <p>
 * A type in a workspace source folder is read from its compilation unit. A type in a
 * library is read from the library's source attachment when it has one, and is
 * decompiled when it does not - the same order {@code getSource} follows. The outline,
 * method-source and filtered-source tools used to turn every binary type away with
 * "no attached source, use getSource", which was wrong for a library with attached
 * source and unhelpful for a large decompiled class, since {@code getSource} can only
 * return all of it.
 * <p>
 * The result is a {@link TypeSource}, which knows how to locate each member in the
 * text it holds: from the Java model where the model's offsets describe that text, and
 * from a parse of the text where they do not.
 */
@Creatable
@Singleton
public class TypeSourceResolver
{
    @Inject
    ILog logger;

    @Inject
    AiIgnoreService aiIgnoreService;

    @Inject
    ClassFileDecompiler classFileDecompiler;

    public enum Status
    {
        /** Source text was found; {@link Resolution#source()} holds it. */
        RESOLVED,
        /** The type's file is excluded from AI processing by .aiignore. */
        EXCLUDED,
        /** The type is binary, has no attached source and could not be decompiled. */
        NO_SOURCE
    }

    /**
     * @param excludedFile the file .aiignore excludes, when {@code status} is
     *            {@link Status#EXCLUDED}; null otherwise
     */
    public record Resolution( Status status, TypeSource source, IFile excludedFile )
    {
        static Resolution resolved( TypeSource source )
        {
            return new Resolution( Status.RESOLVED, source, null );
        }

        static Resolution excluded( IFile file )
        {
            return new Resolution( Status.EXCLUDED, null, file );
        }

        static Resolution noSource()
        {
            return new Resolution( Status.NO_SOURCE, null, null );
        }

        public boolean isResolved()
        {
            return status == Status.RESOLVED;
        }
    }

    /**
     * Resolves the text of one type.
     *
     * @param type a type some open project's classpath supplied
     * @return where the text came from and, when it was found, the text itself
     */
    public Resolution resolve( IType type ) throws JavaModelException
    {
        ICompilationUnit unit = type.getCompilationUnit();
        if ( unit != null )
        {
            IResource resource = unit.getResource();
            IFile file = resource instanceof IFile f ? f : null;
            if ( resource != null && aiIgnoreService.isExcluded( resource ) )
            {
                return Resolution.excluded( file );
            }
            return Resolution.resolved( new TypeSource( type, unit.getBuffer().getContents(),
                    SourceOrigin.WORKSPACE_SOURCE, file, unit, true ) );
        }

        IClassFile classFile = type.getClassFile();
        if ( classFile != null )
        {
            // The whole attached file, which is what the model's member offsets index.
            String attached = classFile.getSource();
            if ( attached != null && !attached.isBlank() )
            {
                return Resolution.resolved( new TypeSource( type, attached, SourceOrigin.ATTACHED_SOURCE, null, null, true ) );
            }
        }

        // The type's own snippet. Member offsets index the file it was cut from, not
        // the snippet, so members are located by parsing the snippet instead.
        String snippet = type.getSource();
        if ( snippet != null && !snippet.isBlank() )
        {
            return Resolution.resolved( new TypeSource( type, snippet, SourceOrigin.ATTACHED_SOURCE, null, null, false ) );
        }

        if ( classFile != null )
        {
            Optional<String> decompiled = classFileDecompiler.decompile( classFile );
            if ( decompiled.isPresent() )
            {
                return Resolution.resolved( new TypeSource( type, decompiled.get(), SourceOrigin.DECOMPILED_CLASS, null, null, false ) );
            }
        }

        return Resolution.noSource();
    }
}
