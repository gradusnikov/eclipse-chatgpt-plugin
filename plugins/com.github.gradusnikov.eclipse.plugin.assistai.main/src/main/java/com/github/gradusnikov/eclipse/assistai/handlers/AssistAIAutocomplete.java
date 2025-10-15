package com.github.gradusnikov.eclipse.assistai.handlers;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.jdt.internal.core.DocumentAdapter;
import org.eclipse.swt.widgets.Shell;

import com.github.gradusnikov.eclipse.assistai.compare.CompareJavaFilesAction;
import com.github.gradusnikov.eclipse.assistai.compare.JavaNode;
import com.github.gradusnikov.eclipse.assistai.compare.SourceMemoryBuffer;
import com.github.gradusnikov.eclipse.assistai.mcp.services.EditorService;
import com.github.gradusnikov.eclipse.assistai.network.clients.LanguageModelClientConfiguration;

import codingagent.factory.ModelFactories;
import codingagent.factory.ModelFactoryAdapter;
import codingagent.models.AutoCompletingQuery;
import codingagent.models.ModelApiDescriptor;
import codingagent.models.Prompts;
import dev.langchain4j.model.chat.ChatLanguageModel;
import jakarta.inject.Inject;
import jakarta.inject.Named;

public class AssistAIAutocomplete extends AssistAIHandlerTemplate {

	@Inject
	private LanguageModelClientConfiguration configuration;

	@Inject
	private EditorService editorService;

	public AssistAIAutocomplete() {
		super(Prompts.AUTOCOMPLETE);
	}

	@Execute
	public void execute(@Named(IServiceConstants.ACTIVE_SHELL) Shell s) {
		JavaNode javaSource;
		try {
			javaSource = editorService.getCompilationUnitFromCurrentJavaEditor();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		AutoCompletingQuery query = (AutoCompletingQuery) chatMessageFactory.createQuery(type);
	
		ModelApiDescriptor modelApiDescriptor = configuration.getSelectedModel().get();
		ModelFactoryAdapter modelFactory = ModelFactories.valueOf(modelApiDescriptor.apiType()).getFactory();
		ChatLanguageModel model = modelFactory.buildChat(modelApiDescriptor);
		query.execute(model);
		String resultSourceFile = query.getSourceFile();

		CompareJavaFilesAction.showCompareView(javaSource, new JavaNode(new DocumentAdapter(new SourceMemoryBuffer(resultSourceFile))));
	}

}
