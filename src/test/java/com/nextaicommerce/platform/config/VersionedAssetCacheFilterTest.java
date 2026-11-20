package com.nextaicommerce.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;

class VersionedAssetCacheFilterTest {
    @Test void cachesOnlyVersionedStaticAssets() throws Exception {
        for(String path:new String[]{"/js/table-widget.js","/app/orders","/images/platform/table/actions-ai-human.png"}) {
            var request=new MockHttpServletRequest("GET",path);request.setParameter("v","20260919-17");
            var response=new MockHttpServletResponse();
            new VersionedAssetCacheFilter().doFilter(request,response,(req,res)->{});
            if(path.startsWith("/app/"))assertThat(response.getHeader("Cache-Control")).isNull();
            else assertThat(response.getHeader("Cache-Control")).contains("immutable");
        }
        var response=new MockHttpServletResponse();
        new VersionedAssetCacheFilter().doFilter(new MockHttpServletRequest("GET","/js/table-widget.js"),response,(req,res)->{});
        assertThat(response.getHeader("Cache-Control")).isNull();
    }
}
