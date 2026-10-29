package com.nextaicommerce.platform.sync;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Durable position in the existing job cursor; source pages and this position commit together. */
public record AmazonOrderDeltaProgress(int revision, boolean ordersComplete, String nextOrderToken,
        int orderPages, List<String> orderIds, int itemIndex, String nextItemToken,
        int itemPages, long records, Instant nextItemAt) {
    public AmazonOrderDeltaProgress {
        orderIds=List.copyOf(orderIds);
        if(revision!=1||orderPages<0||orderPages>100||itemIndex<0||itemIndex>orderIds.size()
                ||itemPages<0||itemPages>100||records<0)throw new IllegalArgumentException("Invalid saved Amazon order position.");
    }
    public static AmazonOrderDeltaProgress empty(){return new AmazonOrderDeltaProgress(1,false,null,0,List.of(),0,null,0,0,null);}
    public AmazonOrderDeltaProgress orders(List<String> ids,String next,long count){
        var all=new ArrayList<>(orderIds);for(String id:ids)if(id!=null&&!id.isBlank()&&!all.contains(id))all.add(id);
        String token=blank(next);int pages=orderPages+1;
        if(pages>100||(pages==100&&token!=null)||token!=null&&token.equals(nextOrderToken))
            throw new IllegalArgumentException("Amazon order pagination exceeded its bound or repeated a cursor.");
        return new AmazonOrderDeltaProgress(1,token==null,token,pages,all,itemIndex,nextItemToken,itemPages,records+count,nextItemAt);
    }
    public AmazonOrderDeltaProgress items(String next,long count,Instant notBefore){
        String token=blank(next);int pages=itemPages+1;
        if(pages>100||(pages==100&&token!=null)||token!=null&&token.equals(nextItemToken))
            throw new IllegalArgumentException("Amazon item pagination exceeded its bound or repeated a cursor.");
        return new AmazonOrderDeltaProgress(1,true,nextOrderToken,orderPages,orderIds,
            itemIndex+(token==null?1:0),token,token==null?0:pages,records+count,notBefore);
    }
    private static String blank(String value){return value==null||value.isBlank()?null:value;}
}
