package com.nextaicommerce.platform.sync;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

public final class AmazonMarketplaceTime {
    private static final List<DateTimeFormatter> LOCAL_DATE_TIMES=List.of(
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss",Locale.US),
        DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss",Locale.US));
    private static final List<DateTimeFormatter> LOCAL_DATES=List.of(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("MM/dd/yyyy",Locale.US));
    private AmazonMarketplaceTime() {}

    public static ParsedTime parse(String value,String marketplaceId){
        if(value==null||value.isBlank())return new ParsedTime(null,null,value);
        String clean=value.trim();ZoneId marketplaceZone=zone(marketplaceId);Instant instant=null;
        try{instant=Instant.parse(clean);}catch(DateTimeParseException ignored){}
        if(instant==null)try{instant=OffsetDateTime.parse(clean).toInstant();}catch(DateTimeParseException ignored){}
        if(instant==null)try{instant=OffsetDateTime.parse(clean,DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ",Locale.US)).toInstant();}catch(DateTimeParseException ignored){}
        if(instant==null)try{instant=ZonedDateTime.parse(clean,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z",Locale.US)).toInstant();}catch(DateTimeParseException ignored){}
        if(instant==null){for(DateTimeFormatter formatter:LOCAL_DATE_TIMES)try{instant=LocalDateTime.parse(clean,formatter).atZone(marketplaceZone).toInstant();break;}catch(DateTimeParseException ignored){}}
        if(instant==null){for(DateTimeFormatter formatter:LOCAL_DATES)try{instant=LocalDate.parse(clean,formatter).atStartOfDay(marketplaceZone).toInstant();break;}catch(DateTimeParseException ignored){}}
        return instant==null?new ParsedTime(null,null,clean):new ParsedTime(Timestamp.from(instant),instant.atZone(marketplaceZone).toLocalDate(),clean);
    }

    public static ZoneId zone(String marketplaceId){return switch(marketplaceId){
        case "ATVPDKIKX0DER"->ZoneId.of("America/Los_Angeles");
        case "A1F83G8C2ARO7P"->ZoneId.of("Europe/London");
        case "A2EUQ1WTGCTBG2"->ZoneId.of("America/Toronto");
        default->ZoneId.of("UTC");};}
    public record ParsedTime(Timestamp instant,LocalDate marketplaceDate,String sourceValue){}
}
