package com.keg.eclipseaiassistant.model;

import java.util.Map;

public record FunctionCall( String name, Map<String, String> arguments  ) {}
