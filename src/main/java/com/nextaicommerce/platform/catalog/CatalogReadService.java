package com.nextaicommerce.platform.catalog;

import com.nextaicommerce.platform.config.PlatformReadCache;
import com.nextaicommerce.platform.web.TablePaging;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Cache only account-visible catalogue projections. Conversation permissions are loaded separately. */
@Service
public class CatalogReadService {
    private final CatalogRepository catalog;
    private final PlatformReadCache cache;
    public CatalogReadService(CatalogRepository catalog,PlatformReadCache cache){this.catalog=catalog;this.cache=cache;}
    private record Query(String text,int page,int size) {}
    public record Snapshot(CatalogRepository.AccountItemPage page,List<CatalogRepository.VendorView> vendors,
            List<CatalogRepository.LocationView> locations,Map<UUID,List<CatalogRepository.VendorOfferView>> offers,
            Map<UUID,List<CatalogRepository.MarketplaceSkuRef>> skus) {}
    public Snapshot page(UUID tenant,String search,int page,int size){
        String text=search==null?"":search.trim();
        if(text.length()>160)throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_REQUEST,"Search must be 160 characters or fewer.");
        Query key=new Query(text,Math.max(0,page),TablePaging.size(size));
        return cache.get(tenant,"catalogue",key,()->{
            var result=catalog.pageAccountItems(tenant,text,key.page,key.size);
            var ids=result.rows().stream().map(CatalogRepository.AccountItemView::id).toList();
            return new Snapshot(result,List.copyOf(catalog.listVendors(tenant)),List.copyOf(catalog.listLocations(tenant)),
                group(catalog.listVendorOffers(tenant,ids),CatalogRepository.VendorOfferView::itemId),
                group(catalog.listMarketplaceSkus(tenant,ids),CatalogRepository.MarketplaceSkuRef::itemId));
        });
    }
    private static <T> Map<UUID,List<T>> group(List<T> rows,Function<T,UUID> key){
        return rows.stream().collect(Collectors.groupingBy(key,Collectors.collectingAndThen(Collectors.toList(),List::copyOf)))
            .entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,Map.Entry::getValue));
    }
}
