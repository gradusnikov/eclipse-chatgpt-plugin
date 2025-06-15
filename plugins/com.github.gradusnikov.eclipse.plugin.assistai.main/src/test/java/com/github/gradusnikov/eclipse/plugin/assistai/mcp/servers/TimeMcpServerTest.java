package com.github.gradusnikov.eclipse.plugin.assistai.mcp.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.mcp.servers.TimeMcpServer;

public class TimeMcpServerTest {

    private TimeMcpServer timeMcpServer;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter OUTPUT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    @BeforeEach
    public void setUp() {
        timeMcpServer = new TimeMcpServer();
    }

    @Test
    public void testGetCurrentTime() {
        String result = timeMcpServer.getCurrentTime();
        // Verify the result is not null and has the expected format
        assertTrue(result != null && !result.isEmpty(), "Current time should not be null or empty");
        assertTrue(result.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} [A-Za-z0-9+/-]+"), 
                 "Current time should match the expected format");
    }

    @Test
    public void testConvertTimeZone_SameTimeZone() {
        String timeString = "2023-05-15 14:30:00";
        String sourceZone = "UTC";
        String targetZone = "UTC";
        
        String result = timeMcpServer.convertTimeZone(timeString, sourceZone, targetZone);
        
        // When source and target are the same, it should return the original time string
        assertEquals(timeString, result);
    }

    @Test
    public void testConvertTimeZone_DifferentTimeZones() {
        String timeString = "2023-05-15 14:30:00";
        String sourceZone = "UTC";
        String targetZone = "America/New_York";
        
        String result = timeMcpServer.convertTimeZone(timeString, sourceZone, targetZone);
        
        // Convert the expected time manually for verification
        ZonedDateTime utcTime = ZonedDateTime.parse(timeString + " UTC", 
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
        ZonedDateTime nyTime = utcTime.withZoneSameInstant(ZoneId.of(targetZone));
        String expected = nyTime.format(OUTPUT_FORMATTER);
        
        assertEquals(expected, result);
    }

    @Test
    public void testConvertTimeZone_InvalidSourceZone() {
        String timeString = "2023-05-15 14:30:00";
        String sourceZone = "INVALID_ZONE";
        String targetZone = "UTC";
        
        String result = timeMcpServer.convertTimeZone(timeString, sourceZone, targetZone);
        
        // Should return an error message
        assertTrue(result.startsWith("Error converting time zone"), 
                 "Should return an error for invalid source zone");
    }

    @Test
    public void testConvertTimeZone_InvalidTargetZone() {
        String timeString = "2023-05-15 14:30:00";
        String sourceZone = "UTC";
        String targetZone = "INVALID_ZONE";
        
        String result = timeMcpServer.convertTimeZone(timeString, sourceZone, targetZone);
        
        // Should return an error message
        assertTrue(result.startsWith("Error converting time zone"), 
                 "Should return an error for invalid target zone");
    }

    @Test
    public void testConvertTimeZone_InvalidTimeFormat() {
        String timeString = "2023-05-15T14:30:00"; // Wrong format
        String sourceZone = "UTC";
        String targetZone = "America/New_York";
        
        String result = timeMcpServer.convertTimeZone(timeString, sourceZone, targetZone);
        
        // Should return an error message
        assertTrue(result.startsWith("Error converting time zone"), 
                 "Should return an error for invalid time format");
    }

    @Test
    public void testConvertTimeZone_NullParameters() {
        // Test with null source zone (should use system default)
        String timeString = "2023-05-15 14:30:00";
        String result = timeMcpServer.convertTimeZone(timeString, null, "UTC");
        
        // Should not throw exception and return a valid result
        assertTrue(!result.startsWith("Error"), 
                 "Should handle null source zone gracefully");
        
        // Test with null target zone (should default to UTC)
        result = timeMcpServer.convertTimeZone(timeString, "UTC", null);
        
        // Should not throw exception and return a valid result
        assertTrue(!result.startsWith("Error"), 
                 "Should handle null target zone gracefully");
    }
}