package com.nextaicommerce.platform.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.util.*;
import java.sql.Timestamp;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository;

class PersonalCollaborationPresentationTest {
    private static String authName(){return "fixture@example.test";}
    @Test void convertedTasksRetainTheirAuthorizedSourceReviewAndArtworkInEveryTab() throws Exception {
        var queue=mock(PersonalCollaborationQueue.class);var reviews=mock(CollaborationRepository.class);
        var access=mock(WorkspaceAccessRepository.class);
        var controller=new PersonalCollaborationController(queue,mock(PersonalCollaborationActions.class),reviews,access);
        UUID account=UUID.randomUUID(),task=UUID.randomUUID(),reviewId=UUID.randomUUID();
        var review=new CollaborationRepository.Review(reviewId,"CATALOG","product","Product",null,"CONVERSATION","Task","ACTIVE",authName(),null,null,null,java.time.Instant.now(),java.time.Instant.now(),null,2,0,"Fixture","{}","/app/catalog",false);
        var auth=new UsernamePasswordAuthenticationToken("fixture@example.test","unused",List.of());
        when(reviews.review(account,reviewId,auth.getName())).thenReturn(review);
        when(reviews.pictures(eq(account),anyList(),eq(auth.getName()))).thenReturn(Map.of(reviewId,"https://example.test/product.png"));
        var session=new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(com.nextaicommerce.platform.web.AccountSelectionController.TENANT_ID,UUID.randomUUID());
        session.setAttribute("unrelated","preserved");
        var key=new PersonalCollaborationQueue.Key("REVIEW",reviewId);
        when(queue.authorized(auth.getName(),false,key)).thenReturn(new PersonalCollaborationQueue.Card(key,"Task",account,"Account",null,"ACTIVE",null,"CATALOG",null,null,new Timestamp(0)));
        when(access.listAccountOptions(auth.getName(),false)).thenReturn(List.of(new WorkspaceAccessRepository.AccountOption(account,"Account","A",null,List.of())));
        assertThatThrownBy(()->controller.source(reviewId,"https://example.test/",auth,session)).isInstanceOf(IllegalArgumentException.class);
        assertThat(controller.source(reviewId,"/app/catalog?q=product",auth,session)).isEqualTo("redirect:/app/catalog?q=product");
        assertThat(session.getAttribute(com.nextaicommerce.platform.web.AccountSelectionController.TENANT_ID)).isEqualTo(account);
        assertThat(session.getAttribute("unrelated")).isEqualTo("preserved");
        UUID shipment=UUID.randomUUID();
        var shipmentReview=new CollaborationRepository.Review(reviewId,"SHIPMENT",shipment.toString(),"Invoice",null,"CONVERSATION","Task","ACTIVE",authName(),null,null,null,java.time.Instant.now(),java.time.Instant.now(),null,2,0,"Fixture","{}","/app/receiving?q="+shipment,false);
        when(reviews.review(account,reviewId,auth.getName())).thenReturn(shipmentReview);
        assertThat(controller.source(reviewId,"/app/receiving?q="+shipment,auth,session)).isEqualTo("redirect:/app/receiving/"+shipment);
        when(reviews.review(account,reviewId,auth.getName())).thenReturn(review);
        for(String scope:List.of("ASSIGNED","CLOSED","ACTIVE")){
            var card=new PersonalCollaborationQueue.Card(new PersonalCollaborationQueue.Key("TASK",task),"Task",account,"Account",UUID.randomUUID(),scope.equals("CLOSED")?"DONE":"OPEN",null,"CATALOG",reviewId,null,Timestamp.from(java.time.Instant.now()));
            when(queue.view(auth.getName(),false,scope,"")).thenReturn(new PersonalCollaborationQueue.Page(List.of(new PersonalCollaborationQueue.Row(card,1)),7));
            var body=(PersonalCollaborationController.Presentation)controller.data("",scope,auth).getBody();
            assertThat(body.reviews()).containsEntry(task,review);
            assertThat(body.pictures()).containsEntry(task,"https://example.test/product.png");
        }
    }
}
