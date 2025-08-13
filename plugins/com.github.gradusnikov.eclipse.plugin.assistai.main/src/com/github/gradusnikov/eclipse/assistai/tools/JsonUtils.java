package com.github.gradusnikov.eclipse.assistai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonUtils
{
    public static String toJsonString( Object object )
    {
        try
        {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(object);
        }
        catch ( JsonProcessingException e )
        {
            throw new RuntimeException( e );
        }
    }
}
