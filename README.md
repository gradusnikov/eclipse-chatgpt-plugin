# AssistAI - A ChatGPT Plugin for Eclipse IDE

AssistAI is an Eclipse IDE plugin that integrates ChatGPT functionality. The plugin is experimental and currently works with Java editors only.

## Features

1. Refactor a selected portion of your code by asking ChatGPT
2. Generate JavaDoc comments for a selected class or method using ChatGPT
3. Create JUnit tests for a selected class or method with ChatGPT's assistance
4. Discuss the content of the currently opened file with ChatGPT
5. Copy code blocks from ChatGPT to the clipboard

You can also ask general questions, like with a regular ChatGPT.

## Context

The plugin utilizes the OpenAI API to send predefined prompts to ChatGPT, which include the context of your IDE, such as:

- File name
- Content of the opened file
- Selected class or method name

If you are unsatisfied with the results, you can ask follow-up questions to ChatGPT. Your questions will be sent along with the complete conversation history, helping ChatGPT provide more accurate answers.

Use the "Clear" button to reset the conversation context. To stop ChatGPT from generating a response, press the "Stop" button.

## Usage Examples

1. To discuss the class you're working on, select "Discuss" from the "Code Assist AI" context menu and ask any question about the code.

![Discuss with ChatGPT](site/how-it-works-discuss.gif)

2. To refactor a code snippet, select it and choose "Refactor" from the "Code Assist AI" context menu.

![Refactor with ChatGPT](site/how-it-works-refactor.gif)

3. You can also ask ChatGPT to document a selected class or method using the "Document" command:

![Document with ChatGPT](site/how-it-works-document.gif)

4. Finally, you can ask ChatGPT to generate a JUnit test:

![JUnit Test Generation](site/how-it-works-junit.gif)

## Installation

Currently, binary builds are not provided. To install the plugin, follow these steps:

1. Import this project into your Eclipse as "Plug-ins and Fragments" using the "Plugin Development" import.
2. Once you have the project in your workspace, export it as "Deployable plug-ins and fragments" and "Install to host" Eclipse.