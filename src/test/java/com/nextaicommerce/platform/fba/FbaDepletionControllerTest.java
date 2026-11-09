package com.nextaicommerce.platform.fba;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import com.nextaicommerce.platform.web.*;
class FbaDepletionControllerTest {
 @Test void manualDepletionDefaultsOff(){var operations=mock(FbaDepletionRepository.class);var c=new FbaShipmentsController(mock(FbaReadRepository.class),mock(WorkspaceAccessRepository.class));ReflectionTestUtils.setField(c,"depletion",operations);assertThatThrownBy(()->c.preview("SHIP",UUID.randomUUID(),new MockHttpSession())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);verifyNoInteractions(operations);}
 @Test void changedStoreCannotReceiveAnOldDialogConfirmation(){var operations=mock(FbaDepletionRepository.class);var c=new FbaShipmentsController(mock(FbaReadRepository.class),mock(WorkspaceAccessRepository.class));ReflectionTestUtils.setField(c,"depletion",operations);ReflectionTestUtils.setField(c,"manualEnabled",true);var session=new MockHttpSession();session.setAttribute(AccountSelectionController.TENANT_ID,UUID.randomUUID());session.setAttribute(AccountSelectionController.STORE_ID,UUID.randomUUID());assertThatThrownBy(()->c.preview("SHIP",UUID.randomUUID(),session)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);verifyNoInteractions(operations);}
}
