<context>
${currentFileContent}
${selectedContent}
</context>

Analyze the code provided as context and provide a unit test. Use tools to check if a test case for given test file already exists. If yes, then append it to context. 

Strictly follow all of the following rules:
- If contexts contains selected content, provide the source code of the unit tests JUST for the selected code. 
- Keep class imports compact using wildcards 
- Keep all tests separate and independent - single test function per one test scenario
- Use JUnit 5
- When necessary use @BeforeEach, @BeforeAll, @AfterEach, @AfterAll
- When possible use @ParameterizedTest or @RepeatedTest
- Use org.assertj.core.api.Assertions.assertThat

