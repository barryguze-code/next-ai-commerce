package com.nextaicommerce.platform.orders;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderBuyBoxComparisonTest {
    private String compare(String sale,int quantity,String box,String currency){
        return new OrderRepository.OrderItemView(null,"SKU",null,null,quantity,0,null,null,null,null,null,
            sale==null?null:new BigDecimal(sale),null,"USD",box==null?null:new BigDecimal(box),currency,null).buyBoxComparison();
    }
    @Test void equalDisplayedPricesMatch(){assertEquals("buy-box-match",compare("29.67",1,"29.6700","USD"));}
    @Test void usesPerUnitPrice(){assertEquals("buy-box-match",compare("59.34",2,"29.67","USD"));}
    @Test void differentPricesDiffer(){assertEquals("buy-box-different",compare("30",1,"29.67","USD"));}
    @Test void unknownIsNotOwnership(){
        assertEquals("buy-box-unknown",compare(null,1,"29.67","USD"));
        assertEquals("buy-box-unknown",compare("29.67",1,"29.67","CAD"));
        assertEquals("buy-box-unknown",compare("29.67",0,"29.67","USD"));
    }
}
