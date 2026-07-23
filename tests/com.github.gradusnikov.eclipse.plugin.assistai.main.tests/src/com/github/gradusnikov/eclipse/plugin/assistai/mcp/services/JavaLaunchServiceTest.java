package com.github.gradusnikov.eclipse.plugin.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;

import com.github.gradusnikov.eclipse.assistai.mcp.services.JavaLaunchService;

/**
 * Plug-in tests for {@link JavaLaunchService#listLaunchConfigurations(String)}.
 *
 * <p>A temporary JUnit and a temporary Java Application launch configuration
 * are created in {@code @BeforeAll} and removed in {@code @AfterAll}, so the
 * tests run in a predictable environment regardless of what is already saved
 * in the workspace.
 */
@TestInstance( TestInstance.Lifecycle.PER_CLASS )
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
public class JavaLaunchServiceTest
{
    private static final String JUNIT_CONFIG_NAME  = "AssistAITest_JUnitConfig";
    private static final String JAVA_CONFIG_NAME   = "AssistAITest_JavaAppConfig";
    private static final String PDE_CONFIG_NAME    = "AssistAITest_PDEJUnitConfig";

    private static final String JUNIT_TYPE_ID      = "org.eclipse.jdt.junit.launchconfig";
    private static final String JAVA_TYPE_ID       = "org.eclipse.jdt.launching.localJavaApplication";
    private static final String PDE_JUNIT_TYPE_ID  = "org.eclipse.pde.ui.JunitLaunchConfig";

    private JavaLaunchService service;
    private ILaunchConfiguration junitConfig;
    private ILaunchConfiguration javaConfig;
    private ILaunchConfiguration pdeConfig;     // may be null if PDE not available

    @BeforeAll
    public void setUp() throws Exception
    {
        BundleContext bundleContext = FrameworkUtil.getBundle( JavaLaunchServiceTest.class ).getBundleContext();

        ServiceTracker<ILog, ILog> logTracker = new ServiceTracker<>( bundleContext, ILog.class, null );
        logTracker.open();
        ILog log = logTracker.getService();

        IEclipseContext context = EclipseContextFactory.getServiceContext( bundleContext );
        context.set( UISynchronize.class, new UISynchronize()
        {
            @Override public void syncExec( Runnable r ) { r.run(); }
            @Override public void asyncExec( Runnable r ) { r.run(); }
            @Override protected boolean isUIThread( Thread t ) { return false; }
            @Override protected void showBusyWhile( Runnable r ) { r.run(); }
            @Override protected boolean dispatchEvents() { return false; }
        } );
        if ( log != null ) context.set( ILog.class, log );

        service = ContextInjectionFactory.make( JavaLaunchService.class, context );

        ILaunchManager lm = DebugPlugin.getDefault().getLaunchManager();

        // Create a plain JUnit launch config
        ILaunchConfigurationType junitType = lm.getLaunchConfigurationType( JUNIT_TYPE_ID );
        if ( junitType != null )
        {
            ILaunchConfigurationWorkingCopy wc = junitType.newInstance( null, JUNIT_CONFIG_NAME );
            wc.setAttribute( IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, "SomeTestProject" );
            wc.setAttribute( IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "com.example.MyTest" );
            junitConfig = wc.doSave();
        }

        // Create a Java Application launch config
        ILaunchConfigurationType javaType = lm.getLaunchConfigurationType( JAVA_TYPE_ID );
        if ( javaType != null )
        {
            ILaunchConfigurationWorkingCopy wc = javaType.newInstance( null, JAVA_CONFIG_NAME );
            wc.setAttribute( IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, "SomeProject" );
            wc.setAttribute( IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "com.example.Main" );
            javaConfig = wc.doSave();
        }

        // Create a PDE JUnit launch config (may not be available in all environments)
        ILaunchConfigurationType pdeType = lm.getLaunchConfigurationType( PDE_JUNIT_TYPE_ID );
        if ( pdeType != null )
        {
            ILaunchConfigurationWorkingCopy wc = pdeType.newInstance( null, PDE_CONFIG_NAME );
            wc.setAttribute( IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, "SomePluginProject" );
            wc.setAttribute( IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "com.example.MyPluginTest" );
            pdeConfig = wc.doSave();
        }
    }

    @AfterAll
    public void tearDown()
    {
        deleteQuietly( junitConfig );
        deleteQuietly( javaConfig );
        deleteQuietly( pdeConfig );
    }

    private void deleteQuietly( ILaunchConfiguration config )
    {
        if ( config == null ) return;
        try { config.delete(); } catch ( CoreException ignored ) {}
    }

    // -------------------------------------------------------------------------
    // Output format
    // -------------------------------------------------------------------------

    @Test
    @Order( 1 )
    public void testListAll_returnsJsonArray()
    {
        String result = service.listLaunchConfigurations( null );
        assertNotNull( result );
        assertTrue( result.startsWith( "[" ), "Expected JSON array start '[', got: " + result );
        assertTrue( result.endsWith( "]" ),   "Expected JSON array end ']', got: " + result );
    }

    @Test
    @Order( 2 )
    public void testListAll_emptyFilter_sameAsNull()
    {
        String withNull  = service.listLaunchConfigurations( null );
        String withEmpty = service.listLaunchConfigurations( "" );
        String withAll   = service.listLaunchConfigurations( "all" );
        assertEquals( withNull, withEmpty, "null and empty filter should return the same result" );
        assertEquals( withNull, withAll,   "null and 'all' filter should return the same result" );
    }

