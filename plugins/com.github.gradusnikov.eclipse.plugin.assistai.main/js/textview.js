
// Function to update summary with function name
function updateFunctionCallSummaries() {
  // Find all function call detail elements
  const functionCallDetails = document.querySelectorAll('.function-call details');
  
  functionCallDetails.forEach(detailsElement => {
    const summaryElement = detailsElement.querySelector('summary');
    const preElement = detailsElement.querySelector('pre');
    
    if (preElement && summaryElement) {
      // Create a mutation observer to watch for changes to the pre content
      const observer = new MutationObserver((mutations) => {
        // Try to extract the function name from the content
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

      });
      
      // Start observing the pre element for changes
      observer.observe(preElement, { 
        childList: true, 
        characterData: true,
        subtree: true 
      });
      
      // Also try to update immediately in case content is already there
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

// Run the function when the DOM is fully loaded
document.addEventListener('DOMContentLoaded', updateFunctionCallSummaries);

// Also set up a mutation observer for the chat container to detect new function calls
document.addEventListener('DOMContentLoaded', () => {
  const contentElement = document.getElementById('content');
  if (contentElement) {
    const observer = new MutationObserver((mutations) => {
      updateFunctionCallSummaries();
    });
    
    observer.observe(contentElement, { 
      childList: true, 
      subtree: true 
    });
  }
});
