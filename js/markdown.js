function replaceCodeBlocks(markdownString) {
  // Regular expression to match the opening code block
  const openingRegex = /```([a-z]+)?/g;

  // Regular expression to match the closing code block
  const closingRegex = /```/g;

  // Replace the opening code block with <pre><code lang="[lang]">
  markdownString = markdownString.replace(openingRegex, '<pre><code lang="$1">');

  // Replace the closing code block with </code></pre>
  markdownString = markdownString.replace(closingRegex, '</code></pre>');

  return markdownString;
}