    @Test
    @Order( 3 )
    public void testListAll_containsExpectedFields()
    {
        String result = service.listLaunchConfigurations( null );
        // "name" is always present; other fields only appear when non-empty.
        // Our setUp creates configs with project and mainClass set, so all
        // five fields should appear somewhere in a non-empty array.
        if ( !"[]".equals( result ) )
        {
            assertTrue( result.contains( "\"name\":" ), "Missing 'name' field" );
        }
        // With our test fixtures (project + mainClass set) the optional fields appear too
        if ( junitConfig != null )
        {
            assertTrue( result.contains( "\"typeId\":"      ), "Missing 'typeId' field" );
            assertTrue( result.contains( "\"typeName\":"    ), "Missing 'typeName' field" );
            assertTrue( result.contains( "\"projectName\":" ), "Missing 'projectName' field" );
            assertTrue( result.contains( "\"mainClass\":"   ), "Missing 'mainClass' field" );
        }
    }

    // -------------------------------------------------------------------------
    // Filtering — junit
    // -------------------------------------------------------------------------

    @Test
    @Order( 10 )
    public void testFilterJunit_containsOurJUnitConfig()
    {
        if ( junitConfig == null ) return;   // type not available in this environment

        String result = service.listLaunchConfigurations( "junit" );
        assertTrue( result.contains( "\"" + JUNIT_CONFIG_NAME + "\"" ),
            "Expected JUnit config in result, got: " + result );
    }

    @Test
    @Order( 11 )
    public void testFilterJunit_doesNotContainJavaAppConfig()
    {
        if ( javaConfig == null ) return;

        String result = service.listLaunchConfigurations( "junit" );
        assertFalse( result.contains( "\"" + JAVA_CONFIG_NAME + "\"" ),
            "Java app config should not appear in 'junit' filter result" );
    }

    @Test
    @Order( 12 )
    public void testFilterJunit_typeIdIsCorrect()
    {
        if ( junitConfig == null ) return;

        String result = service.listLaunchConfigurations( "junit" );
        // Every typeId in the result must be the JUnit type
        // Simple check: the PDE type ID must not appear
        assertFalse( result.contains( PDE_JUNIT_TYPE_ID ),
            "PDE JUnit type should not appear in 'junit' filter result" );
    }

    // -------------------------------------------------------------------------
    // Filtering — junit-plugin
    // -------------------------------------------------------------------------

    @Test
    @Order( 20 )
    public void testFilterJunitPlugin_containsOurPDEConfig()
    {
        if ( pdeConfig == null ) return;   // PDE type not available

        String result = service.listLaunchConfigurations( "junit-plugin" );
        assertTrue( result.contains( "\"" + PDE_CONFIG_NAME + "\"" ),
            "Expected PDE JUnit config in result, got: " + result );
    }

    @Test
    @Order( 21 )
    public void testFilterJunitPlugin_doesNotContainPlainJUnitConfig()
    {
        if ( junitConfig == null || pdeConfig == null ) return;

        String result = service.listLaunchConfigurations( "junit-plugin" );
        assertFalse( result.contains( "\"" + JUNIT_CONFIG_NAME + "\"" ),
            "Plain JUnit config should not appear in 'junit-plugin' filter result" );
    }

    // -------------------------------------------------------------------------
    // Filtering — all
    // -------------------------------------------------------------------------

    @Test
    @Order( 30 )
    public void testFilterAll_containsJUnitAndJavaApp()
    {
        if ( junitConfig == null || javaConfig == null ) return;

        String result = service.listLaunchConfigurations( "all" );
        assertTrue( result.contains( "\"" + JUNIT_CONFIG_NAME + "\"" ),
            "Expected JUnit config in 'all' result" );
        assertTrue( result.contains( "\"" + JAVA_CONFIG_NAME + "\"" ),
            "Expected Java app config in 'all' result" );
    }

    // -------------------------------------------------------------------------
    // Filtering — unknown type
    // -------------------------------------------------------------------------

    @Test
    @Order( 40 )
    public void testFilterUnknown_returnsEmptyArray()
    {
        String result = service.listLaunchConfigurations( "completely-unknown-type-xyz-12345" );
        assertEquals( "[]", result,
            "Expected empty array for unknown type filter, got: " + result );
    }

    // -------------------------------------------------------------------------
    // Filtering — substring of type ID
    // -------------------------------------------------------------------------

    @Test
    @Order( 50 )
    public void testFilterSubstring_localJava_matchesJavaApp()
    {
        if ( javaConfig == null ) return;

        // "localJava" is a substring of "org.eclipse.jdt.launching.localJavaApplication"
        String result = service.listLaunchConfigurations( "localJava" );
        assertTrue( result.contains( "\"" + JAVA_CONFIG_NAME + "\"" ),
            "Expected Java app config when filtering by 'localJava' substring, got: " + result );
    }

    @Test
    @Order( 51 )
    public void testFilterSubstring_localJava_doesNotContainJUnit()
    {
        if ( junitConfig == null || javaConfig == null ) return;

        String result = service.listLaunchConfigurations( "localJava" );
        assertFalse( result.contains( "\"" + JUNIT_CONFIG_NAME + "\"" ),
            "JUnit config should not appear when filtering by 'localJava' substring" );
    }

    // -------------------------------------------------------------------------
    // JSON validity
    // -------------------------------------------------------------------------

    @Test
    @Order( 60 )
    public void testListLaunchConfigurations_outputIsValidJson() throws Exception
    {
        // The output must always be parseable JSON regardless of what launch configs exist.
        String result = service.listLaunchConfigurations( null );
        assertNotNull( result );
        com.fasterxml.jackson.databind.JsonNode node =
            new com.fasterxml.jackson.databind.ObjectMapper().readTree( result );
        assertTrue( node.isArray(), "Expected a JSON array, got: " + result );
    }
}
