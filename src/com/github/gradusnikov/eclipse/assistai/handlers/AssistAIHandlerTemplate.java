package com.github.gradusnikov.eclipse.assistai.handlers;

import javax.inject.Inject;
import javax.inject.Named;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.github.gradusnikov.eclipse.assistai.handlers.JobFactory.JobType;
import com.github.gradusnikov.eclipse.assistai.part.ChatGPTPresenter;

public class AssistAIHandlerTemplate 
{
    @Inject
    protected JobFactory jobFactory;
    
    protected final JobType type;
    
    public AssistAIHandlerTemplate( JobType type )
    {
        this.type = type;
    }
    
    @Execute
    public void execute(@Named(IServiceConstants.ACTIVE_SHELL) Shell s)
    {
        // Get the active editor
        IWorkbenchPage activePage = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        IEditorPart activeEditor = activePage.getActiveEditor();

        // Check if it is a text editor
        if (activeEditor instanceof ITextEditor)
        {
            ITextEditor textEditor = (ITextEditor) activeEditor;

            // Retrieve the document and text selection
            ITextSelection textSelection = (ITextSelection) textEditor.getSelectionProvider().getSelection();
            IDocument      document  = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
            IJavaElement   compilationUnit = JavaUI.getEditorInputJavaElement(textEditor.getEditorInput());
            
            String selectedText = textSelection.getText();
            
            String documentText = document.get();
            String fileName     = activeEditor.getEditorInput().getName();
            
            // get java elements
            String selectedJavaElement = "";
            String selectedJavaType = "";
            
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
            Job job = jobFactory.createJob(type, documentText, selectedText, selectedJavaElement, selectedJavaType);
            job.schedule();
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
