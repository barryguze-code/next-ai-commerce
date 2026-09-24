package com.nextaicommerce.platform.catalog;

import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared identity rules for bulk import, manual catalogue and receiving. */
final class CatalogIdentity {
    private CatalogIdentity() {}
    static String distributionCenter(String value){
        String dc=value==null?"":value.trim().toUpperCase(Locale.ROOT).replaceFirst("^DC[\\s:#-]*","").trim();
        if(dc.isBlank())return "";
        if(!dc.matches("[A-Z0-9][A-Z0-9 -]{0,39}"))throw new IllegalArgumentException("Enter a DC / branch code of up to 40 letters or numbers.");
        return dc.matches("\\d+")?dc.replaceFirst("^0+(?!$)",""):dc;
    }
    static String scope(UUID tenant,String dc){return dc==null||dc.isBlank()?"ACCOUNT:"+tenant:"DC:"+dc;}
    static void lock(JdbcTemplate jdbc){
        // Catalogue writes only. Orders, stock reservations and publishing do not acquire this lock.
        jdbc.execute("SELECT pg_advisory_xact_lock(193641,1)");
    }
}
