package com.github.gradusnikov.eclipse.assistai.preferences;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ModelApiDescriptorUtilities
{
    public static String toJson( ModelApiDescriptor ... descriptors )
    {
        var list = Arrays.asList( descriptors );
        return toJson( list );
    }

    public static List<ModelApiDescriptor> fromJson( String json )
    {
        ObjectMapper mapper = new ObjectMapper();
        try
        {
            List<ModelApiDescriptor> values = mapper.readValue( json.getBytes(), new TypeReference<List<ModelApiDescriptor>>()
            {
            } );
            return values;
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }
    }

    public static String toJson( List<ModelApiDescriptor> list )
    {
        ObjectMapper mapper = new ObjectMapper();
        try
        {
            return mapper.writeValueAsString( list );
        }
        catch ( JsonProcessingException e )
        {
            throw new RuntimeException( e );
        }
    }
}
