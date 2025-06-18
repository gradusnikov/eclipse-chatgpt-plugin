package codingagent.factory;

public enum ModelFactories {
	OLLAMA(new OllamaFactory());
		
	private final ModelFactoryAdapter factory;
	
	ModelFactories(ModelFactoryAdapter factory) {
		this.factory = factory;
	}

	public ModelFactoryAdapter getFactory() {
		return factory;
	}
}
