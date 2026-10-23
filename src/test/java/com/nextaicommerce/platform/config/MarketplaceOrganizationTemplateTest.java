package com.nextaicommerce.platform.config;
import static org.assertj.core.api.Assertions.*;
import java.util.*;import java.nio.file.*;import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import com.nextaicommerce.platform.marketplace.*;

class MarketplaceOrganizationTemplateTest {
 @Test void contextHeadingsRepeatOnlyAtVisibleRunBoundariesAndNeverContainOfferMetrics()throws Exception{
  var app=JakartaServletWebApplication.buildApplication(new MockServletContext());var c=new WebContext(app.buildExchange(new MockHttpServletRequest(),new MockHttpServletResponse()));
  var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCacheable(false);var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);
  var a=MarketplaceSafetyTemplateTest.row("000CHILD-A","ACTIVE");var b=MarketplaceSafetyTemplateTest.row("000CHILD-B","ACTIVE");var solo=MarketplaceSafetyTemplateTest.row("STANDALONE","ACTIVE");var d=MarketplaceSafetyTemplateTest.row("000CHILD-C","ACTIVE");
  var parent=new MarketplaceSkuRelationships.Parent("000PARENT-WITH-LONG-EXACT-IDENTITY-US-FBA","B0PARENT01",3);var contexts=Map.of(a.sellerSku(),parent,b.sellerSku(),parent,d.sellerSku(),parent);
  c.setVariables(Map.ofEntries(Map.entry("query",""),Map.entry("selectedStatus","ALL"),Map.entry("selectedSort","status"),Map.entry("sortDirection","asc"),Map.entry("selectedCondition","NEW"),Map.entry("selectedFulfillment",""),Map.entry("conditionOptions",Map.of("NEW","New","USED_GOOD","Used · Good","UNKNOWN","Unknown / other")),Map.entry("summary",new MarketplaceSkuRepository.SkuSummary(5,4,0,0,0,0,0,10)),Map.entry("skuPage",new MarketplaceSkuRepository.SkuPage(List.of(a,b,solo,d),4,0,25)),Map.entry("inventoryDecisions",Map.of()),Map.entry("parentContexts",contexts)));
  c.setVariable("selectedAccountName","KARACA");c.setVariable("selectedAccountInitials","K");
  c.setVariable("selectedStore",new com.nextaicommerce.platform.web.WorkspaceAccessRepository.ConnectionView(UUID.randomUUID(),UUID.randomUUID(),"KARACA","KARACA","AMAZON","fixture","ATVPDKIKX0DER","Amazon US","US","ACTIVE",Instant.parse("2026-10-22T15:56:00Z"),true));
  c.setVariable("listingSync",new com.nextaicommerce.platform.sync.AmazonManualListingSync.Availability(false,"Amazon SKU sync is queued or running. Refresh to see updated listings.",true,Instant.parse("2026-10-20T21:11:00Z")));
  String html=engine.process("marketplace-skus",c);
  assertThat(html.split("data-family-heading",-1)).hasSize(3);assertThat(html).contains("Parent SKU","Parent ASIN","Condition","All conditions","Store-wide counts");
  assertThat(html).doesNotContain("data-sku=\"000PARENT","Associated child variations","sku-parent-label");
  for(String fragment:html.split("<tr class=\"sku-family-heading\"")){if(!fragment.startsWith(" data-family-heading"))continue;assertThat(fragment.substring(0,fragment.indexOf("</tr>"))).doesNotContain("button","price","profit","sales","Available");}
  Path out=Path.of("/tmp/marketplace-organization-review");Files.createDirectories(out);Files.writeString(out.resolve("marketplace.html"),html);
  c.setVariable("skuPage",new MarketplaceSkuRepository.SkuPage(List.of(),0,0,25));c.setVariable("emptyParentContext",parent);c.setVariable("query",parent.sku());
  String empty=engine.process("marketplace-skus",c);Files.writeString(out.resolve("empty-parent.html"),empty);assertThat(empty).contains("No variations match the current filters.","3 variations","Adjust the current filters to view matching variations.").doesNotContain("data-sku=", "data-family-heading","Amazon has not supplied listings for this filter yet.","Try a different SKU, ASIN, title, or status.");
  assertThat(empty.indexOf("sku-empty-parent-context")).isLessThan(empty.indexOf("<table id=\"marketplace-listings-table\""));
  c.setVariable("emptyParentContext",null);c.setVariable("skuPage",new MarketplaceSkuRepository.SkuPage(List.of(a,b,solo,d),4,0,25));
  c.setVariable("parentContexts",Map.of());String expired=engine.process("marketplace-skus",c);assertThat(expired).doesNotContain("data-family-heading");Files.writeString(out.resolve("expired.html"),expired);
 }
}
