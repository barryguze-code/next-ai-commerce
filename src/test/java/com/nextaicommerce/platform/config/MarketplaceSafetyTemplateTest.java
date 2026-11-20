package com.nextaicommerce.platform.config;
import static org.assertj.core.api.Assertions.*;
import java.util.*;import java.time.*;import java.math.BigDecimal;import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import com.nextaicommerce.platform.marketplace.*;
import com.nextaicommerce.platform.fba.FbaReadRepository;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository.ConnectionView;
class MarketplaceSafetyTemplateTest {
 static MarketplaceSkuRepository.SkuView row(String sku,String status){
  var values=new HashMap<String,Object>();values.put("sellerSku",sku);values.put("asin","B0SYNTHETIC");values.put("title","Synthetic KARACA long product title with complete identifiers and multi-component mapping for responsive verification");values.put("listingStatus",status);values.put("fbaAvailable",sku.contains("ZERO")?0:23);values.put("calculatedAvailable",new BigDecimal(sku.contains("ZERO")?"0":"23"));values.put("inboundQuantity",160);values.put("fulfillmentChannel","AMAZON");values.put("marketplaceId","US");values.put("price",new BigDecimal("89.99"));values.put("currency","USD");values.put("conditionType","New");values.put("fnsku","FN-"+sku);values.put("shippingTemplate","Synthetic shipping template");values.put("mappedProduct","Synthetic product");values.put("mappingStatus","ACTIVE");values.put("mappingQuantity",BigDecimal.ONE);
  try{var fields=MarketplaceSkuRepository.SkuView.class.getRecordComponents();var types=Arrays.stream(fields).map(f->f.getType()).toArray(Class<?>[]::new);Object[] args=new Object[fields.length];for(int i=0;i<fields.length;i++)args[i]=values.getOrDefault(fields[i].getName(),fields[i].getType()==long.class?0L:null);return MarketplaceSkuRepository.SkuView.class.getDeclaredConstructor(types).newInstance(args);}catch(Exception e){throw new AssertionError(e);}
 }
 @Test void verifiedParentHasNoMappingOrOfferControlsAndUnknownNeverBecomesParent()throws Exception{
  var context=JakartaServletWebApplication.buildApplication(new MockServletContext());var c=new WebContext(context.buildExchange(new MockHttpServletRequest(),new MockHttpServletResponse()));
  var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setCacheable(false);var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);
  var parent=row("PARENT","PARENT");var child=row("000001-CHILD-WITH-LONG-EXACT-IDENTIFIER-US-FBA","ACTIVE");var zero=row("000002-ZERO","ACTIVE");var unknown=row("UNKNOWN","UNVERIFIED");var conflict=row("CONFLICT","UNVERIFIED");var expired=row("EXPIRED","UNVERIFIED");var rows=List.of(parent,child,zero,unknown,conflict,expired);
  var decisions=new HashMap<String,MarketplaceInventoryEligibility.Decision>();
  for(var r:rows){boolean p=r==parent,eligible=r==child||r==zero;decisions.put(r.sellerSku(),new MarketplaceInventoryEligibility.Decision(r.sellerSku(),"US",p?"PARENT":eligible?"ELIGIBLE":r==conflict?"CONFLICT":"UNRESOLVED",eligible,p?"PARENT_INVENTORY_IDENTITY":r==expired?"STALE_EVIDENCE":"VERIFIED",p?AmazonVariationRelationshipProvider.State.PARENT:eligible?AmazonVariationRelationshipProvider.State.CHILD:r==conflict?AmazonVariationRelationshipProvider.State.CONFLICT:AmazonVariationRelationshipProvider.State.UNKNOWN));}
  c.setVariables(Map.ofEntries(Map.entry("canViewOperations",true),Map.entry("canManageConnections",true),Map.entry("canEditCatalog",false),Map.entry("isSuperAdmin",false),Map.entry("canManageUsers",false),Map.entry("selectedAccountName","Synthetic account"),Map.entry("signedInEmail","fixture@example.invalid"),Map.entry("roleLabel","Viewer"),Map.entry("selectedStoreName","Synthetic KARACA"),Map.entry("query",""),Map.entry("selectedStatus","ALL"),Map.entry("selectedSort","status"),Map.entry("sortDirection","asc"),Map.entry("selectedStore",new ConnectionView(UUID.randomUUID(),UUID.randomUUID(),"Synthetic account","Synthetic KARACA","AMAZON","fixture","US","Amazon US","US","ACTIVE",Instant.now(),true)),Map.entry("summary",new MarketplaceSkuRepository.SkuSummary(6,2,0,0,0,0,0,23,0,3,1)),Map.entry("insights",new MarketplaceSkuRepository.SkuInsights(2,0,0,0,0,0)),Map.entry("skuPage",new MarketplaceSkuRepository.SkuPage(rows,6,0,25)),Map.entry("inventoryDecisions",decisions),Map.entry("familyRows",Map.of("PARENT",List.of(child,zero)))));
  var stocks=new HashMap<String,FbaReadRepository.Stock>();for(var r:rows)stocks.put(r.sellerSku(),new FbaReadRepository.Stock(r.sellerSku(),r==zero?0:23,0,160,0,0,0,Instant.now(),BigDecimal.TEN,null,null,null,0));c.setVariable("fbaStock",stocks);
  var mapping=new HashMap<String,List<MarketplaceSkuRepository.MappingComponentView>>();for(var r:rows)mapping.put(r.sellerSku(),List.of(new MarketplaceSkuRepository.MappingComponentView(UUID.randomUUID(),"Synthetic one","00001001",null,BigDecimal.ONE),new MarketplaceSkuRepository.MappingComponentView(UUID.randomUUID(),"Synthetic two","00001002",null,new BigDecimal("2"))));c.setVariable("mappingComponents",mapping);
  String html=engine.process("marketplace-skus",c);assertThat(html).contains("Variation family · not a sellable offer","Associated child variations","Relationship unverified","Relationship conflict","Relationship evidence expired","Incomplete — 3 listings unverified");assertThat(html).doesNotContain("mapping-dialog-main-0","data-profit-key=\"PARENT\"");
  Path target=Path.of(System.getProperty("marketplacePreviewDir","/tmp/sku008-010-review")).toAbsolutePath();
  assertThat(target.toString()).startsWith("/tmp/");Files.createDirectories(target);Files.writeString(target.resolve("marketplace.html"),html);
 }
}
