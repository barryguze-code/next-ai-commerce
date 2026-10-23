package com.nextaicommerce.platform.marketplace;

import com.nextaicommerce.platform.web.AccountSelectionController;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import jakarta.servlet.http.HttpSession;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Controller
public class MarketplaceSkuCsvController {
    private final MarketplaceSkuCsvExport exporter;private final WorkspaceAccessRepository workspace;private final ObjectMapper json;
    public MarketplaceSkuCsvController(MarketplaceSkuCsvExport exporter,WorkspaceAccessRepository workspace,ObjectMapper json){this.exporter=exporter;this.workspace=workspace;this.json=json;}
    @GetMapping("/app/marketplace-skus/export.csv")
    public ResponseEntity<byte[]> export(@RequestParam Map<String,String> params,HttpSession session,Authentication authentication){
        UUID tenant=id(session,AccountSelectionController.TENANT_ID),connection=id(session,AccountSelectionController.STORE_ID);access(tenant,connection,authentication);
        if(params.values().stream().anyMatch(v->v.length()>16000)||params.size()>75)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid export request.");
        Map<String,Object> filters=new LinkedHashMap<>();
        try{var node=json.readTree(params.getOrDefault("columnFilters","{}"));if(!node.isObject())throw new IllegalArgumentException();for(var entry:node.properties()){if(entry.getValue().isString())filters.put(entry.getKey(),entry.getValue().asString());else if(entry.getValue().isObject()){Map<String,String> bounds=new HashMap<>();entry.getValue().properties().forEach(e->{if(!e.getValue().isString())throw new IllegalArgumentException();bounds.put(e.getKey(),e.getValue().asString());});filters.put(entry.getKey(),bounds);}else throw new IllegalArgumentException();}}
        catch(RuntimeException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid column filters.");}
        List<String> columns=List.of(params.getOrDefault("columns","").split(",",-1));
        var request=new MarketplaceSkuCsvExport.Request(params.getOrDefault("q",""),params.getOrDefault("status","ALL"),params.getOrDefault("sort","status"),params.getOrDefault("direction","asc"),MarketplaceSkuFilters.defaults(params),columns,filters);
        MarketplaceSkuCsvExport.Artifact artifact;
        try{artifact=exporter.generate(tenant,connection,request);}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}catch(RuntimeException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"CSV preparation failed. No complete download was produced. Please retry.");}
        if(!tenant.equals(id(session,AccountSelectionController.TENANT_ID))||!connection.equals(id(session,AccountSelectionController.STORE_ID)))throw new ResponseStatusException(HttpStatus.CONFLICT,"Account or store changed. Retry the export.");
        access(tenant,connection,authentication);
        String stamp=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(artifact.asOf());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"marketplace-skus-"+stamp+".csv\"")
            .header("X-CSV-Rows",Integer.toString(artifact.rows())).header("X-CSV-Schema","marketplace-skus-v1")
            .contentLength(artifact.bytes().length).body(artifact.bytes());
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> problem(ResponseStatusException error){
        var body=ProblemDetail.forStatusAndDetail(error.getStatusCode(),Objects.toString(error.getReason(),"CSV preparation failed. No download was started."));
        return ResponseEntity.status(error.getStatusCode()).contentType(MediaType.APPLICATION_PROBLEM_JSON).cacheControl(CacheControl.noStore()).body(body);
    }
    static UUID id(HttpSession session,String key){Object value=session.getAttribute(key);if(value instanceof UUID id)return id;throw new ResponseStatusException(HttpStatus.CONFLICT,"Choose an account and Amazon store before exporting.");}
    void access(UUID tenant,UUID connection,Authentication auth){
        if(auth==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        boolean admin=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_PLATFORM_ADMIN"));
        boolean allowed=workspace.listAccountOptions(auth.getName(),admin).stream().filter(a->tenant.equals(a.id())).flatMap(a->a.stores().stream()).anyMatch(c->connection.equals(c.id())&&"AMAZON".equals(c.channel()));
        if(!allowed)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Store access unavailable.");
    }
}
