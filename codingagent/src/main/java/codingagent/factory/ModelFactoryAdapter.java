package codingagent.factory;

import codingagent.models.ModelApiDescriptor;
import dev.langchain4j.model.chat.ChatLanguageModel;

public interface ModelFactoryAdapter {
	public ChatLanguageModel buildChat(ModelApiDescriptor descriptor);
}
