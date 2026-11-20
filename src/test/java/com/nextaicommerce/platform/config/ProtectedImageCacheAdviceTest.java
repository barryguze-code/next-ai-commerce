package com.nextaicommerce.platform.config;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.http.server.*;
import org.springframework.mock.web.*;
class ProtectedImageCacheAdviceTest {
 @Test void privateImagesRevalidateAndContentOrAccountChangesInvalidate(){
  var advice=new ProtectedImageCacheAdvice();var request=new MockHttpServletRequest("GET","/app/catalog/products/a/image");request.getSession().setAttribute("selectedTenantId","account-one");var response=new MockHttpServletResponse();var first=new ServletServerHttpResponse(response);
  byte[] bytes={1,2,3};assertThat(advice.beforeBodyWrite(bytes,null,MediaType.IMAGE_PNG,null,new ServletServerHttpRequest(request),first)).isEqualTo(bytes);String tag=first.getHeaders().getETag();assertThat(first.getHeaders().getCacheControl()).contains("private","no-cache");
  request.addHeader("If-None-Match",tag);response=new MockHttpServletResponse();assertThat(advice.beforeBodyWrite(bytes,null,MediaType.IMAGE_PNG,null,new ServletServerHttpRequest(request),new ServletServerHttpResponse(response))).isNull();assertThat(response.getStatus()).isEqualTo(304);
  var changed=new ServletServerHttpResponse(new MockHttpServletResponse());assertThat(advice.beforeBodyWrite(new byte[]{4},null,MediaType.IMAGE_PNG,null,new ServletServerHttpRequest(request),changed)).isNotNull();assertThat(changed.getHeaders().getETag()).isNotEqualTo(tag);
  request.getSession().setAttribute("selectedTenantId","account-two");changed=new ServletServerHttpResponse(new MockHttpServletResponse());assertThat(advice.beforeBodyWrite(bytes,null,MediaType.IMAGE_PNG,null,new ServletServerHttpRequest(request),changed)).isNotNull();assertThat(changed.getHeaders().getETag()).isNotEqualTo(tag);
  org.springframework.test.util.ReflectionTestUtils.setField(advice,"version","next-release");var release=new ServletServerHttpResponse(new MockHttpServletResponse());advice.beforeBodyWrite(bytes,null,MediaType.IMAGE_PNG,null,new ServletServerHttpRequest(request),release);assertThat(release.getHeaders().getETag()).isNotEqualTo(changed.getHeaders().getETag());
 }
 @Test void downloadsAndNonImagesStayOutsideCache(){var a=new ProtectedImageCacheAdvice();var r=new MockHttpServletRequest("GET","/app/export");var s=new ServletServerHttpResponse(new MockHttpServletResponse());assertThat(a.beforeBodyWrite(new byte[]{1},null,MediaType.APPLICATION_OCTET_STREAM,null,new ServletServerHttpRequest(r),s)).hasSize(1);assertThat(s.getHeaders().getETag()).isNull();}
}
