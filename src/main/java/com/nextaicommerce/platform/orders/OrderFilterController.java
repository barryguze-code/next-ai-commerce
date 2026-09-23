package com.nextaicommerce.platform.orders;

import com.nextaicommerce.platform.web.AccountSelectionController;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Presets are private to a signed-in user within their selected account. */
@RestController
@RequestMapping("/app/table-filters/orders")
public class OrderFilterController {
    private final JdbcTemplate jdbc; private final ObjectMapper json;
    public OrderFilterController(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public record Save(String name,Map<String,String> filters){}
    private UUID scope(HttpSession session){
        if(!(session.getAttribute(AccountSelectionController.TENANT_ID) instanceof UUID id))throw new ResponseStatusException(HttpStatus.CONFLICT,"Choose an account first.");
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,id.toString());return id;
    }
    @GetMapping @Transactional
    public List<Map<String,Object>> list(HttpSession session,Authentication auth){
        UUID tenant=scope(session);
        return jdbc.query("SELECT id,name,filters::text FROM user_table_filters WHERE tenant_id=? AND owner_key=? AND widget='orders' ORDER BY created_at,id",
            (rs,n)->Map.<String,Object>of("id",rs.getString(1),"name",rs.getString(2),"filters",json.readTree(rs.getString(3))),tenant,auth.getName());
    }
    @PostMapping @Transactional
    public Map<String,String> save(@RequestBody Save payload,HttpSession session,Authentication auth){
        UUID tenant=scope(session);String name=payload.name()==null?"":payload.name().trim();
        if(name.isEmpty()||name.length()>40)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use a tag name of 1–40 characters.");
        Map<String,String> filters;
        try{filters=OrderSmartFilters.clean(payload.filters());}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}
        // Serializes per-user saves so simultaneous requests cannot exceed the limit.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,tenant+":"+auth.getName()+":orders");
        if(jdbc.queryForObject("SELECT count(*) FROM user_table_filters WHERE tenant_id=? AND owner_key=? AND widget='orders'",Integer.class,tenant,auth.getName())>=30)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"You can save up to 30 tags. Remove one before adding another.");
        UUID id=UUID.randomUUID();
        int added=jdbc.update("INSERT INTO user_table_filters(id,tenant_id,owner_key,widget,name,filters) VALUES (?,?,?,'orders',?,?::jsonb) ON CONFLICT (tenant_id,owner_key,widget,name) DO NOTHING",id,tenant,auth.getName(),name,json.writeValueAsString(filters));
        if(added==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"A tag with that name already exists.");
        return Map.of("id",id.toString(),"name",name);
    }
    public record Rename(String name){}
    @PatchMapping("/{id}") @Transactional
    public Map<String,String> rename(@PathVariable UUID id,@RequestBody Rename payload,HttpSession session,Authentication auth){
        UUID tenant=scope(session);String name=payload.name()==null?"":payload.name().trim();
        if(name.isEmpty()||name.length()>40)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use a tag name of 1–40 characters.");
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,tenant+":"+auth.getName()+":orders");
        if(jdbc.queryForObject("SELECT count(*) FROM user_table_filters WHERE tenant_id=? AND owner_key=? AND widget='orders' AND name=? AND id<>?",Integer.class,tenant,auth.getName(),name,id)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"A tag with that name already exists.");
        if(jdbc.update("UPDATE user_table_filters SET name=? WHERE id=? AND tenant_id=? AND owner_key=? AND widget='orders'",name,id,tenant,auth.getName())==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return Map.of("id",id.toString(),"name",name);
    }
    @DeleteMapping("/{id}") @Transactional
    public Map<String,Boolean> delete(@PathVariable UUID id,HttpSession session,Authentication auth){
        UUID tenant=scope(session);
        if(jdbc.update("DELETE FROM user_table_filters WHERE id=? AND tenant_id=? AND owner_key=? AND widget='orders'",id,tenant,auth.getName())==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return Map.of("deleted",true);
    }
}
