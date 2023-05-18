package com.github.gradusnikov.eclipse.assistai.handlers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.inject.Inject;
import javax.inject.Named;

import org.eclipse.core.resources.IFile;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.github.gradusnikov.eclipse.assistai.prompt.JobFactory;
import com.github.gradusnikov.eclipse.assistai.prompt.Prompts;

public class AssistAIHandlerTemplate 
{
    @Inject
    protected JobFactory jobFactory;
    
    protected final Prompts type;
    
    public AssistAIHandlerTemplate( Prompts type )
    {
        this.type = type;
    }
    
    @Execute
    public void execute(@Named(IServiceConstants.ACTIVE_SHELL) Shell s)
    {
        // Get the active editor
        var activePage = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var activeEditor = activePage.getActiveEditor();

        // Check if it is a text editor
        if (activeEditor instanceof ITextEditor)
        {
            ITextEditor textEditor = (ITextEditor) activeEditor;

            // Retrieve the document and text selection
            ITextSelection textSelection = (ITextSelection) textEditor.getSelectionProvider().getSelection();
            var   compilationUnit = JavaUI.getEditorInputJavaElement(textEditor.getEditorInput());
            
            var selectedText = textSelection.getText();
            
            // Read the content from the file
            // this fixes skipped empty lines issue
            var file = textEditor.getEditorInput().getAdapter(IFile.class);
            var documentText = "";
            try  
            {
                documentText = new String( Files.readAllBytes( file.getLocation().toFile().toPath() ), StandardCharsets.UTF_8 );
            } 
            catch (IOException e) 
            {
                throw new RuntimeException(e);
            }
            var fileName     = file.getProjectRelativePath().toString(); // use project relative path
            var ext          = fileName.substring( fileName.lastIndexOf( "." )+1 );
            
            // get java elements
            var selectedJavaElement = "";
            var selectedJavaType = "code snippet";
            
            if (compilationUnit instanceof ICompilationUnit)
            {
                IJavaElement selectedElement;
                try
                {
                    selectedElement = ((ICompilationUnit) compilationUnit).getElementAt( textSelection.getOffset( ));
                    if (selectedElement != null )
                    {
                        switch ( selectedElement.getElementType() )
                        {
                            case IJavaElement.METHOD:
                            case IJavaElement.FIELD:
                            case IJavaElement.LOCAL_VARIABLE:
                                selectedJavaElement = selectedElement.toString();
                                selectedJavaType = javaElementTypeToString(selectedElement);
                                break;
                            case IJavaElement.TYPE:
                                selectedJavaElement = "";
                                selectedJavaType = "class declaration";
                           default:
                        }
                    }
                    selectedJavaElement = selectedJavaElement.replaceAll("\\[.*\\]", "");
                }
                catch (JavaModelException e)
                {
                    throw new RuntimeException(e);
                }
            }
            var context = new Context( fileName, 
                                       documentText, 
                                       selectedText, 
                                       selectedJavaElement, 
                                       selectedJavaType,
                                       ext);
            jobFactory.createJob(type, context).schedule();
        }
    }
    
    public String javaElementTypeToString( IJavaElement element )
    {
        switch ( element.getElementType() )
        {
            case IJavaElement.ANNOTATION: return "annotation";
            case IJavaElement.CLASS_FILE: return "class file";
            case IJavaElement.COMPILATION_UNIT: return "compilation unit";
            case IJavaElement.FIELD: return "field";
            case IJavaElement.IMPORT_CONTAINER: return "import container";
            case IJavaElement.IMPORT_DECLARATION: return "import declaration";
            case IJavaElement.INITIALIZER: return "initializer";
            case IJavaElement.JAVA_MODEL: return "java model";
            case IJavaElement.JAVA_MODULE: return "java module";
            case IJavaElement.JAVA_PROJECT: return "java project";
            case IJavaElement.LOCAL_VARIABLE: return "local variable";
            case IJavaElement.METHOD: return "method";
            case IJavaElement.PACKAGE_DECLARATION: return "package declaration";
            case IJavaElement.PACKAGE_FRAGMENT: return "package fragment";
            case IJavaElement.PACKAGE_FRAGMENT_ROOT: return "package fragment root";
            case IJavaElement.TYPE: return "type";
            case IJavaElement.TYPE_PARAMETER: return "type parameter";
            default: return "";
        }

    }

    
}
