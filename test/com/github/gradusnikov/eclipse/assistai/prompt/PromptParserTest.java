package com.github.gradusnikov.eclipse.assistai.prompt;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PromptParserTest {


    @Test
    void parseToHtml_regularString() {
        PromptParser parser = new PromptParser("Hello, World!");
        String result = parser.parseToHtml();
        assertThat(result).isEqualTo("Hello, World!");
    }

    @ParameterizedTest
    @CsvSource({
        "java",
        "python",
        "c"
    })
    void parseToHtml_codeBlock(String language) {
        String prompt = """
                ```${lang}
                print("Hello!\\n");
                ```
                test.
                """.replace("${lang}", language);
        PromptParser  parser = new PromptParser(prompt);
        String result = parser.parseToHtml();
        assertThat(result).isEqualTo("""
                <pre><code lang="${lang}">
                print("Hello!\\n");
                </code></pre>
                test.""".replace("${lang}", language) );
    }
    @Test
    void parseToHtml_codeBlock2()
    {
        String prompt = """
            ```java
            /**
             * Generates a random double value between 0 and 50.
             *
             * @return a random double value between 0 and 50.
             */
            public double random() {
                return Math.random() * 100 / 2;
            }
            ```
            """;
        PromptParser  parser = new PromptParser(prompt);
        String result = parser.parseToHtml();
        System.out.println( result );
    }

    @Test
    void parseToHtml_codeBlock3()
    {
        String prompt = """
            To add JUnit to your `pom.xml`, you need to add the following dependency to the `<dependencies>` section:
            
            For JUnit 4:
            
            ```xml
            <dependency>
              <groupId>junit</groupId>
              <artifactId>junit</artifactId>
              <version>4.13.2</version>
              <scope>test</scope>
            </dependency>
            ```
            
            For JUnit 5 (also known as JUnit Jupiter):
            
            ```xml
            <dependency>
              <groupId>org.junit.jupiter</groupId>
              <artifactId>junit-jupiter-engine</artifactId>
              <version>5.8.1</version>
              <scope>test</scope>
            </dependency>
            ```
            
            Remember to replace the version numbers with the most recent stable version if needed.                
                """;
        PromptParser  parser = new PromptParser(prompt);
        String result = parser.parseToHtml();
        System.out.println( result );
    }
    
}