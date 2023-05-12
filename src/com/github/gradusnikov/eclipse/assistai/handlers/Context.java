package com.github.gradusnikov.eclipse.assistai.handlers;

public record Context(
    String fileName,
    String fileContents,
    String selectedContent,
    String selectedItem,
    String selectedItemType,
    String lang) {}
