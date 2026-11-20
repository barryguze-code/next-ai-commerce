package com.nextaicommerce.platform.orders;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

/** One-time local UAT bootstrap for the user-supplied packing history. Never enabled in production. */
@Component
@Profile("local")
class LocalPackingHistoryLoader {
    private static final Logger log=LoggerFactory.getLogger(LocalPackingHistoryLoader.class);
    private final JdbcTemplate jdbc;private final String source;
    LocalPackingHistoryLoader(JdbcTemplate jdbc,@Value("${app.local-packing-history.path:}") String source){this.jdbc=jdbc;this.source=source;}

    @EventListener(ApplicationReadyEvent.class)
    void load(){
        if(source==null||source.isBlank()||!Files.isRegularFile(Path.of(source)))return;
        Integer existing=jdbc.queryForObject("SELECT count(*) FROM temporary_order_packaging_lookup",Integer.class);
        if(existing!=null&&existing>0){log.info("Local packing history already loaded — {} decision(s)",existing);return;}
        try{
            Map<String,Scope> orders=new LinkedHashMap<>();
            jdbc.query("SELECT amazon_order_id,tenant_id,marketplace_connection_id FROM amazon_orders",(RowCallbackHandler)rs->
                orders.putIfAbsent(rs.getString(1),new Scope(rs.getObject(2,UUID.class),rs.getObject(3,UUID.class))));
            Map<Scope,List<Row>> grouped=new LinkedHashMap<>();int skipped=0;
            try(BufferedReader reader=Files.newBufferedReader(Path.of(source))){
                reader.readLine();String line;
                while((line=reader.readLine())!=null){
                    String[] fields=line.split("\\t",-1);if(fields.length<4)continue;
                    String orderId=fields[0].trim(),packaging=fields[2].trim();Scope scope=orders.get(orderId);
                    if(scope==null||packaging.isBlank()){skipped++;continue;}
                    grouped.computeIfAbsent(scope,key->new ArrayList<>()).add(new Row(orderId,fields[1].trim(),packaging,fields[3].trim()));
                }
            }
            int imported=0;
            for(var entry:grouped.entrySet()){
                setTenant(entry.getKey().tenantId());List<Row> rows=entry.getValue();
                jdbc.batchUpdate("""
                    INSERT INTO temporary_order_packaging_lookup(tenant_id,marketplace_connection_id,amazon_order_id,order_item_summary,packaging,order_sku_qty_list,source_note)
                    VALUES (?,?,?,?,?,?,'Imported local Packaging.txt history')
                    ON CONFLICT (tenant_id,marketplace_connection_id,amazon_order_id) DO UPDATE SET
                      order_item_summary=EXCLUDED.order_item_summary,packaging=EXCLUDED.packaging,
                      order_sku_qty_list=EXCLUDED.order_sku_qty_list,source_note=EXCLUDED.source_note,imported_at=now()
                    """,rows,500,(PreparedStatement statement,Row row)->{
                    statement.setObject(1,entry.getKey().tenantId());statement.setObject(2,entry.getKey().connectionId());statement.setString(3,row.orderId());
                    statement.setString(4,row.orderSummary());statement.setString(5,row.packaging());statement.setString(6,row.skuSummary());
                });imported+=rows.size();
            }
            log.info("Local packing history ready — imported {} decision(s), skipped {} unmatched or blank row(s)",imported,skipped);
        }catch(Exception ex){log.warn("Local packing history could not be loaded; packing slips will still work with manual package choices.",ex);}
    }
    private void setTenant(UUID tenantId){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());}
    private record Scope(UUID tenantId,UUID connectionId){}
    private record Row(String orderId,String orderSummary,String packaging,String skuSummary){}
}
