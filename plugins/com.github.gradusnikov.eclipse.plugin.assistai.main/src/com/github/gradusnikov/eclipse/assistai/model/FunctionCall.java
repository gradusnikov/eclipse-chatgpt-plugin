package com.github.gradusnikov.eclipse.assistai.model;

import java.util.Map;

public record FunctionCall( String id, String name, Map<String, Object> arguments  ) {}
