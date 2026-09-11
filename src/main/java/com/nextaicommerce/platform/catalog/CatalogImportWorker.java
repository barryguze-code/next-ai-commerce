package com.nextaicommerce.platform.catalog;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import com.nextaicommerce.platform.marketplace.MarketplaceSkuAutoMapper;

@Component
public class CatalogImportWorker {
    private static final Logger log=LoggerFactory.getLogger(CatalogImportWorker.class);
    private final CatalogImportService imports;private final CatalogImportProgress progress;private final MarketplaceSkuAutoMapper mapper;
    CatalogImportWorker(CatalogImportService imports,CatalogImportProgress progress,MarketplaceSkuAutoMapper mapper){this.imports=imports;this.progress=progress;this.mapper=mapper;}
    @Async
    public void start(UUID tenantId,String actorEmail,UUID importId,Map<String,String> mapping){
        try{progress.processing(tenantId,importId,0);imports.approve(tenantId,actorEmail,importId,mapping);progress.mapping(tenantId,importId);var result=mapper.mapTenant(tenantId);progress.complete(tenantId,importId,result.mapped(),result.remaining());}
        catch(Exception ex){progress.fail(tenantId,importId,ex.getMessage());log.error("Catalogue background import failed: import={} reason={}",importId,ex.getMessage(),ex);}
    }
}
