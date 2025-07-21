package com.github.gradusnikov.eclipse.assistai.compare;

import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.DocumentRangeNode;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.graphics.Image;

/**
 * Comparable Java elements are represented as JavaNodes.
 * Extends the DocumentRangeNode with method signature information.
 * 
 * This class was intented to similar that JavaNode that is not visible in JDT
 * 
 */
public class JavaNode extends DocumentRangeNode implements ITypedElement {

	public static final int CU= 0;
	public static final int PACKAGE= 1;
	public static final int IMPORT_CONTAINER= 2;
	public static final int IMPORT= 3;
	public static final int INTERFACE= 4;
	public static final int CLASS= 5;
	public static final int ENUM= 6;
	public static final int ANNOTATION= 7;
	public static final int FIELD= 8;
	public static final int INIT= 9;
	public static final int CONSTRUCTOR= 10;
	public static final int METHOD= 11;

	private int fInitializerCount= 1;

	/**
	 * Creates a JavaNode under the given parent.
	 * @param parent the parent node
	 * @param type the Java elements type. Legal values are from the range CU to METHOD of this class.
	 * @param name the name of the Java element
	 * @param start the starting position of the java element in the underlying document
	 * @param length the number of characters of the java element in the underlying document
	 */
	public JavaNode(JavaNode parent, int type, String name, int start, int length) {
		super(parent, type, name, parent.getDocument(), start, length);
		parent.addChild(this);
	}

	/**
	 * Creates a JavaNode for a CU. It represents the root of a
	 * JavaNode tree, so its parent is null.
	 * @param document the document which contains the Java element
	 */
	public JavaNode(IDocument document) {
		super(CU, "root", document, 0, document.getLength()); //$NON-NLS-1$
	}
		
	public String getInitializerCount() {
		return Integer.toString(fInitializerCount++);
	}

	/**
	 * Extracts the method name from the signature.
	 * Used for smart matching.
	 */
	public String extractMethodName() {
		String id= getId();
		int pos= id.indexOf('(');
		if (pos > 0)
			return id.substring(1, pos);
		return id.substring(1);
	}

	/**
	 * Extracts the method's arguments name the signature.
	 * Used for smart matching.
	 */
	public String extractArgumentList() {
		String id= getId();
		int pos= id.indexOf('(');
		if (pos >= 0)
			return id.substring(pos+1);
		return id.substring(1);
	}

	/**
	 * Returns a name which is presented in the UI.
	 * @see ITypedElement#getName()
	 */
	@Override
	public String getName() {
		return getId();
	}

	/*
	 * @see ITypedElement#getType()
	 */
	@Override
	public String getType() {
		return "java2"; //$NON-NLS-1$
	}

	/**
	 * Returns a shared image for this Java element.
	 *
	 * see ITypedInput.getImage
	 */
	@Override
	public Image getImage() {
		return null;
	}

	/*
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return getType() + ": " + getName() //$NON-NLS-1$
				+ "[" + getRange().offset + "+" + getRange().length + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}
}

