<p align="center"><img src="src/website/logo_110_80.png"></p>


# AssistAI - A LLM (ChatGPT) Plugin for Eclipse IDE

AssistAI is an Eclipse IDE plugin that brings a Large Language Model (LLM) assistant (similar to ChatGPT) into your development environment. This an experimental plugin..


## Features

- Refactor selected code snippets with LLM
- Generate JavaDoc comments for chosen classes or methods with LLM's help
- Create JUnit tests for selected classes or methods using LLM's assistance
- Engage in a conversation with LLM about the content of the currently opened file
- Fix compilation errors with LLM's guidance
- Copy code blocks generated by LLM to the clipboard
- Produce Git commit comments based on changes in staged files
- Customize pre-defined prompts
- Using the built in tools (MPC) AssistAI can:
  - use JavaDoc or related source code to better understand the context
  - perform a web search using [DuckDuckGo](https://duckduckgo.com/)
  - read a content of a web page
- Create contexts for the LLM that include source files or images 
- Use the vision model to discuss a image content
- Switch between defined LLMs
- [NEW] Added support for interfacing with [Model Context Protocol (MPC)](https://modelcontextprotocol.io/introduction) servers. The plugin is a MCP Client. You can interface your favorite MCP servers using **any** of the supported LLMs (not limited to Claude). 
- [NEW] Now you can give control to LLM to modify your project files, access error logs, console output, and more... use Claude, sit back, vibe, and watch them tokens burn
- [NEW] Discuss about math, or tabular data. The chat component now supports latex and table rendering
- [NEW] Added tools that AI agents can use to execute and review JUnit tests


You can also pose general questions to LLM, just like with the regular AI chatbot interface.

## Supported Models

AssistAI supports multiple LLM providers through different API protocols:

| Provider | Protocol | Sample Models | MCP / tools | Vision | Notes |
|----------|----------|--------------|----------------|--------|-------|
| OpenAI | OpenAI API | gpt-4o, gpt-4-turbo, gpt-3.5-turbo | ✅ | ✅ | Default integration |
| Anthropic | Claude API | claude-3-7-sonnet, claude-3-5-sonnet, claude-3-opus | ✅ | ✅ | Native Claude API integration |
| Groq | OpenAI API | qwen-qwq-32b, llama3-70b-8192 | ✅ | ✅ | Uses OpenAI-compatible API |
| DeepSeek | DeepSeek API | deepseek-chat | ✅ | ❌ | Specialized integration |
| Google | Gemini API | gemini-2.0-flash, gemini-1.5-pro | ✅ | ✅ | Specialized integration |
| Local/Self-hosted | OpenAI API | Any local model via Ollama, LM Studio, etc. | Varies | Varies | Configure with OpenAI-compatible endpoint |
| Other 3rd party | OpenAI API | Various models from providers like Together.ai, Anyscale, etc. | Varies | Varies | Use OpenAI-compatible settings |

To use a local or third-party model, configure it using the OpenAI protocol format in the Models preferences section with the appropriate endpoint URL.

## Built-in MCP tools

| MCP Server | Tool | Description |
|------------|------|-------------|
| duck-duck-search | webSearch | Performs a search using a Duck Duck Go search engine and returns the search result json. |
| eclipse-coder | createFile | Creates a new file in the specified project, adds it to the project, and opens it in the editor. |
| eclipse-coder | insertIntoFile | Inserts content at a specific position in an existing file. |
| eclipse-coder | replaceString | Replaces a specific string in a file with a new string, optionally within a specified line range. |
| eclipse-coder | undoEdit | Undoes the last edit operation by restoring a file from its backup. |
| eclipse-coder | createDirectories | Creates a directory structure (recursively) in the specified project. |
| eclipse-ide | formatCode | Formats code according to the current Eclipse formatter settings. |
| eclipse-ide | getJavaDoc | Get the JavaDoc for the given compilation unit. |
| eclipse-ide | getSource | Get the source for the given class. |
| eclipse-ide | getProjectProperties | Retrieves the properties and configuration of a specified project. |
| eclipse-ide | getProjectLayout | Get the file and folder structure of a specified project in a hierarchical format. |
| eclipse-ide | getMethodCallHierarchy | Retrieves the call hierarchy (callers) for a specified method. |
| eclipse-ide | getCompilationErrors | Retrieves compilation errors and problems from the workspace or a project. |
| eclipse-ide | readProjectResource | Read the content of a text resource from a specified project. |
| eclipse-ide | listProjects | List all available projects in the workspace with their detected natures. |
| eclipse-ide | getCurrentlyOpenedFile | Gets information about the currently active file in the Eclipse editor. |
| eclipse-ide | getEditorSelection | Gets the currently selected text or lines in the active editor. |
| eclipse-ide | getConsoleOutput | Retrieves the recent output from Eclipse console(s). |
| eclipse-ide | runAllTests | Runs all tests in a specified project and returns the results. |
| eclipse-ide | runPackageTests | Runs tests in a specific package and returns the results. |
| eclipse-ide | runClassTests | Runs tests for a specific class and returns the results. |
| eclipse-ide | runTestMethod | Runs a specific test method and returns the results. |
| eclipse-ide | findTestClasses | Finds all test classes in a project. |
| memory | think | Use this tool to think about something without obtaining new information or performing changes. |
| webpage-reader | readWebPage | Reads the content of the given web site and returns its content as a markdown text. |
| time | currentTime | Returns the current date and time in the following format: yyyy-MM-dd HH:mm:ss |
| time | convertTimeZone | Converts time from one time zone to another. |



## Context

The plugin leverages the OpenAI API to send predefined prompts to the LLM. These prompts include relevant context from your IDE, such as:

- File name
- Content of the opened file
- Selected class or method name

If you're not satisfied with the results, you can ask follow-up questions to LLM. Your inquiries, along with the complete conversation history, will be sent to LLM, ensuring more precise answers.

Use the "Clear" button to reset the conversation context. Press the "Stop" button to halt LLM's response generation.

## Usage Examples

1. Vibe-coding ;)

   ![Eclipse Coder](src/website/eclipse-coder.gif)

1. Learn new things

   <img src="src/website/latex-rendering.png" alt="Math rendering" style="zoom:50%;" />

1. To discuss the class you're working on, select "Discuss" from the "Code Assist AI" context menu and ask any question about the code.

![Discuss with ChatGPT](src/website/how-it-works-discuss.gif)

2. To refactor a code snippet, select it and choose "Refactor" from the "Code Assist AI" context menu.

![Refactor with ChatGPT](src/website/how-it-works-refactor.gif)

3. You can also ask ChatGPT to document a selected class or method using the "Document" command:

![Document with ChatGPT](src/website/how-it-works-document.gif)

4. Additionally, you can request ChatGPT to generate a JUnit test:

![JUnit Test Generation](src/website/how-it-works-junit.gif)

5. After staging all modified files, ask ChatGPT to create a Git commit message:

![Git Commit Message Generation](src/website/how-it-works-gitcomment.gif)

6. If you have errors in your code, ChatGPT can generate a patch to solve your issues. Select "Fix Errors" command, copy patch contents using the "Copy Code" button, and paste it to your project with CTRL+v, or you can use "Apply Patch" button that will open the patch import window. 

   The "Apply Patch" button is active whenever ChatGPT returns a *diff* code block. When interacting with ChatGPT (i.e. performing a code review) you can ask it to format its answers this way using a following prompt: "Return your answer in diff format using full paths".

![Git Commit Message Generation](src/website/how-it-works-fixerrors-1.gif)

7. AssistAI can use function calls to get the related source code or JavaDoc to better understand the problem and provide you with a more accurate solution.

![Function calling](src/website/how-it-works-function-calls.gif)

8. Using the context menu Paste an image from a Cliboard or drag-and-drop an image file to discuss it with the ChatGPT. 

![Vision](src/website/how-it-works-vision.png)

9. Configure and use MPC servers. Fill in MCP server details: *name*, *command (CLI)*, *enviromnent variables*, and allow the LLM to use external tools.
![MPC](src/website/mpc-support.png)

## Installation

### Plugin Installation

The easiest way to install the plugin is to use the Eclipse Marketplace. Just drag the "Install" button below into your running Eclipse workspace.

<p align="center"><a href="https://marketplace.eclipse.org/marketplace-client-intro?mpc_install=5602936" class="drag" title="Drag to your running Eclipse* workspace. *Requires Eclipse Marketplace Client"><img style="width:80px;" typeof="foaf:Image" class="img-responsive" src="https://marketplace.eclipse.org/sites/all/themes/solstice/public/images/marketplace/btn-install.svg" alt="Drag to your running Eclipse* workspace. *Requires Eclipse Marketplace Client" /></a></p>

Alternatively you can configure an update site:

1. In Eclipse IDE, open *Help > Install new software*
2. Click the *Add* button to open the "add repository" window, and input `AssistAI` as *Name* and `https://gradusnikov.github.io/eclipse-chatgpt-plugin/` as *Location*
3. Click *Add*
4. Back in *Install* window, choose *AssistAI* from the  *Work with:* list
4. Select "Assist AI" from the plugin list and proceed to the next step by clicking the *Next* button
5. Accept any certificate warnings (note: this is a self-signed plugin, so you will be warned about potential security risks)

### Configuration

After installing the plugin, configure access to the **OpenAI API**:

1. Open *Window > Preferences > Assist AI* preferences
2. Configure your models *Window > Preferences > Assist AI* preferences > Models
   
3. Select the model you want to use from a dropdown list: *Window > Preferences > Assist AI* preferences. You can switch between the defined models here.

### Configuring MCP Servers

MCP servers provide LLMs with tools to interact with files, databases, and APIs. These tools can transform this plugin into an alternative to Cursor or MANUS. Several built-in MCP servers exist, such as DuckDuckGo search, web fetching, or Eclipse integrations, but you can easily add any MCP server that provides a stdio interface.

#### Adding the Filesystem MCP Server
To enable the filesystem MCP (e.g., [filesystem](https://github.com/modelcontextprotocol/servers/tree/main/src/filesystem)), which allows LLMs to read or modify files, follow these steps:

1. **Open the Preferences**  
   Navigate to *Window > Preferences > Assist AI > MCP Servers* and click **Add**.

2. **Configure the Server**  
   Fill in the configuration details:  
   ```  
   Name: server-filesystem  
   Command: wsl.exe npx -y @modelcontextprotocol/server-filesystem ${workspace_loc}  
   ```

3. **Optional Environment Variables**  
   Define environment variables if required (e.g., API keys).

#### Notes
- The `${workspace_loc}` variable specifies the workspace folder accessible to the MCP. When using WSL, this path is automatically converted to a Unix-style path for compatibility.  Other eclipse variables are available (`${project_loc}`, etc.)
- **Security Warning:** Use MCP servers cautiously, as they grant LLMs access to read or modify sensitive data in your project directory, which could be accidentally altered or deleted by the LLM.


### Add ChatGPT View

Add the *ChatGPT View* to your IDE:

1. Open *Window > Show View > Other*
2. Select *ChatGPT View* from the *Code Assist AI* category
