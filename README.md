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
- [NEW] Added support for interfacing with [Model Context Protocol (MPC)](https://modelcontextprotocol.io/introduction) servers - MCP servers can be used with both OpenAI compatible (gpt-4o, groq, LM studio) and Anthropic (Claude) LLMs
- [NEW] Now supporting both OpenAI and Anthropic protocols - including function/tool calling.


You can also pose general questions to LLM, just like with the regular ChatGPT interface.

## Context

The plugin leverages the OpenAI API to send predefined prompts to the LLM. These prompts include relevant context from your IDE, such as:

- File name
- Content of the opened file
- Selected class or method name

If you're not satisfied with the results, you can ask follow-up questions to LLM. Your inquiries, along with the complete conversation history, will be sent to LLM, ensuring more precise answers.

Use the "Clear" button to reset the conversation context. Press the "Stop" button to halt LLM's response generation.

## Usage Examples

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
2. Click the *Add* button to open the "add repository" window, and input `AssistAI` as *Name* and `https://eclipse-chatgpt-plugin.lm.r.appspot.com/` as *Location*
3. Click *Add*
4. Back in *Install* window, choose *AssistAI* from the  *Work with:* list
4. Select "Assist AI" from the plugin list and proceed to the next step by clicking the *Next* button
5. Accept any certificate warnings (note: this is a self-signed plugin, so you will be warned about potential security risks)

### Configuration

After installing the plugin, configure access to the **OpenAI API**:

1. Open *Window > Preferences > Assist AI* preferences
2. Configure your models *Window > Preferences > Assist AI* preferences > Models:
   1. input the model URL (e.g. if you want to use OpenAI models, type https://api.openai.com/v1/chat/completions . You can use any other LLM endpoint, as long as it implements the OpenAI protocol - groq, LLM studio, etc.)
   2. input your API keys (e.g. if you want to use OpenAI models, you can find your keys at https://platform.openai.com/account/api-keys)
   3. Input the model name. By default, the plugin uses the *gpt-4*o model, but you can also utilize *gpt-3.5-turbo* or any available ChatGPT model. To check which models are available to you, go to https://platform.openai.com/playground?mode=chat and check the *Model* drop list.  **I highly recommend using one of the GPT-4o models*** as these have larger context window, which is essential for handling large source files. If you encounter 400 errors, the most probable cause is exceeding the context window limit. The LLM model has a maximum capacity that, when surpassed, results in these errors. 
   4. if the model supports vision (image analysis) check the "With Vision" checkbox
   5. if the model supports function calling (e.g. gpt-4) check the "With Function Calls" checkbox
   6. adjust the model Temperature parameter (if needed)

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

