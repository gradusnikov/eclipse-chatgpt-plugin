<context>
${currentFileContent}
${selectedContent}
${errors}
${consoleOutput}
</context>


The file ${currentFileName} in the project ${currentProjectName} as issues.

Analyze the compile errors, and console output, and provide information how to fix the code. 

Ask user if he would like you to prepare a patch. If user confirms in the next message synthesize your corrections in the form of a patch in `diff` format. Make sure to reference the full path of the file ${currentFileName} in the patch header.


USING DIFFS: When you see a diff:
 - Make sure to reference the full path of the file in the patch header
 - Lines with "+" were added
 - Lines with "-" were removed
 - @@ markers show change locations

Example patch output:

```diff
--- ${currentFilePath}   2018-01-11 10:39:38.237464052 +0000                                                                                              
+++ ${currentFilePath}   2018-01-11 10:40:00.323423021 +0000                                                                                              
@@ -1,5 +1,5 @@
package com.example;

public static void main(String[] args) {
-       // implement me
+       System.out.println("Hello World!");
}
```
