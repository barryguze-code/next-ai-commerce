package com.nextaicommerce.platform.collaboration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class PersonalCollaborationThreadControllerTest {
    @Test void scopedDialogUsesAuthorizedRecordAccountAndRejectsMismatchedOrInvisibleContext(){
        var queue=mock(PersonalCollaborationQueue.class);var actions=mock(PersonalCollaborationActions.class);var reviews=mock(CollaborationRepository.class);
        var catalog=mock(com.nextaicommerce.platform.catalog.CatalogRepository.class);
        var controller=new PersonalCollaborationThreadController(queue,actions,reviews,catalog);
        var auth=new UsernamePasswordAuthenticationToken("fixture","unused",List.of());
        UUID id=UUID.randomUUID(),account=UUID.randomUUID(),other=UUID.randomUUID();
        var key=new PersonalCollaborationQueue.Key("REVIEW",id);
        when(queue.authorized("fixture",false,key)).thenReturn(new PersonalCollaborationQueue.Card(key,"Fixture",account,"Account",null,"ACTIVE",null,"CATALOG",null,null,new java.sql.Timestamp(0)));
        controller.members(id,auth);verify(reviews).members(account);
        UUID product=UUID.randomUUID();
        var review=new CollaborationRepository.Review(id,"CATALOG",product.toString(),"Product",null,"CONVERSATION","Task","ACTIVE","fixture",null,null,null,java.time.Instant.now(),java.time.Instant.now(),null,2,0,"Fixture","{}","/app/catalog",false);
        when(reviews.review(account,id,"fixture")).thenReturn(review);
        when(reviews.pictures(account,List.of(review),"fixture")).thenReturn(Map.of(id,"/app/catalog/products/"+product+"/image"));
        when(catalog.productImage(account,product)).thenReturn(new com.nextaicommerce.platform.catalog.CatalogRepository.ProductImage(new byte[]{1,2},"image/png"));
        assertThat(controller.picture(id,auth).getBody()).containsExactly(1,2);
        verify(catalog).productImage(account,product);
        controller.conversation(id,id,"TEAM_CHAT",auth);verify(reviews,times(2)).review(account,id,"fixture");
        assertThatThrownBy(()->controller.reply(id,other,"Test","TEAM_CHAT",null,List.of(),auth)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(actions);
        when(queue.authorized("fixture",false,new PersonalCollaborationQueue.Key("REVIEW",other))).thenThrow(new AccessDeniedException("Denied"));
        assertThatThrownBy(()->controller.members(other,auth)).isInstanceOf(AccessDeniedException.class);
        verify(reviews,never()).members(other);
    }
}
