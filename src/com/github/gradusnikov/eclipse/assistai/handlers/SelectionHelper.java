package com.github.gradusnikov.eclipse.assistai.handlers;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.TextSelection;

public class SelectionHelper 
{
	private String type;
	private ITextSelection selection;
	
	public SelectionHelper( ITextSelection selection, IJavaElement   compilationUnit )
	{
		this.type = type;
		this.selection = selection;
	}
	
	public String getText()
	{
		return this.selection.getText();
	}
	
	
    private String javaElementTypeToString( IJavaElement element )
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
        	default: 
        		return "snippet";
        }
    }
}
