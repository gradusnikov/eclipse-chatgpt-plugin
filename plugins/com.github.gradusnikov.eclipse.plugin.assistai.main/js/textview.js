// Function to update summary with function name
function updateFunctionCallSummaries() {
  // Find all function call detail elements
  const functionCallDetails = document.querySelectorAll('.function-call details');
  
  functionCallDetails.forEach(detailsElement => {
    const summaryElement = detailsElement.querySelector('summary');
    const preElement = detailsElement.querySelector('pre');
    
    if (preElement && summaryElement) {
      // Process content immediately
      const content = preElement.textContent;
      const nameMcpToolMatch = content.match(/"name"\s*:\s*"([^_]+)__([^"]+)"/);
      const nameMatch = content.match(/"name"\s*:\s*"([^"]+)"/);
      
      if (nameMcpToolMatch && nameMcpToolMatch[1] && nameMcpToolMatch[2]) {
        const mcp = nameMcpToolMatch[1];
        const tool = nameMcpToolMatch[2];
        summaryElement.textContent = `Using Tool @${mcp}: ${tool}`;
      } else if (nameMatch && nameMatch[1]) {
        summaryElement.textContent = `Function call: ${nameMatch[1]}`;
      }
    }
  });
}

function renderLatex() {
    // Convert block latex tags
    document.querySelectorAll('.block-latex').forEach(elem => {
        let latexString = atob(elem.innerHTML);
        let latexHtml = katex.renderToString(latexString, {throwOnError: false});
        // Create a temporary element to manipulate the generated HTML
        let tempDiv = document.createElement('div');
        tempDiv.innerHTML = latexHtml;
        // Add block-katex class to the katex span
        let katexSpan = tempDiv.querySelector('.katex');
        if (katexSpan) {
            katexSpan.classList.add('block-katex');
        }
        elem.outerHTML = tempDiv.innerHTML;
    });
    
    // Convert inline latex tags
    document.querySelectorAll('.inline-latex').forEach(elem => {
        let latexString = atob(elem.innerHTML);
        let latexHtml = katex.renderToString(latexString, {throwOnError: false});
        // Create a temporary element to manipulate the generated HTML
        let tempDiv = document.createElement('div');
        tempDiv.innerHTML = latexHtml;
        // Add inline-katex class to the katex span
        let katexSpan = tempDiv.querySelector('.katex');
        if (katexSpan) {
            katexSpan.classList.add('inline-katex');
        }
        elem.outerHTML = tempDiv.innerHTML;
    });
}

function renderInlineCode() {
    document.querySelectorAll('.inline-code').forEach(elem => {
        elem.outerHTML = '<code>' + elem.innerHTML + '</code>';
    });
}

function renderCode() {
  renderInlineCode();
  renderLatex();
  updateFunctionCallSummaries();
  hljs.highlightAll(); 
}