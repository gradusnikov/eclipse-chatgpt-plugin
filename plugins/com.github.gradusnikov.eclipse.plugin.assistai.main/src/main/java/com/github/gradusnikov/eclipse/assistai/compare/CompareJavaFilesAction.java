package com.github.gradusnikov.eclipse.assistai.compare;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.core.runtime.IProgressMonitor;

public class CompareJavaFilesAction {
	public static void showCompareView(ITypedElement left, ITypedElement right) {
		// Create compare configuration
		CompareConfiguration config = new CompareConfiguration();
		config.setLeftLabel(left.getName());
		config.setRightLabel(right.getName());
		config.setLeftEditable(false);
		config.setRightEditable(false);

		// Create compare input
		CompareEditorInput input = new CompareEditorInput(config) {
			@Override
			protected Object prepareInput(IProgressMonitor monitor) {
				return new Differencer().findDifferences(false, monitor, null, null, left, right);
			}
		};

		input.setTitle("Compare Java Files");
		CompareUI.openCompareDialog(input);
	}

}