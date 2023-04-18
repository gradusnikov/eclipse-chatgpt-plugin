package com.github.gradusnikov.eclipse.assistai.part;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.github.gradusnikov.eclipse.assistai.Activator;

public class AssistAIPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

  private StringFieldEditor openAIKeyField;
  private StringFieldEditor modelNameField;

  public AssistAIPreferencePage() {
    super("My Plugin Settings", null);
    setDescription("Enter your OpenAI Key and Model Name below:");
  }

  @Override
  protected Control createContents(Composite parent) {
    Composite composite = new Composite(parent, SWT.NONE);
    composite.setLayout(new GridLayout(2, false));

    Label openAIKeyLabel = new Label(composite, SWT.NONE);
    openAIKeyLabel.setText("OpenAI Key:");
    openAIKeyField = new StringFieldEditor("openAIKey", "", composite);
    openAIKeyField.setStringValue(getPreferenceStore().getString("openAIKey"));

    Label modelNameLabel = new Label(composite, SWT.NONE);
    modelNameLabel.setText("Model Name:");
    modelNameField = new StringFieldEditor("modelName", "", composite);
    modelNameField.setStringValue(getPreferenceStore().getString("modelName"));

    return composite;
  }

  @Override
  public void init(IWorkbench workbench) {
    setPreferenceStore( Activator.getDefault().getPreferenceStore() );
  }

  @Override
  public boolean performOk() {
    openAIKeyField.store();
    modelNameField.store();
    return super.performOk();
  }
}
