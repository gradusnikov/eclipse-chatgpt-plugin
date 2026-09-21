package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IImportContainer;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.SourceRange;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;

import com.github.gradusnikov.eclipse.assistai.resources.ResourceVersion;
import com.github.gradusnikov.eclipse.assistai.resources.SourceOrigin;

/**
 * The text of one Java type together with a way of finding its members in that text.
 * <p>
 * For a type in a workspace source folder, and for a library type read from its whole
 * attached source file, the Java model's source ranges index this very text and are
 * used as they are. For a decompiled class, or a type whose attachment could only
 * supply the type's own snippet, the model has no ranges that mean anything here, so
 * the text is parsed once and members are matched to declarations by name, and for
 * methods by parameter count and type. The two paths give the same answer to the same
 * question - where does this member start and end - which is all the reading tools
 * need to know.
 * <p>
 * Only {@link SourceOrigin#WORKSPACE_SOURCE} can be edited; the origin travels with the
 * text so a caller never mistakes a rendering of bytecode for a file it can write.
 */
public final class TypeSource
{
    private final IType type;
    private final String content;
    private final SourceOrigin origin;
    private final IFile file;
    private final ICompilationUnit unit;
    private final boolean modelRangesIndexContent;
    private final IDocument document;

    private CompilationUnit ast;

    TypeSource( IType type, String content, SourceOrigin origin, IFile file, ICompilationUnit unit,
                boolean modelRangesIndexContent )
    {
        this.type = type;
        this.content = content;
        this.origin = origin;
        this.file = file;
        this.unit = unit;
        this.modelRangesIndexContent = modelRangesIndexContent;
        this.document = new Document( content );
    }

    public IType type()
    {
        return type;
    }

    public String content()
    {
        return content;
    }

    public SourceOrigin origin()
    {
        return origin;
    }

    /** The workspace file the text was read from, or null for a library type. */
    public IFile file()
    {
        return file;
    }

    /** The platform's line tracker over the text, so CRLF and LF files count lines alike. */
    public IDocument document()
    {
        return document;
    }

    public String projectName()
    {
        return file == null ? null : file.getProject().getName();
    }

    public String filePath()
    {
        return file == null ? null : file.getProjectRelativePath().toString();
    }

    public ResourceVersion version()
    {
        return file == null ? ResourceVersion.UNKNOWN : ResourceVersion.of( file );
    }

    /**
     * Where a member starts and ends in {@link #content()}, Javadoc included, or null
     * when the member has no declaration in the text - which happens when a decompiler
     * dropped or synthesized it.
     */
    public ISourceRange rangeOf( IMember member ) throws JavaModelException
    {
        if ( modelRangesIndexContent )
        {
            ISourceRange range = member.getSourceRange();
            if ( indexesContent( range ) )
            {
                return range;
            }
        }
        ASTNode node = nodeOf( member );
        return node == null ? null : new SourceRange( node.getStartPosition(), node.getLength() );
    }

    /** The member's Javadoc range within {@link #content()}, or null when it has none. */
    public ISourceRange javadocRangeOf( IMember member ) throws JavaModelException
    {
        if ( modelRangesIndexContent && indexesContent( member.getSourceRange() ) )
        {
            ISourceRange range = member.getJavadocRange();
            return indexesContent( range ) ? range : null;
        }
        ASTNode node = nodeOf( member );
        if ( node instanceof BodyDeclaration declaration && declaration.getJavadoc() != null )
        {
            Javadoc javadoc = declaration.getJavadoc();
            return new SourceRange( javadoc.getStartPosition(), javadoc.getLength() );
        }
        return null;
    }

    /** The range covering every import declaration, or null when there are none. */
    public ISourceRange importsRange() throws JavaModelException
    {
        if ( unit != null )
        {
            IImportContainer imports = unit.getImportContainer();
            return imports != null && imports.exists() ? imports.getSourceRange() : null;
        }
        @SuppressWarnings( "unchecked" )
        List<ImportDeclaration> imports = ast().imports();
        if ( imports.isEmpty() )
        {
            return null;
        }
        ImportDeclaration first = imports.get( 0 );
        ImportDeclaration last = imports.get( imports.size() - 1 );
        int end = last.getStartPosition() + last.getLength();
        return new SourceRange( first.getStartPosition(), end - first.getStartPosition() );
    }

    public String textOf( ISourceRange range )
    {
        return content.substring( range.getOffset(), range.getOffset() + range.getLength() );
    }

    private boolean indexesContent( ISourceRange range )
    {
        return range != null && range.getOffset() >= 0 && range.getLength() > 0
                && range.getOffset() + range.getLength() <= content.length();
    }

    // ---- locating members by parsing the text -------------------------------

    private CompilationUnit ast()
    {
        if ( ast == null )
        {
            ASTParser parser = ASTParser.newParser( AST.getJLSLatest() );
            parser.setKind( ASTParser.K_COMPILATION_UNIT );
            parser.setSource( content.toCharArray() );
            parser.setResolveBindings( false );
            parser.setStatementsRecovery( true );
            Map<String, String> options = JavaCore.getOptions();
            JavaCore.setComplianceOptions( JavaCore.latestSupportedJavaVersion(), options );
            parser.setCompilerOptions( options );
            ast = (CompilationUnit) parser.createAST( null );
        }
        return ast;
    }

