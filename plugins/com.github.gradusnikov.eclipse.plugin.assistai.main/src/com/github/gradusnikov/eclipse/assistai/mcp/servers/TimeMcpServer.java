package com.github.gradusnikov.eclipse.assistai.mcp.servers;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.mcp.annotations.McpServer;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.Tool;
import com.github.gradusnikov.eclipse.assistai.mcp.annotations.ToolParam;

@Creatable
@McpServer(name = "time")
public class TimeMcpServer
{
    @Tool(name = "currentTime", description = "Returns the current date and time in the following format: yyyy-MM-dd HH:mm:ss", type = "object")
    public String getCurrentTime()
    {
        ZonedDateTime now = ZonedDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
        return now.format(formatter);
    }
    
    @Tool(name = "convertTimeZone", 
          description = "Converts time from one time zone to another. Returns a converted time in the yyyy-MM-dd HH:mm:ss z format.", 
          type = "object")
    public String convertTimeZone(@ToolParam(name="time", description = "Date/time in the format yyyy-MM-dd HH:mm:ss", required = true) String timeString, 
            @ToolParam(name="sourceZone", description = "Source time zone id such as, such as Europe/Paris or CST. Default: system time zone") String sourceZone, 
            @ToolParam(name="targetZone", description = "Target time zone id, such as Europer/Paris or CST. Default: UTC") String targetZone)
    {
        
        // Get source and target time zones from parameters
        var sourceZoneId = ZoneId.of( Optional.ofNullable( sourceZone ).orElse( ZoneId.systemDefault().getId() ) );
        var targetZoneId = ZoneId.of(Optional.ofNullable( targetZone ).orElse( "UTC" ) );
        
        if ( sourceZone.equals( targetZone ) )
        {
            return timeString;
        }
        
        try 
        {
            // Default to current time if not provided
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
            var time = formatter.parse( timeString + " " + sourceZoneId.getId() );
            var zoneTime = ZonedDateTime.from( time );
            
            // Then convert to the target time zone
            ZonedDateTime convertedTime = zoneTime.withZoneSameInstant( targetZoneId );
            
            // Format the result
            return convertedTime.format(formatter);
        } 
        catch (Exception e)
        {
            throw new RuntimeException( "Error converting time zone: " + e.getMessage() + 
                   ". Valid zone IDs include: " + ZoneId.getAvailableZoneIds() );
        }
    }
}