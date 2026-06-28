package com.github.gradusnikov.eclipse.assistai.tools;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.content.IContentTypeManager;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

public class ResourceUtilities
{
    public static List<String> readFileLines(IFile file) throws IOException, CoreException
    {
        Objects.requireNonNull(file);
        List<String> lines = new ArrayList<>();
        try (InputStream is = file.getContents();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(is, Charset.forName(file.getCharset()))))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                lines.add(line);
            }
        }
        return lines;
    }

    public static List<String> readFileLinesWithTerminators(IFile file)
            throws IOException, CoreException
    {
        Objects.requireNonNull(file);

        try (InputStream is = file.getContents())
        {
            String content = new String(
                    is.readAllBytes(),
                    Charset.forName(file.getCharset()));

            List<String> lines = new ArrayList<>();

            var matcher = Pattern.compile(".*?(\\R|$)", Pattern.DOTALL)
                    .matcher(content);

            while (matcher.find())
            {
                String line = matcher.group();

                // avoid the extra empty match at EOF
                if (!line.isEmpty())
                {
                    lines.add(line);
                }
            }

            return lines;
        }
    }
    
    /**
     * Recursively creates a folder hierarchy.
     * 
     * @param folder The folder to create
     * @throws CoreException If there is an error creating the folder
     */
    public static void createFolderHierarchy(IFolder folder) throws CoreException
    {
        Objects.requireNonNull(folder);
        if (!folder.exists())
        {
            IContainer parent = folder.getParent();
            if (parent instanceof IFolder && !parent.exists())
            {
                createFolderHierarchy((IFolder) parent);
            }
            folder.create(true, true, null);
        }
    }

    public static byte[] readInputStream(InputStream inputStream) throws IOException
    {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) != -1)
        {
            result.write(buffer, 0, length);
        }
        return result.toByteArray();
    }

    public static String readFileContent(IFile file) throws IOException, CoreException
    {
        Objects.requireNonNull(file);
        // Read file content
        try (InputStream is = file.getContents())
        {
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) != -1)
            {
                content.write(buffer, 0, length);
            }
            return content.toString(file.getCharset());
        }
    }

    public static InputStream toFileContent(IFile file, String text) throws CoreException
    {
        Objects.requireNonNull(file);
        
        try {
			return new ByteArrayInputStream(text == null ? new byte[0]: text.getBytes(file.getCharset()));
		} catch (UnsupportedEncodingException e) {
			throw new CoreException(Status.error("Unknown charset", e));
		}
    }

    public static String getSuggestedFileName(String lang, String codeBlock)
    {
        // Fall back to the original implementation if parsing fails or for other languages
        return switch (lang)
        {
            case "java" -> suggestedJavaFile(codeBlock);
            case "python" -> "new_script.py";
            case "javascript" -> "script.js";
            case "typescript" -> "script.ts";
            case "html" -> "index.html";
            case "xml" -> "config.xml";
            case "json" -> "data.json";
            case "markdown" -> "README.md";
            case "cpp" -> "main.cpp";
            case "bash" -> "script.sh";
            case "yaml" -> "config.yaml";
            case "properties" -> "config.properties";
            default -> "NewFile." + getFileExtensionForLang(lang);
        };
    }

    public static IPath suggestedJavaPackage(String codeBlock)
    {
        String pathString = "";
        // Extract package name from Java code
        if (codeBlock != null && !codeBlock.isBlank())
        {
            try
            {
                // Use Eclipse JDT to parse the Java code
                ASTParser parser = ASTParser.newParser(AST.JLS23);
                parser.setSource(codeBlock.toCharArray());
                parser.setKind(ASTParser.K_COMPILATION_UNIT);

                CompilationUnit cu = (CompilationUnit) parser.createAST(null);

                PackageDeclaration packageDecl = cu.getPackage();
                if (packageDecl != null)
                {
                    pathString = packageDecl.getName().getFullyQualifiedName().replace(".", "/");
                }
            }
            catch (Exception e)
            {
                // ignore
            }
        }
        return IPath.fromPath(Paths.get(pathString));
    }

    public static String suggestedJavaFile(String codeBlock)
    {
        // Extract class name from Java code
        if (codeBlock != null && !codeBlock.isBlank())
        {
            try
            {
                // Use Eclipse JDT to parse the Java code
                ASTParser parser = ASTParser.newParser(AST.JLS23);
                parser.setSource(codeBlock.toCharArray());
                parser.setKind(ASTParser.K_COMPILATION_UNIT);

                CompilationUnit cu = (CompilationUnit) parser.createAST(null);

                // Find the first type declaration (class, interface, enum)
                for (Object type : cu.types())
                {
                    if (type instanceof TypeDeclaration)
                    {
                        TypeDeclaration typeDecl = (TypeDeclaration) type;
                        return typeDecl.getName().getIdentifier() + ".java";
                    }
                    else if (type instanceof EnumDeclaration)
                    {
                        EnumDeclaration enumDecl = (EnumDeclaration) type;
                        return enumDecl.getName().getIdentifier() + ".java";
                    }
                }
            }
            catch (Exception e)
            {
                // ignore
            }
        }
        return "NewClass.java";

    }

    public static IPath getSuggestedPath(IProject project, String lang, String codeBlock)
    {
        return switch (lang)
        {
            case "java" -> suggestedJavaPath(project).append(suggestedJavaPackage(codeBlock));
            case "python" -> suggestedPythonPath(project);
            case "javascript", "typescript", "html", "css" -> suggestedWebPath(project);
            default -> project.getFullPath();
        };
    }

    public static IPath suggestedWebPath(IProject project)
    {
        // Check for web folders
        if (project.getFolder("webapp").exists())
        {
            return project.getFolder("webapp").getFullPath();
        }
        else if (project.getFolder("WebContent").exists())
        {
            return project.getFolder("WebContent").getFullPath();
        }
        else if (project.getFolder("public").exists())
        {
            return project.getFolder("public").getFullPath();
        }
        else if (project.getFolder("web").exists())
        {
            return project.getFolder("web").getFullPath();
        }
        else
        {
            return project.getFullPath();
        }
    }

    public static IPath suggestedPythonPath(IProject project)
    {
        if (project.getFolder("src").exists())
        {
            return project.getFolder("src").getFullPath();
        }
        else
        {
            return project.getFullPath();
        }
    }

    public static IPath suggestedJavaPath(IProject project)
    {
        // Try to find src/main/java or src folder
        IPath srcMainJava = project.getFolder("src/main/java").getFullPath();
        if (project.getFolder("src/main/java").exists())
        {
            return srcMainJava;
        }
        else if (project.getFolder("src").exists())
        {
            return project.getFolder("src").getFullPath();
        }
        else
        {
            return project.getFullPath();
        }
    }

    public static String getFileExtensionForLang(String lang)
    {
        return switch (lang)
        {
            case "java" -> "java";
            case "python" -> "py";
            case "javascript" -> "js";
            case "typescript" -> "ts";
            case "html" -> "html";
            case "xml" -> "xml";
            case "json" -> "json";
            case "markdown" -> "md";
            case "cpp" -> "cpp";
            case "bash" -> "sh";
            case "yaml" -> "yaml";
            case "properties" -> "properties";
            case "text" -> "txt";
            default -> "txt";
        };
    }

    public static String getResourceFileType(IFile file) throws IOException
    {
        Objects.requireNonNull(file);

        int MAX_FILE_SIZE_KB = 1024;
        // Check file size to avoid loading extremely large files
        long fileSizeInKB = file.getLocation().toFile().length() / 1024;
        if (fileSizeInKB > MAX_FILE_SIZE_KB)
        {
            // Limit to 1MB
            throw new IOException("Error: File '" + file.getFullPath().toFile() + "' is too large (" + fileSizeInKB
                    + " KB). Maximum size is " + MAX_FILE_SIZE_KB + " KB.");
        }

        try
        {
            if (isBinaryFile(file))
            {
                throw new IOException("Cannot read binary file '" + file.getFullPath().toFile()
                        + "'. Only text files are supported.");
            }
            return getLanguageForFile(file);
        }
        catch (IOException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new IOException("Error: Cannot detect the file content type. Reason: " + e.getMessage());
        }
    }

    /**
     * Decides whether a workspace file is binary using the Eclipse content-type
     * registry, falling back to a NUL-byte sniff for unknown content types.
     */
    private static boolean isBinaryFile(IFile file) throws IOException, CoreException
    {
        byte[] sample;
        try (InputStream is = new BufferedInputStream(file.getContents()))
        {
            sample = is.readNBytes(8192);
        }

        IContentTypeManager manager = Platform.getContentTypeManager();
        IContentType textType = manager.getContentType(IContentTypeManager.CT_TEXT);
        IContentType detected = manager.findContentTypeFor(new ByteArrayInputStream(sample), file.getName());
        if (detected != null)
        {
            return textType == null || !detected.isKindOf(textType);
        }
        // Unknown content type: NUL bytes are a strong signal of binary content.
        for (byte b : sample)
        {
            if (b == 0)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Determines the language for syntax highlighting based on file extension.
     * 
     * @param fileExtension The file extension
     * @return The language identifier for syntax highlighting
     */
    public static String getLanguageForFile(IFile file)
    {
        return Optional.ofNullable(file)
                .map(IFile::getFileExtension)
                .map(ResourceUtilities::getLanguageForExtension)
                .orElse("");
    }

    /**
     * Determines the language for syntax highlighting based on file extension.
     * 
     * @param fileExtension The file extension
     * @return The language identifier for syntax highlighting
     */
    public static String getLanguageForExtension(String fileExtension)
    {

        return switch (Optional.ofNullable(fileExtension).map(String::toLowerCase).orElse(""))
        {
            case "java" -> "java";
            case "py" -> "python";
            case "js" -> "javascript";
            case "ts" -> "typescript";
            case "html" -> "html";
            case "xml" -> "xml";
            case "json" -> "json";
            case "md" -> "markdown";
            case "c" -> "cpp";
            case "cpp" -> "cpp";
            case "h" -> "cpp";
            case "hpp" -> "cpp";
            case "sh" -> "bash";
            case "properties" -> "properties";
            case "yaml" -> "yaml";
            case "yml" -> "yaml";
            case "txt" -> "text";
            default -> "";

        };
    }

    /**
     * Normalizes glob patterns. If no patterns are provided, returns an array containing "*".
     */
    public static String[] normalizeGlobPatterns(String... fileNamePatterns)
    {
        if (fileNamePatterns == null || fileNamePatterns.length == 0)
        {
            return new String[] { "*" };
        }

        List<String> patterns = new ArrayList<>();
        for (String p : fileNamePatterns)
        {
            if (p != null && !p.isBlank())
            {
                patterns.add(p.trim());
            }
        }
        return patterns.isEmpty() ? new String[] { "*" } : patterns.toArray(String[]::new);
    }

    /**
     * Converts one or more glob patterns (supports '*' and '?') into a single regex {@link Pattern}.
     * The resulting pattern is anchored to match the full file name.
     */
    public static Pattern globPatternsToRegex(String... globs)
    {
        String[] normalized = normalizeGlobPatterns(globs);

        StringBuilder regex = new StringBuilder();
        regex.append("^(?:");
        for (int i = 0; i < normalized.length; i++)
        {
            if (i > 0)
            {
                regex.append("|");
            }
            regex.append(globToRegex(normalized[i]));
        }
        regex.append(")$");

        return Pattern.compile(regex.toString());
    }

    /**
     * Very small glob implementation: supports '*' and '?' only.
     */
    public static String globToRegex(String glob)
    {
        String g = glob == null ? "*" : glob.trim();
        if (g.isEmpty())
        {
            g = "*";
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < g.length(); i++)
        {
            char c = g.charAt(i);
            switch (c)
            {
                case '*':
                    out.append(".*");
                    break;
                case '?':
                    out.append('.');
                    break;
                // Escape regex metacharacters
                case '\\':
                case '.':
                case '^':
                case '$':
                case '+':
                case '{':
                case '}':
                case '[':
                case ']':
                case '(':
                case ')':
                case '|':
                    out.append('\\').append(c);
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }
}
