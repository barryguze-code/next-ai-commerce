package com.nextaicommerce.platform.shipping;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Deterministic, reviewable rate recommendation rules. This class never purchases a label. */
public final class ColdChainShippingPolicy {
    private ColdChainShippingPolicy(){}
    public record Policy(int handlingDays,int targetTransitDays,LocalTime cutoff,List<String> blockedServiceTerms,
            BigDecimal upsGroundPremiumLimit,boolean weekendHold,boolean paidOrExpeditedFridayHandoff){}
    public record Schedule(LocalDate handoffDate,boolean expedited,boolean extraIce,String note){}
    public record Choice(AmazonMerchantFulfillmentClient.Rate offer,String reason){}

    public static Schedule schedule(Policy policy,ZoneId zone,String temperatureClass,String serviceLevel,
            BigDecimal customerShipping,Instant now){
        ZonedDateTime local=now.atZone(zone);boolean cold=!"AMBIENT".equalsIgnoreCase(temperatureClass);
        boolean expedited=isExpedited(serviceLevel),paid=customerShipping!=null&&customerShipping.signum()>0;
        LocalDate handoff=local.toLocalDate();
        if(cold&&policy.weekendHold()&&(local.getDayOfWeek()==DayOfWeek.THURSDAY||local.getDayOfWeek()==DayOfWeek.FRIDAY)){
            boolean friday=policy.paidOrExpeditedFridayHandoff()&&(paid||expedited);
            if(friday&&local.getDayOfWeek()==DayOfWeek.THURSDAY)handoff=handoff.plusDays(1);
            else if(friday&&local.toLocalTime().isBefore(policy.cutoff()))handoff=local.toLocalDate();
            else handoff=nextMonday(local.toLocalDate());
        }else if(!local.toLocalTime().isBefore(policy.cutoff()))handoff=nextBusinessDay(handoff);
        for(int i=0;i<policy.handlingDays();i++)handoff=nextBusinessDay(handoff);
        boolean extraIce=cold&&(paid||expedited)&&handoff.getDayOfWeek()==DayOfWeek.FRIDAY;
        String note=handoff.getDayOfWeek()==DayOfWeek.MONDAY&&cold
            ?"Weekend cold-chain hold · hand to carrier Monday"
            :extraIce?"Friday handoff · customer-paid or expedited · add extra ice"
            :"Hand to carrier "+handoff.getDayOfWeek().toString().toLowerCase(Locale.US);
        return new Schedule(handoff,expedited,extraIce,note);
    }

    public static Choice choose(Policy policy,ZoneId zone,String temperatureClass,LocalDate handoff,
            List<AmazonMerchantFulfillmentClient.Rate> offers){
        boolean cold=!"AMBIENT".equalsIgnoreCase(temperatureClass);
        List<AmazonMerchantFulfillmentClient.Rate> safe=offers.stream().filter(rate->!rate.requiresSellerInput())
            .filter(rate->rate.labelFormats().stream().anyMatch("PDF"::equalsIgnoreCase))
            .filter(rate->!blocked(rate,policy.blockedServiceTerms()))
            .filter(rate->!cold||withinTransitTarget(rate,handoff,zone,policy.targetTransitDays())).toList();
        if(safe.isEmpty())return new Choice(null,cold
            ?"No PDF service met the cold-chain transit rule after blocked services were removed. Review this order individually."
            :"No directly purchasable PDF service remained after blocked services were removed.");
        var cheapest=safe.stream().min(Comparator.comparing(AmazonMerchantFulfillmentClient.Rate::amount)
            .thenComparing(rate->rate.latestDelivery(),Comparator.nullsLast(Comparator.naturalOrder()))).orElseThrow();
        var ground=safe.stream().filter(ColdChainShippingPolicy::upsGround)
            .min(Comparator.comparing(AmazonMerchantFulfillmentClient.Rate::amount)).orElse(null);
        if(ground!=null&&ground.amount().compareTo(cheapest.amount().add(policy.upsGroundPremiumLimit()))<=0)
            return new Choice(ground,ground==cheapest?"UPS Ground is the cheapest qualifying service."
                :"UPS Ground selected for service quality within the configured "+money(policy.upsGroundPremiumLimit())+" premium.");
        return new Choice(cheapest,"Cheapest qualifying service within the delivery and cold-chain rules.");
    }

    public static boolean isExpedited(String value){
        String normalized=value==null?"":value.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]","");
        return normalized.contains("EXPEDIT")||normalized.contains("PRIORITY")||normalized.contains("NEXTDAY")
            ||normalized.contains("ONEDAY")||normalized.contains("1DAY")||normalized.contains("TWODAY")
            ||normalized.contains("2DAY")||normalized.contains("SECOND DAY".replace(" ",""));
    }
    static boolean withinTransitTarget(AmazonMerchantFulfillmentClient.Rate rate,LocalDate handoff,ZoneId zone,int days){
        Instant delivered=rate.latestDelivery()!=null?rate.latestDelivery():rate.earliestDelivery();if(delivered==null)return false;
        LocalDate delivery=delivered.atZone(zone).toLocalDate();return businessDaysAfter(handoff,delivery)<=days;
    }
    private static int businessDaysAfter(LocalDate from,LocalDate to){
        if(to.isBefore(from))return 0;int days=0;for(LocalDate day=from.plusDays(1);!day.isAfter(to);day=day.plusDays(1))
            if(day.getDayOfWeek()!=DayOfWeek.SATURDAY&&day.getDayOfWeek()!=DayOfWeek.SUNDAY)days++;return days;
    }
    private static boolean blocked(AmazonMerchantFulfillmentClient.Rate rate,List<String> terms){
        String service=((rate.carrierName()==null?"":rate.carrierName())+" "+(rate.serviceName()==null?"":rate.serviceName())).toUpperCase(Locale.US);
        return terms!=null&&terms.stream().filter(term->term!=null&&!term.isBlank()).map(term->term.toUpperCase(Locale.US)).anyMatch(service::contains);
    }
    private static boolean upsGround(AmazonMerchantFulfillmentClient.Rate rate){
        String value=((rate.carrierName()==null?"":rate.carrierName())+" "+(rate.serviceName()==null?"":rate.serviceName())).toUpperCase(Locale.US);
        return value.contains("UPS")&&value.contains("GROUND")&&!value.contains("SUREPOST");
    }
    private static LocalDate nextBusinessDay(LocalDate value){do{value=value.plusDays(1);}while(value.getDayOfWeek()==DayOfWeek.SATURDAY||value.getDayOfWeek()==DayOfWeek.SUNDAY);return value;}
    private static LocalDate nextMonday(LocalDate value){while(value.getDayOfWeek()!=DayOfWeek.MONDAY)value=value.plusDays(1);return value;}
    private static String money(BigDecimal value){return "$"+value.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString();}
}
