package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IImportContainer;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeParameter;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jface.text.Document;

import com.github.gradusnikov.eclipse.assistai.resources.ResourceToolResult;
import com.github.gradusnikov.eclipse.assistai.services.AiIgnoreService;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class OutlineService
{
    @Inject
    ILog logger;

    @Inject
    AiIgnoreService aiIgnoreService;

    /**
     * Returns a compact outline of a Java class: class declaration, fields,
     * method signatures (no bodies), and inner types â all with line numbers.
     */
    public ResourceToolResult getClassOutline(String fullyQualifiedClassName, boolean includeFields)
    {
        final String toolName = "getClassOutline";

        for (IJavaProject javaProject : getAvailableJavaProjects())
        {
            try
            {
                IType type = javaProject.findType(fullyQualifiedClassName);
                if (type == null)
                    continue;

                ICompilationUnit cu = type.getCompilationUnit();
                if (cu == null)
                    continue;

                if (cu.getResource() != null && aiIgnoreService.isExcluded(cu.getResource()))
                {
                    return ResourceToolResult.transientResult(
                            "Access denied: '" + fullyQualifiedClassName + "' is excluded from AI processing by .aiignore.", toolName);
                }

                String source = cu.getBuffer().getContents();
                Document doc = new Document(source);

                StringBuilder result = new StringBuilder();

                ISourceRange sourceRange = type.getSourceRange();
                int classStartLine = doc.getLineOfOffset(sourceRange.getOffset()) + 1;
                int classEndLine = doc.getLineOfOffset(sourceRange.getOffset() + sourceRange.getLength() - 1) + 1;

                result.append("=== ").append(fullyQualifiedClassName)
                      .append(" (lines ").append(classStartLine).append("-").append(classEndLine).append(") ===\n\n");

                result.append(String.format("  %4d: %s\n\n", classStartLine, formatClassDeclaration(type)));

                if (includeFields)
                {
                    IField[] fields = type.getFields();
                    if (fields.length > 0)
                    {
                        result.append("  Fields:\n");
                        for (IField field : fields)
                        {
                            int fieldLine = doc.getLineOfOffset(field.getSourceRange().getOffset()) + 1;
                            result.append(String.format("    %4d: %s\n", fieldLine, formatFieldDeclaration(field)));
                        }
                        result.append("\n");
                    }
                }

                IMethod[] methods = type.getMethods();
                if (methods.length > 0)
                {
                    result.append("  Methods:\n");
                    for (IMethod method : methods)
                    {
                        ISourceRange methodRange = method.getSourceRange();
                        int methodStartLine = doc.getLineOfOffset(methodRange.getOffset()) + 1;
                        int methodEndLine = doc.getLineOfOffset(
                                methodRange.getOffset() + methodRange.getLength() - 1) + 1;
                        result.append(String.format("    %4d-%4d: %s\n",
                                methodStartLine, methodEndLine, formatMethodSignature(method)));
                    }
                    result.append("\n");
                }

                IType[] innerTypes = type.getTypes();
                if (innerTypes.length > 0)
                {
                    result.append("  Inner Types:\n");
                    for (IType innerType : innerTypes)
                    {
                        ISourceRange innerRange = innerType.getSourceRange();
                        int innerStartLine = doc.getLineOfOffset(innerRange.getOffset()) + 1;
                        int innerEndLine = doc.getLineOfOffset(
                                innerRange.getOffset() + innerRange.getLength() - 1) + 1;
                        result.append(String.format("    %4d-%4d: %s\n",
                                innerStartLine, innerEndLine, formatClassDeclaration(innerType)));
                    }
                }

                return ResourceToolResult.fromJavaType(type, result.toString(), toolName);
            }
            catch (Exception e)
            {
                logger.error(e.getMessage(), e);
            }
        }

        return ResourceToolResult.transientResult("Type not found: " + fullyQualifiedClassName, toolName);
    }

    /**
     * Returns source code for specific method(s) with line numbers.
     * Accepts comma-separated method names to retrieve multiple methods in one call.
     */
    public ResourceToolResult getMethodSource(String fullyQualifiedClassName, String methodNames,
            String methodSignature, boolean includeJavadoc)
    {
        final String toolName = "getMethodSource";

        Set<String> requestedMethods = Arrays.stream(methodNames.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        if (requestedMethods.isEmpty())
        {
            return ResourceToolResult.transientResult("No method names specified.", toolName);
        }

        for (IJavaProject javaProject : getAvailableJavaProjects())
        {
            try
            {
                IType type = javaProject.findType(fullyQualifiedClassName);
                if (type == null)
                    continue;

                ICompilationUnit cu = type.getCompilationUnit();
                if (cu == null)
                    continue;

                if (cu.getResource() != null && aiIgnoreService.isExcluded(cu.getResource()))
                {
                    return ResourceToolResult.transientResult(
                            "Access denied: '" + fullyQualifiedClassName + "' is excluded from AI processing by .aiignore.", toolName);
                }

                String source = cu.getBuffer().getContents();
                String[] lines = source.split("\n", -1);
                Document doc = new Document(source);
                int width = String.valueOf(lines.length).length();

                StringBuilder result = new StringBuilder();
                List<String> found = new ArrayList<>();
                List<String> notFound = new ArrayList<>(requestedMethods);

                for (IMethod method : type.getMethods())
                {
                    if (!requestedMethods.contains(method.getElementName()))
                        continue;

                    if (methodSignature != null && !methodSignature.isEmpty())
                    {
                        String params = formatMethodParams(method);
                        if (!params.contains(methodSignature))
                            continue;
                    }

                    found.add(method.getElementName());
                    notFound.remove(method.getElementName());

                    ISourceRange range = method.getSourceRange();
                    int startOffset = range.getOffset();
                    int endOffset = startOffset + range.getLength();

                    if (includeJavadoc)
                    {
                        ISourceRange javadocRange = method.getJavadocRange();
                        if (javadocRange != null)
                        {
                            startOffset = Math.min(startOffset, javadocRange.getOffset());
                        }
                    }

                    int startLine = doc.getLineOfOffset(startOffset) + 1;
                    int endLine = doc.getLineOfOffset(endOffset - 1) + 1;

                    result.append("// ").append(type.getElementName()).append(".")
                          .append(method.getElementName())
                          .append(" (lines ").append(startLine).append("-").append(endLine).append(")\n");

                    for (int i = startLine - 1; i < endLine && i < lines.length; i++)
                    {
                        result.append(String.format("%" + width + "d\t%s\n", i + 1, lines[i]));
                    }
                    result.append("\n");
                }

                if (found.isEmpty())
                {
                    return ResourceToolResult.transientResult(
                            "Method(s) '" + methodNames + "' not found in " + fullyQualifiedClassName, toolName);
                }

                if (!notFound.isEmpty())
                {
                    result.append("// Not found: ").append(String.join(", ", notFound)).append("\n");
                }

                return ResourceToolResult.fromJavaType(type, result.toString(), toolName);
            }
            catch (Exception e)
            {
                logger.error(e.getMessage(), e);
            }
        }

        return ResourceToolResult.transientResult("Type not found: " + fullyQualifiedClassName, toolName);
    }

    /**
     * Returns source with optional import exclusion and selective method expansion.
     * Methods not in the expand list are collapsed to signature + opening/closing braces.
     * Line numbers always match the original file for accurate editing.
     *
     * @param methodNames comma-separated method names to expand; null/empty = expand all
     */
    public ResourceToolResult getFilteredSource(String fullyQualifiedClassName,
            boolean excludeImports, String methodNames)
    {
        final String toolName = "getFilteredSource";

        Set<String> expandMethods = (methodNames != null && !methodNames.isBlank())
                ? Arrays.stream(methodNames.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet())
                : Collections.emptySet();
        boolean expandAll = expandMethods.isEmpty();

        for (IJavaProject javaProject : getAvailableJavaProjects())
        {
            try
            {
                IType type = javaProject.findType(fullyQualifiedClassName);
                if (type == null)
                    continue;

                ICompilationUnit cu = type.getCompilationUnit();
                if (cu == null)
                    continue;

                if (cu.getResource() != null && aiIgnoreService.isExcluded(cu.getResource()))
                {
                    return ResourceToolResult.transientResult(
                            "Access denied: '" + fullyQualifiedClassName + "' is excluded from AI processing by .aiignore.", toolName);
                }

                String source = cu.getBuffer().getContents();
                String[] lines = source.split("\n", -1);
                Document doc = new Document(source);
                int width = String.valueOf(lines.length).length();

                // Collapse ranges: startLine -> endLine (1-based, inclusive)
                TreeMap<Integer, Integer> collapseRanges = new TreeMap<>();

                if (excludeImports)
                {
                    IImportContainer importContainer = cu.getImportContainer();
                    if (importContainer != null && importContainer.exists())
                    {
                        ISourceRange importRange = importContainer.getSourceRange();
                        int importStart = doc.getLineOfOffset(importRange.getOffset()) + 1;
                        int importEnd = doc.getLineOfOffset(
                                importRange.getOffset() + importRange.getLength() - 1) + 1;
                        collapseRanges.put(importStart, importEnd);
                    }
                }

                if (!expandAll)
                {
                    for (IMethod method : type.getMethods())
                    {
                        if (expandMethods.contains(method.getElementName()))
                            continue;

                        ISourceRange range = method.getSourceRange();
                        String methodSource = method.getSource();
                        if (methodSource == null)
                            continue;

                        int braceIndex = findOpeningBrace(methodSource);
                        if (braceIndex < 0)
                            continue;

                        int methodStartOffset = range.getOffset();
                        int braceLine = doc.getLineOfOffset(methodStartOffset + braceIndex) + 1;
                        int methodEndLine = doc.getLineOfOffset(
                                methodStartOffset + range.getLength() - 1) + 1;

                        // Collapse from line after opening brace to line before closing brace
                        int bodyStart = braceLine + 1;
                        int bodyEnd = methodEndLine - 1;

                        if (bodyStart <= bodyEnd)
                        {
                            collapseRanges.put(bodyStart, bodyEnd);
                        }
                    }
                }

                StringBuilder result = new StringBuilder();
                int i = 0;

                while (i < lines.length)
                {
                    int lineNum = i + 1;

                    var entry = collapseRanges.floorEntry(lineNum);
                    if (entry != null && lineNum >= entry.getKey() && lineNum <= entry.getValue())
                    {
                        result.append(String.format("%" + width + "s\t    // ... (lines %d-%d)\n",
                                "", entry.getKey(), entry.getValue()));
                        i = entry.getValue();
                        continue;
                    }

                    result.append(String.format("%" + width + "d\t%s\n", lineNum, lines[i]));
                    i++;
                }

                return ResourceToolResult.fromJavaType(type, result.toString(), toolName);
            }
            catch (Exception e)
            {
                logger.error(e.getMessage(), e);
            }
        }

        return ResourceToolResult.transientResult("Type not found: " + fullyQualifiedClassName, toolName);
    }

    // --- Formatting helpers ---

    private String formatClassDeclaration(IType type) throws JavaModelException
    {
        StringBuilder decl = new StringBuilder();

        for (IAnnotation ann : type.getAnnotations())
        {
            decl.append("@").append(ann.getElementName()).append(" ");
        }

        int flags = type.getFlags();
        if (type.isInterface())
        {
            flags &= ~Flags.AccAbstract;
        }
        String modifiers = Flags.toString(flags);
        if (!modifiers.isEmpty())
        {
            decl.append(modifiers).append(" ");
        }

        if (type.isAnnotation())
        {
            decl.append("@interface ");
        }
        else if (type.isInterface())
        {
            decl.append("interface ");
        }
        else if (type.isEnum())
        {
            decl.append("enum ");
        }
        else if (type.isRecord())
        {
            decl.append("record ");
        }
        else
        {
            decl.append("class ");
        }

        decl.append(type.getElementName());

        ITypeParameter[] typeParams = type.getTypeParameters();
        if (typeParams.length > 0)
        {
            decl.append("<");
            for (int i = 0; i < typeParams.length; i++)
            {
                if (i > 0)
                    decl.append(", ");
                decl.append(typeParams[i].getElementName());
                String[] bounds = typeParams[i].getBounds();
                if (bounds.length > 0)
                {
                    decl.append(" extends ").append(String.join(" & ", bounds));
                }
            }
            decl.append(">");
        }

        String superclass = type.getSuperclassName();
        if (superclass != null && !"Object".equals(superclass))
        {
            decl.append(" extends ").append(superclass);
        }

        String[] interfaces = type.getSuperInterfaceNames();
        if (interfaces.length > 0)
        {
            decl.append(type.isInterface() ? " extends " : " implements ");
            decl.append(String.join(", ", interfaces));
        }

        return decl.toString();
    }

    private String formatFieldDeclaration(IField field) throws JavaModelException
    {
        if (field.isEnumConstant())
        {
            return field.getElementName();
        }

        StringBuilder decl = new StringBuilder();

        for (IAnnotation ann : field.getAnnotations())
        {
            decl.append("@").append(ann.getElementName()).append(" ");
        }

        String modifiers = Flags.toString(field.getFlags());
        if (!modifiers.isEmpty())
        {
            decl.append(modifiers).append(" ");
        }

        decl.append(Signature.toString(field.getTypeSignature()));
        decl.append(" ").append(field.getElementName());

        Object constantValue = field.getConstant();
        if (constantValue != null)
        {
            if (constantValue instanceof String)
            {
                decl.append(" = \"").append(constantValue).append("\"");
            }
            else
            {
                decl.append(" = ").append(constantValue);
            }
        }

        return decl.toString();
    }

    private String formatMethodSignature(IMethod method) throws JavaModelException
    {
        StringBuilder sig = new StringBuilder();

        for (IAnnotation ann : method.getAnnotations())
        {
            sig.append("@").append(ann.getElementName()).append(" ");
        }

        String modifiers = Flags.toString(method.getFlags());
        if (!modifiers.isEmpty())
        {
            sig.append(modifiers).append(" ");
        }

        if (!method.isConstructor())
        {
            sig.append(Signature.toString(method.getReturnType())).append(" ");
        }

        sig.append(method.getElementName());
        sig.append("(").append(formatMethodParams(method)).append(")");

        String[] exceptions = method.getExceptionTypes();
        if (exceptions.length > 0)
        {
            sig.append(" throws ");
            for (int i = 0; i < exceptions.length; i++)
            {
                if (i > 0)
                    sig.append(", ");
                sig.append(Signature.toString(exceptions[i]));
            }
        }

        return sig.toString();
    }

    private String formatMethodParams(IMethod method) throws JavaModelException
    {
        StringBuilder params = new StringBuilder();
        String[] paramTypes = method.getParameterTypes();
        String[] paramNames = method.getParameterNames();

        for (int i = 0; i < paramTypes.length; i++)
        {
            if (i > 0)
                params.append(", ");
            params.append(Signature.toString(paramTypes[i]));
            if (i < paramNames.length)
            {
                params.append(" ").append(paramNames[i]);
            }
        }

        return params.toString();
    }

    private int findOpeningBrace(String methodSource)
    {
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < methodSource.length(); i++)
        {
            char c = methodSource.charAt(i);
            char next = (i + 1 < methodSource.length()) ? methodSource.charAt(i + 1) : 0;

            if (inLineComment)
            {
                if (c == '\n')
                    inLineComment = false;
                continue;
            }
            if (inBlockComment)
            {
                if (c == '*' && next == '/')
                {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inString)
            {
                if (c == '\\') { i++; continue; }
                if (c == '"')
                    inString = false;
                continue;
            }
            if (inChar)
            {
                if (c == '\\') { i++; continue; }
                if (c == '\'')
                    inChar = false;
                continue;
            }

            if (c == '/' && next == '/')
            {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*')
            {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '\'') { inChar = true; continue; }

            if (c == '{')
                return i;
        }

        return -1;
    }

    private List<IJavaProject> getAvailableJavaProjects()
    {
        List<IJavaProject> javaProjects = new ArrayList<>();
        try
        {
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (IProject project : projects)
            {
                if (project.isOpen() && project.hasNature(JavaCore.NATURE_ID))
                {
                    javaProjects.add(JavaCore.create(project));
                }
            }
        }
        catch (CoreException e)
        {
            throw new RuntimeException(e);
        }
        return javaProjects;
    }
}
