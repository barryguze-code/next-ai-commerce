package com.nextaicommerce.platform.catalog;
import java.math.BigDecimal;import java.util.UUID;import org.junit.jupiter.api.Test;import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
class CatalogProductDetailsTest {
 @Test void newProductUsesExistingSizeUnitColumnsWithoutAnUpdateOfExistingProducts(){
  var jdbc=mock(JdbcTemplate.class);var repo=new CatalogRepository(jdbc);UUID tenant=UUID.randomUUID(),actor=UUID.randomUUID(),product=UUID.randomUUID();when(jdbc.queryForObject(anyString(),eq(UUID.class),any(Object[].class))).thenReturn(product);
  assertThat(repo.insertGlobalProduct(tenant,actor," New product ","Brand",true," 12 oz "," ea ")).isEqualTo(product);
  verify(jdbc).queryForObject(contains("package_size,unit_of_measure) VALUES"),eq(UUID.class),eq("New product"),eq("Brand"),eq(true),eq(tenant),eq(actor),eq("12 oz"),eq("EA"));verify(jdbc,never()).update(anyString(),any(Object[].class));
 }
 @Test void detailValidationUsesSchemaLengthsAndSafeDefaults(){assertThat(CatalogRepository.newProductUnit(null)).isEqualTo("EA");assertThat(CatalogRepository.newProductSize(" ")).isNull();assertThatThrownBy(()->CatalogRepository.newProductSize("x".repeat(81))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->CatalogRepository.newProductUnit("x".repeat(31))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->CatalogRepository.newProductUnit("<script>")).isInstanceOf(IllegalArgumentException.class);}
 @Test void percentagesAreDisplayOnlyWithAtMostTwoDecimals(){assertThat(CatalogRepository.percentage(new BigDecimal("18.0000"))).isEqualTo("18%");assertThat(CatalogRepository.percentage(new BigDecimal("0.0000"))).isEqualTo("0%");assertThat(CatalogRepository.percentage(new BigDecimal("18.2500"))).isEqualTo("18.25%");BigDecimal stored=new BigDecimal("18.1254");assertThat(CatalogRepository.percentage(stored)).isEqualTo("18.13%");assertThat(stored.toPlainString()).isEqualTo("18.1254");}
}
