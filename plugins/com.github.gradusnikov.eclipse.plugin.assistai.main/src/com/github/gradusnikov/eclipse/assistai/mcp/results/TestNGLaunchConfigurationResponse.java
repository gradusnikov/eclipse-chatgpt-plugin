package com.github.gradusnikov.eclipse.assistai.mcp.results;

import java.util.List;

/**
 * The outcome of creating, or updating, a TestNG launch configuration.
 * <p>
 * The configuration is only saved, never launched: {@link #configurationName()} is what
 * a caller then hands to {@code launchConfiguration}. The target lists echo what was
 * written into the configuration, so a caller can see what will run without reading the
 * {@code .launch} file back.
 *
 * @param configurationName the exact name to pass to {@code launchConfiguration}; null
 *            when nothing was saved
 * @param created true when a new configuration was written, false when an existing
 *            TestNG configuration of the same name was updated in place
 * @param projectName the project the configuration runs from
 * @param launchType CLASS, METHOD, PACKAGE, SUITE or GROUP; null when nothing was saved
 * @param classes the classes targeted ({@code CLASS_TEST_LIST})
 * @param methods the methods targeted ({@code METHOD_TEST_LIST})
 * @param packages the packages targeted ({@code PACKAGE_TEST_LIST})
 * @param suites the suite files targeted, as absolute file system paths
 *            ({@code SUITE_TEST_LIST})
 * @param groups the groups targeted ({@code GROUP_LIST})
 * @param groupClasses for a GROUP target, the test classes found to declare those groups
 *            - the classes TestNG will scan ({@code GROUP_LIST_CLASS}); empty otherwise
 * @param diagnostics empty on success
 * @param summaryText one-line human summary
 */
public record TestNGLaunchConfigurationResponse(
    String configurationName,
    boolean created,
    String projectName,
    String launchType,
    List<String> classes,
    List<String> methods,
    List<String> packages,
    List<String> suites,
    List<String> groups,
    List<String> groupClasses,
    List<Diagnostic> diagnostics,
    String summaryText
)
{
    public static TestNGLaunchConfigurationResponse success( String name, boolean created, String projectName,
            String launchType, List<String> classes, List<String> methods, List<String> packages,
            List<String> suites, List<String> groups, List<String> groupClasses )
    {
        String summary = ( created ? "Created" : "Updated" ) + " TestNG launch configuration '" + name
                + "' (" + launchType + ") in project '" + projectName
                + "'. Run it with launchConfiguration(configurationName='" + name + "').";
        return new TestNGLaunchConfigurationResponse( name, created, projectName, launchType,
                List.copyOf( classes ), List.copyOf( methods ), List.copyOf( packages ),
                List.copyOf( suites ), List.copyOf( groups ), List.copyOf( groupClasses ),
                Diagnostic.none(), summary );
    }

    public static TestNGLaunchConfigurationResponse failure( String projectName, Diagnostic diagnostic )
    {
        return new TestNGLaunchConfigurationResponse( null, false, projectName, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of( diagnostic ), diagnostic.message() );
    }
}
