package com.github.gradusnikov.eclipse.assistai.mcp;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;

/**
 * Generates the MCP API reference from the annotations themselves.
 * <p>
 * The document exists because the alternative - a hand-written one - was wrong within
 * a week of every change to a tool. This reads the same {@link McpServer},
 * {@link Tool} and {@link ToolParam} declarations the server builds its schema from,
 * over the same server list the runtime registers, so a tool cannot be added, renamed
 * or reshaped without the document following. A test regenerates it and fails when the
 * committed file differs, which is what makes that guarantee real rather than
 * aspirational.
 * <p>
 * Result shapes are rendered from the record components rather than from the generated
 * JSON Schema: the schema is what a machine consumes and is far more verbose than a
 * person reading a reference needs.
 */
public final class McpApiDoc
{
    /** Regenerate with the script in {@code tools/}, not by editing the output. */
    private static final String HEADER = """
            # MCP API reference

            The tools this plugin exposes over MCP, and the shape of what each returns.

            **This file is generated.** It is produced from the `@McpServer`, `@Tool` and
            `@ToolParam` annotations by `McpApiDoc`, over the server list in
            `McpServerBuiltins`, and `McpApiDocPDETest` fails when it is out of date. Do not
            edit it by hand - change the annotations and regenerate:

            ```
            tools/generate-mcp-api.sh
            ```

            Every tool argument is passed as a string, whatever the parameter means; a
            required parameter is marked `*`. Tools marked *long* run asynchronously and
            return an operation id to poll with `getOperationStatus`.

            ## Results

            A result arrives twice on one response. `structuredContent` is the object a
            client branches on, and matches the shape named under **Returns**. A text
            block carries the same data for clients that read only text.

            The text block is a *rendering* of that object, not a copy of its
            serialization: a string spanning more than one line is lifted into a fenced
            code block, and the value it came from becomes `<rendered above as ...>`
            naming the block that now holds it. So a source body, a diff or a stack
            trace arrives as lines rather than as one escaped string. No field is
            dropped, and `structuredContent` itself is never altered.

            An empty result is an empty list and a count - never null, and never a
            sentence saying nothing was found. A field that can be absent is absent as
            `null` rather than as a sentinel or an invented value, so the advertised
            output schema admits null for everything except a list and a primitive.

            A failure is reported in the result rather than as a protocol error:
            a `status` a caller can branch on, and `diagnostics` carrying a coded
            `DiagnosticCode` with a message.
            """;

    private McpApiDoc()
    {
    }

    public static String generate()
    {
        return generateInto( new StringBuilder( HEADER ), new LinkedHashSet<>() );
    }

    private static String generateInto( StringBuilder out, Set<Class<?>> shapes )
    {
        List<Class<?>> servers = Arrays.stream( McpServerBuiltins.BUILT_IN_MCP_SERVERS )
                .sorted( Comparator.comparing( McpApiDoc::serverName ) )
                .toList();

        out.append( "\n## Servers\n\n| Server | Tools |\n|---|---|\n" );
        for ( Class<?> server : servers )
        {
            out.append( "| [" ).append( serverName( server ) ).append( "](#" )
               .append( anchor( serverName( server ) ) ).append( ") | " )
               .append( tools( server ).size() ).append( " |\n" );
        }

        for ( Class<?> server : servers )
        {
            out.append( "\n## " ).append( serverName( server ) ).append( "\n" );
            for ( Method tool : tools( server ) )
            {
                appendTool( out, tool, shapes );
            }
        }

        appendShapes( out, shapes );
        return out.toString();
    }

    private static void appendTool( StringBuilder out, Method method, Set<Class<?>> shapes )
    {
        Tool tool = method.getAnnotation( Tool.class );

        out.append( "\n### `" ).append( tool.name() ).append( "`" );
        if ( tool.longExecution() )
        {
            out.append( " *(long)*" );
        }
        out.append( "\n\n" ).append( tool.description().trim() ).append( "\n" );

        List<Parameter> parameters = Arrays.stream( method.getParameters() )
                .filter( p -> p.isAnnotationPresent( ToolParam.class ) )
                .toList();
        if ( !parameters.isEmpty() )
        {
            out.append( "\n| Parameter | Type | | Description |\n|---|---|---|---|\n" );
            for ( Parameter parameter : parameters )
            {
                ToolParam param = parameter.getAnnotation( ToolParam.class );
                out.append( "| `" ).append( param.name() ).append( "` | " )
                   .append( param.type() ).append( " | " )
                   .append( param.required() ? "\\*" : "" ).append( " | " )
                   .append( param.description().trim().replace( "|", "\\|" ) ).append( " |\n" );
            }
        }

        out.append( "\n**Returns** " ).append( typeLink( method.getGenericReturnType(), shapes ) ).append( "\n" );
    }