    private ASTNode nodeOf( IMember member ) throws JavaModelException
    {
        IType declaring = member instanceof IType t ? t : member.getDeclaringType();
        if ( declaring == null )
        {
            return null;
        }
        List<String> names = new ArrayList<>();
        for ( IType t = declaring; t != null; t = t.getDeclaringType() )
        {
            if ( t.getElementName().isEmpty() )
            {
                return null; // anonymous: nothing to match a name against
            }
            names.add( 0, t.getElementName() );
        }
        @SuppressWarnings( "unchecked" )
        AbstractTypeDeclaration typeNode = findType( ast().types(), names, 0 );
        if ( member instanceof IType || typeNode == null )
        {
            return typeNode;
        }
        if ( member instanceof IMethod method )
        {
            return findMethod( typeNode, method );
        }
        if ( member instanceof IField field )
        {
            return findField( typeNode, field );
        }
        return null;
    }

    private static AbstractTypeDeclaration findType( List<?> declarations, List<String> names, int depth )
    {
        for ( Object declaration : declarations )
        {
            if ( declaration instanceof AbstractTypeDeclaration typeNode
                    && typeNode.getName().getIdentifier().equals( names.get( depth ) ) )
            {
                if ( depth == names.size() - 1 )
                {
                    return typeNode;
                }
                AbstractTypeDeclaration nested = findType( typeNode.bodyDeclarations(), names, depth + 1 );
                if ( nested != null )
                {
                    return nested;
                }
            }
        }
        return null;
    }

    private static ASTNode findMethod( AbstractTypeDeclaration typeNode, IMethod method ) throws JavaModelException
    {
        List<MethodDeclaration> byName = new ArrayList<>();
        for ( Object declaration : typeNode.bodyDeclarations() )
        {
            if ( declaration instanceof MethodDeclaration candidate
                    && candidate.getName().getIdentifier().equals( method.getElementName() ) )
            {
                byName.add( candidate );
            }
        }
        if ( byName.size() <= 1 )
        {
            return byName.isEmpty() ? null : byName.get( 0 );
        }

        // Overloads: narrow by parameter count, then by the erased simple type names.
        String[] parameterTypes = method.getParameterTypes();
        List<MethodDeclaration> byCount = new ArrayList<>();
        for ( MethodDeclaration candidate : byName )
        {
            if ( candidate.parameters().size() == parameterTypes.length )
            {
                byCount.add( candidate );
            }
        }
        if ( byCount.size() == 1 )
        {
            return byCount.get( 0 );
        }
        List<String> wanted = new ArrayList<>();
        for ( String signature : parameterTypes )
        {
            wanted.add( Signature.getSimpleName( Signature.toString( Signature.getTypeErasure( signature ) ) ) );
        }
        for ( MethodDeclaration candidate : byCount )
        {
            if ( simpleParameterTypes( candidate ).equals( wanted ) )
            {
                return candidate;
            }
        }
        return byCount.isEmpty() ? byName.get( 0 ) : byCount.get( 0 );
    }

    private static List<String> simpleParameterTypes( MethodDeclaration declaration )
    {
        List<String> names = new ArrayList<>();
        for ( Object parameter : declaration.parameters() )
        {
            SingleVariableDeclaration variable = (SingleVariableDeclaration) parameter;
            String typeName = variable.getType().toString();
            int generic = typeName.indexOf( '<' );
            if ( generic >= 0 )
            {
                typeName = typeName.substring( 0, generic ) + typeName.substring( typeName.lastIndexOf( '>' ) + 1 );
            }
            typeName = typeName.substring( typeName.lastIndexOf( '.' ) + 1 );
            StringBuilder name = new StringBuilder( typeName );
            for ( int i = 0; i < variable.getExtraDimensions(); i++ )
            {
                name.append( "[]" );
            }
            if ( variable.isVarargs() )
            {
                name.append( "[]" );
            }
            names.add( name.toString() );
        }
        return names;
    }

    private static ASTNode findField( AbstractTypeDeclaration typeNode, IField field )
    {
        String name = field.getElementName();
        for ( Object declaration : typeNode.bodyDeclarations() )
        {
            if ( declaration instanceof FieldDeclaration fieldNode )
            {
                for ( Object fragment : fieldNode.fragments() )
                {
                    if ( ( (VariableDeclarationFragment) fragment ).getName().getIdentifier().equals( name ) )
                    {
                        return fieldNode;
                    }
                }
            }
        }
        if ( typeNode instanceof EnumDeclaration enumNode )
        {
            for ( Object constant : enumNode.enumConstants() )
            {
                if ( ( (EnumConstantDeclaration) constant ).getName().getIdentifier().equals( name ) )
                {
                    return (EnumConstantDeclaration) constant;
                }
            }
        }
        return null;
    }
}
