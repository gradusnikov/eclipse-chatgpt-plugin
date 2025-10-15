package codingagent.factory;

public enum ModelFactories {
	OLLAMA(new OllamaFactory()),
	MISTRAL(new MistralFactory()),
	OPENAI(new OpenAiFactory())
	;
	
		
	private ModelFactoryAdapter factory;
	
	ModelFactories(ModelFactoryAdapter factory) {
		this.factory = factory;
	}

	public ModelFactoryAdapter getFactory() {
		return factory;
	}
}
