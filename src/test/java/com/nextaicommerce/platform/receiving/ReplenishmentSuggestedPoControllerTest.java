package com.nextaicommerce.platform.receiving;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
class ReplenishmentSuggestedPoControllerTest {
 Map<String,Object> item(String code,int cases,int demand){var r=new HashMap<String,Object>();r.put("id",UUID.randomUUID());r.put("name",code);r.put("code",code);r.put("vendor","Fixture");r.put("pack",12);r.put("cases",cases);r.put("demand",demand);r.put("suggested_each",cases*12);r.put("unit_cost",2);return r;}
 @Test void projectsCompletePositiveForecastWithoutSkuArraysOrPageFilter(){var rows=new ArrayList<Map<String,Object>>();for(int n=0;n<88;n++){var r=item("000"+n,2,10);r.put("skus",List.of(Map.of("sku","not-in-payload")));rows.add(r);}rows.add(item("healthy",0,10));rows.add(item("no-demand",2,0));var projected=ReplenishmentSuggestedPoController.items(rows);assertThat(projected).hasSize(88);assertThat(projected.getFirst().get("code")).isEqualTo("0000");assertThat(projected.getFirst().get("each")).isEqualTo(24L);assertThat(projected).allSatisfy(r->assertThat(r).doesNotContainKey("skus"));}
 @Test void accountAndPermissionChecksProtectCachedSnapshot(){var suggestions=mock(ReplenishmentSuggestions.class);var access=mock(WorkspaceAccessRepository.class);var c=new ReplenishmentSuggestedPoController(suggestions,access);UUID id=UUID.randomUUID();var session=new MockHttpSession();session.setAttribute("selectedTenantId",id);var auth=new UsernamePasswordAuthenticationToken("operator",null);
 assertThatThrownBy(()->c.suggested(session,auth,id)).hasMessageContaining("403");verifyNoInteractions(suggestions);when(access.canOperateAccount(id,"operator")).thenReturn(true);assertThatThrownBy(()->c.suggested(session,auth,UUID.randomUUID())).hasMessageContaining("409");verifyNoInteractions(suggestions);
 when(suggestions.snapshot(id)).thenReturn(new ReplenishmentSuggestions.Snapshot(List.of(item("000123",1,1)),Instant.now()));assertThat(c.suggested(session,auth,id).getStatusCode().value()).isEqualTo(200);verify(suggestions,times(2)).snapshot(id);verify(suggestions).pending(id);verifyNoMoreInteractions(suggestions);}
 @Test void oversizedForecastIsRejectedBeforeReplacingWorkingDraft(){var rows=new ArrayList<Map<String,Object>>();for(int n=0;n<501;n++)rows.add(item("item"+n,1,1));assertThatThrownBy(()->ReplenishmentSuggestedPoController.items(rows)).hasMessageContaining("500");}
 @Test void staleSnapshotCannotSeedBeforeRefreshCompletes(){var suggestions=mock(ReplenishmentSuggestions.class);var access=mock(WorkspaceAccessRepository.class);var c=new ReplenishmentSuggestedPoController(suggestions,access);UUID id=UUID.randomUUID();var session=new MockHttpSession();session.setAttribute("selectedTenantId",id);when(access.canOperateAccount(id,"operator")).thenReturn(true);when(suggestions.snapshot(id)).thenReturn(new ReplenishmentSuggestions.Snapshot(List.of(item("old",1,1)),Instant.EPOCH));when(suggestions.pending(id)).thenReturn(true);var response=c.suggested(session,new UsernamePasswordAuthenticationToken("operator",null),id);assertThat(((Map<?,?>)response.getBody()).get("ready")).isEqualTo(false);verify(suggestions).ensureFresh(id);}
}
