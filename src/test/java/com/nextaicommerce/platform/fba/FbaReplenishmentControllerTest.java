package com.nextaicommerce.platform.fba;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import com.nextaicommerce.platform.web.*;

class FbaReplenishmentControllerTest {
 final WorkspaceAccessRepository workspace=mock(WorkspaceAccessRepository.class);
 final FbaReplenishmentForecasts forecasts=mock(FbaReplenishmentForecasts.class);
 final FbaReplenishmentPlans plans=mock(FbaReplenishmentPlans.class);
 final FbaReplenishmentController controller=new FbaReplenishmentController(workspace,forecasts,plans,mock(FbaReplenishmentSettings.class));
 final UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();final MockHttpSession session=new MockHttpSession();
 @BeforeEach void setup(){ReflectionTestUtils.setField(controller,"enabled",true);session.setAttribute(AccountSelectionController.TENANT_ID,tenant);session.setAttribute(AccountSelectionController.STORE_ID,store);when(workspace.findConnection(tenant,store)).thenReturn(new WorkspaceAccessRepository.ConnectionIdentity(store,"AMAZON"));}
 @Test void featureOffCannotReadOrStartWork(){ReflectionTestUtils.setField(controller,"enabled",false);assertEquals(404,assertThrows(ResponseStatusException.class,()->controller.forecast(session)).getStatusCode().value());verifyNoInteractions(forecasts,plans);}
 @Test void missingStoreAndWrongChannelDoNotQueryPlanData(){session.removeAttribute(AccountSelectionController.STORE_ID);assertThrows(ResponseStatusException.class,()->controller.list(session));session.setAttribute(AccountSelectionController.STORE_ID,store);when(workspace.findConnection(tenant,store)).thenReturn(new WorkspaceAccessRepository.ConnectionIdentity(store,"SHOPIFY"));assertThrows(ResponseStatusException.class,()->controller.list(session));verifyNoInteractions(plans);}
 @Test void forecastsUseOnlySessionAccountAndStore(){controller.forecast(session);verify(workspace).findConnection(tenant,store);verify(forecasts).read(tenant,store);}
 @Test void savedPlanLookupRetainsSessionScope(){UUID id=UUID.randomUUID();controller.get(id,session);verify(plans).get(tenant,store,id);}
 @Test void operatorCannotReserveOrReleaseStock(){var auth=new TestingAuthenticationToken("operator","unused","ROLE_OPERATOR");assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.allocate(UUID.randomUUID(),null,session,auth)).getStatusCode().value());assertThrows(ResponseStatusException.class,()->controller.release(UUID.randomUUID(),null,session,auth));verifyNoInteractions(plans);}
 @Test void managerReservationRetainsScopeAndActor(){var auth=new TestingAuthenticationToken("owner@example.test","unused","ROLE_OWNER");UUID id=UUID.randomUUID();var command=new FbaReplenishmentPlans.Allocate(UUID.randomUUID(),1,List.of());controller.allocate(id,command,session,auth);verify(plans).allocate(tenant,store,id,"owner@example.test",command);}
}
