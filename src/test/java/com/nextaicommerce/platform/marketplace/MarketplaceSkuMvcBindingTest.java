package com.nextaicommerce.platform.marketplace;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.View;

/** Exercises packaged controller argument resolution, without repositories or database writes. */
class MarketplaceSkuMvcBindingTest {
 @Test void everyPackagedHandlerRetainsItsParameterNames(){
  for(Class<?> type:List.of(MarketplaceSkuController.class,MarketplaceSkuCsvController.class))
   for(Method method:type.getDeclaredMethods())if(method.isAnnotationPresent(GetMapping.class)||method.isAnnotationPresent(PostMapping.class))
    for(Parameter parameter:method.getParameters())assertThat(parameter.isNamePresent()).as(type.getSimpleName()+"."+method.getName()+":"+parameter.getType()).isTrue();
 }
 @Test void defaultAndExactParentSearchBindThroughRealMvcResolvers()throws Exception{
  var controller=mock(MarketplaceSkuController.class);var mvc=MockMvcBuilders.standaloneSetup(controller).setViewResolvers((name,locale)->mock(View.class)).build();
  mvc.perform(MockMvcRequestBuilders.get("/app/marketplace-skus")).andReturn();
  verify(controller).marketplaceSkus(eq(""),eq("ALL"),eq("status"),eq("asc"),eq(0),isNull(),eq(25),anyMap(),isNull(),any(),any());
  mvc.perform(MockMvcRequestBuilders.get("/app/marketplace-skus").param("q","4D-2PHN-6KX3").param("f_condition","NEW").param("f_channel","FBA").param("sort","price").param("direction","desc").param("page","2")).andReturn();
  verify(controller).marketplaceSkus(eq("4D-2PHN-6KX3"),eq("ALL"),eq("price"),eq("desc"),eq(2),isNull(),eq(25),argThat(p->"NEW".equals(p.get("f_condition"))&&"FBA".equals(p.get("f_channel"))),isNull(),any(),any());
 }
 @Test void everyExistingHandlerResolvesRequiredScalarParametersWithoutBusinessCalls()throws Exception{
  var controller=mock(MarketplaceSkuController.class);var mvc=MockMvcBuilders.standaloneSetup(controller).setViewResolvers((name,locale)->mock(View.class)).build();
  for(Method method:MarketplaceSkuController.class.getDeclaredMethods()){
   var get=method.getAnnotation(GetMapping.class);var post=method.getAnnotation(PostMapping.class);if(get==null&&post==null)continue;
   String route=(get!=null?get.value():post.value())[0];var request=get!=null?MockMvcRequestBuilders.get(route):MockMvcRequestBuilders.post(route);
   for(Parameter parameter:method.getParameters()){
    var param=parameter.getAnnotation(RequestParam.class);if(param==null||Map.class.isAssignableFrom(parameter.getType()))continue;
    String name=!param.name().isEmpty()?param.name():!param.value().isEmpty()?param.value():parameter.getName();
    if(param.required()&&param.defaultValue().equals(ValueConstants.DEFAULT_NONE))request.param(name,name.equals("productId")?UUID.randomUUID().toString():name.equals("quantity")?"1":"00001");
   }
   var result=mvc.perform(request).andReturn();assertThat(result.getResolvedException()).as(route).isNull();assertThat(result.getResponse().getStatus()).as(route).isLessThan(400);
  }
 }
 @Test void csvRouteUsesExistingMapContractThroughMvc()throws Exception{
  var controller=mock(MarketplaceSkuCsvController.class);var mvc=MockMvcBuilders.standaloneSetup(controller).build();
  var result=mvc.perform(MockMvcRequestBuilders.get("/app/marketplace-skus/export.csv").param("q","00001").param("f_condition","ALL").param("columns","product-details")).andReturn();
  assertThat(result.getResolvedException()).isNull();verify(controller).export(argThat(p->"00001".equals(p.get("q"))&&"ALL".equals(p.get("f_condition"))),any(),isNull());
 }
}
