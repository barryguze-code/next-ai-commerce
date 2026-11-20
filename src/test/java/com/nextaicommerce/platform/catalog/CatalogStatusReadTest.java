package com.nextaicommerce.platform.catalog;

import com.nextaicommerce.platform.config.PlatformReadCache;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CatalogStatusReadTest {
    @Test void statusIsValidatedAndUnknownInputCannotBecomeSql(){
        for(String value:List.of("ACTIVE","INACTIVE","DISCONTINUED","ALL"))assertThat(CatalogRepository.catalogStatus(value)).isEqualTo(value);
        assertThat(CatalogRepository.catalogStatus(null)).isEqualTo("ACTIVE");assertThat(CatalogRepository.catalogStatus("ACTIVE' OR 1=1")).isEqualTo("ACTIVE");
    }
    @Test void statusIsAppliedToServerCountAndPageWithBoundParameters(){
        for(String search:List.of("","342934")){
            var jdbc=mock(JdbcTemplate.class);var tenant=UUID.randomUUID();
            when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
            new CatalogRepository(jdbc).pageAccountItems(tenant,search,0,25,"product","asc","INACTIVE");
            var parameters=ArgumentCaptor.forClass(Object[].class);
            verify(jdbc).queryForObject(contains("AND item.status=?"),eq(Long.class),parameters.capture());
            assertThat(parameters.getValue()).contains(tenant,"INACTIVE");
        }
    }
    @Test void activeAndArchiveQueriesDoNotShareCachedResults(){
        var repo=mock(CatalogRepository.class);var tenant=UUID.randomUUID();
        var active=new CatalogRepository.AccountItemPage(List.of(),1,0,25);var inactive=new CatalogRepository.AccountItemPage(List.of(),2,0,25);
        when(repo.pageAccountItems(tenant,"342934",0,25,"product","asc","ACTIVE")).thenReturn(active);
        when(repo.pageAccountItems(tenant,"342934",0,25,"product","asc","INACTIVE")).thenReturn(inactive);
        var reads=new CatalogReadService(repo,new PlatformReadCache(true,Duration.ofSeconds(10),256));
        assertThat(reads.page(tenant,"342934",0,25,"product","asc","ACTIVE").page()).isSameAs(active);
        assertThat(reads.page(tenant,"342934",0,25,"product","asc","INACTIVE").page()).isSameAs(inactive);
        assertThat(reads.page(tenant,"342934",0,25,"product","asc","ACTIVE").page()).isSameAs(active);
        verify(repo,times(1)).pageAccountItems(tenant,"342934",0,25,"product","asc","ACTIVE");
    }
}
