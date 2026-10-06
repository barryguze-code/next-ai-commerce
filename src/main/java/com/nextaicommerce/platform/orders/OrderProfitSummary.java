package com.nextaicommerce.platform.orders;

import com.nextaicommerce.platform.profit.ProfitRepository;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Separate from page rendering; batches the existing estimate engine, never calls Amazon. */
@Service
public class OrderProfitSummary {
    private final OrderRepository orders;
    private final ProfitRepository profit;
    private final SkuRefundHistory refunds;
    private record Key(UUID tenant,UUID store,String status,String query,Map<String,String> filters){}
    private record Cached(long expires,Result result){}
    public record Total(BigDecimal amount,int estimated,int pending){}
    public record Result(Total today,Total filtered,Total thirtyDays,List<SkuRefundHistory.RefundTotal> refundsThirtyDays,OrderRepository.FilteredSummary volumeThirtyDays){}
    private final ConcurrentHashMap<Key,Cached> cache=new ConcurrentHashMap<>();
    public OrderProfitSummary(OrderRepository orders,ProfitRepository profit,SkuRefundHistory refunds){this.orders=orders;this.profit=profit;this.refunds=refunds;}
    public Result get(UUID tenant,UUID store,String status,String query,Map<String,String> filters){
        var normalized=new HashMap<>(filters);normalized.remove("smartSort");normalized.remove("q");normalized.remove("status");
        var key=new Key(tenant,store,status,query,Map.copyOf(normalized));
        if(cache.size()>128)cache.entrySet().removeIf(e->e.getValue().expires<System.currentTimeMillis());
        if(cache.size()>128)cache.clear();
        return cache.compute(key,(k,old)->{
            long now=System.currentTimeMillis();if(old!=null&&old.expires>now)return old;
            var today=orders.profitOrderKeys(tenant,store,"ALL","",Map.of("f_date_preset","today"));
            var filtered=orders.profitOrderKeys(tenant,store,status,query,normalized);
            var cutoff=java.time.Instant.now().minus(java.time.Duration.ofDays(30));
            var thirtyDays=orders.profitOrderKeys(tenant,store,"ALL","",Map.of("f_date_min",cutoff.toString()));
            var unique=new LinkedHashSet<>(today);unique.addAll(filtered);unique.addAll(thirtyDays);var keys=new ArrayList<>(unique);
            Map<String,ProfitRepository.View> views=new HashMap<>();
            for(int start=0;start<keys.size();start+=100)views.putAll(profit.orders(tenant,store,keys.subList(start,Math.min(start+100,keys.size()))));
            return new Cached(System.currentTimeMillis()+60000,new Result(total(today,views),total(filtered,views),total(thirtyDays,views),refunds.thirtyDays(tenant,store),orders.filteredSummary(tenant,store,"ALL","",Map.of("f_date_min",cutoff.toString()))));
        }).result;
    }
    static Total total(List<String> keys,Map<String,ProfitRepository.View> views){
        BigDecimal amount=BigDecimal.ZERO;int estimated=0,pending=0;
        for(var key:keys){var view=views.get(key);if(view==null||view.totals()==null||view.totals().profit()==null||!"USD".equals(view.currency())){pending++;continue;}
            amount=amount.add(view.totals().profit());estimated++;
        }
        return new Total(amount,estimated,pending);
    }
}