    private static void appendShapes( StringBuilder out, Set<Class<?>> shapes )
    {
        if ( shapes.isEmpty() )
        {
            return;
        }
        out.append( "\n## Result shapes\n" );

        // The set grows while it is walked - a record's components pull in their own
        // record and enum types - so it is drained rather than iterated.
        List<Class<?>> written = new ArrayList<>();
        while ( written.size() < shapes.size() )
        {
            List<Class<?>> pending = shapes.stream().filter( c -> !written.contains( c ) ).toList();
            for ( Class<?> shape : pending )
            {
                written.add( shape );
                appendShape( out, shape, shapes );
            }
        }
    }

    private static void appendShape( StringBuilder out, Class<?> shape, Set<Class<?>> shapes )
    {
        out.append( "\n### `" ).append( shapeHeading( shape ) ).append( "`\n\n" );

        if ( shape.isEnum() )
        {
            out.append( Arrays.stream( shape.getEnumConstants() )
                    .map( c -> "`" + c + "`" ).collect( Collectors.joining( " \\| " ) ) ).append( "\n" );
            return;
        }

        out.append( "| Field | Type |\n|---|---|\n" );
        for ( RecordComponent component : shape.getRecordComponents() )
        {
            out.append( "| `" ).append( component.getName() ).append( "` | " )
               .append( typeLink( component.getGenericType(), shapes ) ).append( " |\n" );
        }
    }

    /**
     * Renders a type, registering any record or enum it mentions so the appendix
     * describes it exactly once.
     */
    private static String typeLink( Type type, Set<Class<?>> shapes )
    {
        if ( type instanceof ParameterizedType parameterized )
        {
            Type[] arguments = parameterized.getActualTypeArguments();
            String raw = simpleName( (Class<?>) parameterized.getRawType() );
            if ( arguments.length == 1 && List.class.equals( parameterized.getRawType() ) )
            {
                return typeLink( arguments[0], shapes ) + "[]";
            }
            return raw + Arrays.stream( arguments ).map( a -> typeLink( a, shapes ) )
                    .collect( Collectors.joining( ", ", "&lt;", "&gt;" ) );
        }

        Class<?> clazz = (Class<?>) type;
        if ( clazz.isRecord() || clazz.isEnum() )
        {
            shapes.add( clazz );
            return "[`" + shapeHeading( clazz ) + "`](#" + anchor( shapeHeading( clazz ) ) + ")";
        }
        return "`" + simpleName( clazz ) + "`";
    }

    /** Nested types read better as {@code Outer.Inner} than as {@code Inner} alone. */
    private static String simpleName( Class<?> clazz )
    {
        return clazz.getEnclosingClass() == null
                ? clazz.getSimpleName()
                : clazz.getEnclosingClass().getSimpleName() + "." + clazz.getSimpleName();
    }

    private static String anchor( String heading )
    {
        return heading.toLowerCase().replace( ".", "" ).replace( " ", "-" );
    }

    /**
     * The name a shape is filed under.
     * <p>
     * Unqualified, even for a nested record: the heading is what the anchor is derived
     * from, and {@code Outer.Inner} is slugged differently by every Markdown dialect -
     * Eclipse's own preview resolves none of the forms this generator tried. An
     * unqualified name has no punctuation to disagree about.
     * {@link #duplicateShapeNames()} fails the build if two shapes ever collide on one.
     */
    private static String shapeHeading( Class<?> shape )
    {
        return shape.getEnclosingClass() == null
                ? shape.getSimpleName()
                : shape.getEnclosingClass().getSimpleName() + shape.getSimpleName();
    }

    /**
     * Whether every shape reachable from a tool has a distinct unqualified name.
     * <p>
     * Exposed for the test rather than checked here: a collision is a documentation
     * defect to be fixed by renaming a record, not something to paper over at render
     * time with a suffix nobody can predict.
     */
    public static List<String> duplicateShapeNames()
    {
        Set<Class<?>> shapes = new LinkedHashSet<>();
        generateInto( new StringBuilder(), shapes );

        List<String> names = shapes.stream().map( McpApiDoc::shapeHeading ).toList();
        return names.stream().distinct().filter( n -> names.indexOf( n ) != names.lastIndexOf( n ) ).toList();
    }

    private static String serverName( Class<?> server )
    {
        return server.getAnnotation( McpServer.class ).name();
    }

    private static List<Method> tools( Class<?> server )
    {
        return Arrays.stream( server.getDeclaredMethods() )
                .filter( m -> m.isAnnotationPresent( Tool.class ) )
                .sorted( Comparator.comparing( m -> m.getAnnotation( Tool.class ).name() ) )
                .toList();
    }
}
