package com.nextaicommerce.platform.marketplace;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class MappingWorkspaceGuardTest {
 @Test void inlineSaveRequiresExactOriginatingAccountAndStore(){UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();var origin=new HashMap<String,String>();origin.put("_mappingInline","true");assertThatThrownBy(()->MarketplaceSkuController.verifyMappingWorkspace(origin,tenant,store)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);origin.put("originAccount",tenant.toString());origin.put("originStore",store.toString());assertThatCode(()->MarketplaceSkuController.verifyMappingWorkspace(origin,tenant,store)).doesNotThrowAnyException();assertThatThrownBy(()->MarketplaceSkuController.verifyMappingWorkspace(origin,tenant,UUID.randomUUID())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);assertThatThrownBy(()->MarketplaceSkuController.verifyMappingWorkspace(origin,UUID.randomUUID(),store)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);}
}
