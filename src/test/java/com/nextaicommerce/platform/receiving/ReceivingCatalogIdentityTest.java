package com.nextaicommerce.platform.receiving;

import com.nextaicommerce.platform.catalog.CatalogRepository;
import java.math.BigDecimal;
import java.util.*;
import java.lang.reflect.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ReceivingCatalogIdentityTest {
    final UUID tenant=UUID.randomUUID(),vendor=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();
    Object match(ReceivingRepository repo,String code)throws Throwable{
        var method=ReceivingRepository.class.getDeclaredMethod("matchOrCreateItem",UUID.class,UUID.class,String.class,String.class,String.class,String.class,String.class,BigDecimal.class,String.class,String.class,String.class);
        method.setAccessible(true);
        try{return method.invoke(repo,tenant,vendor,"fixture","Muesli","Ozery",code,"664164101982",BigDecimal.ONE,"USD","INVOICE","fixture");}catch(InvocationTargetException e){throw e.getCause();}
    }
    @Test void duplicateVendorBindingsCannotArbitrarilySelectAnInventoryIdentity()throws Throwable{
        var jdbc=mock(JdbcTemplate.class);var catalog=mock(CatalogRepository.class);
        // The old LIMIT 1 extractor returns one identity even when two exist.
        when(jdbc.query(anyString(),any(ResultSetExtractor.class),eq(tenant),eq(vendor),anyString())).thenReturn(a);
        when(jdbc.queryForList(anyString(),eq(UUID.class),eq(tenant),eq(vendor),anyString())).thenReturn(List.of(a,b));
        assertThatThrownBy(()->match(new ReceivingRepository(jdbc,catalog),"342934")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("multiple products");
        verifyNoInteractions(catalog);verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void aUniqueExistingVendorBindingRemainsAuthoritativeWithoutReparenting()throws Throwable{
        var jdbc=mock(JdbcTemplate.class);var catalog=mock(CatalogRepository.class);
        when(jdbc.queryForList(anyString(),eq(UUID.class),eq(tenant),eq(vendor),eq("342934"))).thenReturn(List.of(a));
        Object result=match(new ReceivingRepository(jdbc,catalog),"000342934");
        var field=result.getClass().getDeclaredMethod("itemId");field.setAccessible(true);assertThat(field.invoke(result)).isEqualTo(a);
        verify(jdbc).queryForList(contains("SELECT DISTINCT"),eq(UUID.class),eq(tenant),eq(vendor),eq("342934"));verifyNoInteractions(catalog);
    }
    @Test void sharedNormalizationPreservesAlphanumericLeadingZerosAndDoesNotRewriteSourceCodes(){
        assertThat(com.nextaicommerce.platform.catalog.CatalogIdentity.vendorCode("000342934")).isEqualTo("342934");
        assertThat(com.nextaicommerce.platform.catalog.CatalogIdentity.vendorCode("000AB-12")).isEqualTo("000AB12");
    }
}
