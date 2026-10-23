package com.nextaicommerce.platform.shipping;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ShippingPreferences {
    public record Settings(String labelSize,CarrierLabelComposer.Layout layout) {
        public Settings {
            if(!List.of("THERMAL_4X6","LETTER").contains(labelSize==null?"":labelSize)
                ||layout==null||layout==CarrierLabelComposer.Layout.SANDBOX_80_20)
                throw new IllegalArgumentException("Choose a supported label size and packing-slip layout.");
            if("LETTER".equals(labelSize)&&layout==CarrierLabelComposer.Layout.COMBINED_80_20)
                throw new IllegalArgumentException("Bottom packing slips require 4×6 thermal paper.");
        }
    }
    private final JdbcTemplate jdbc;
    public ShippingPreferences(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private void scope(UUID tenant,UUID store){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM marketplace_connections WHERE tenant_id=? AND id=?)",Boolean.class,tenant,store)))
            throw new IllegalArgumentException("Choose a store in this account.");
    }
    public Settings get(UUID tenant,UUID store){scope(tenant,store);return jdbc.query("SELECT label_size,layout FROM shipping_print_preferences WHERE tenant_id=? AND marketplace_connection_id=?",(rs,n)->new Settings(rs.getString(1),CarrierLabelComposer.Layout.valueOf(rs.getString(2))),tenant,store).stream().findFirst().orElse(new Settings("THERMAL_4X6",CarrierLabelComposer.Layout.ORIGINAL));}
    public Settings save(UUID tenant,UUID store,Settings settings){scope(tenant,store);jdbc.update("INSERT INTO shipping_print_preferences(tenant_id,marketplace_connection_id,label_size,layout) VALUES (?,?,?,?) ON CONFLICT(tenant_id,marketplace_connection_id) DO UPDATE SET label_size=excluded.label_size,layout=excluded.layout",tenant,store,settings.labelSize(),settings.layout().name());return settings;}
}
