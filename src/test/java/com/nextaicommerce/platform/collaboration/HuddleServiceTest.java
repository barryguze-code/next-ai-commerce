package com.nextaicommerce.platform.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HuddleServiceTest {
    private final CollaborationRepository repository=mock(CollaborationRepository.class);
    private final CollaborationNotificationWorker notifications=mock(CollaborationNotificationWorker.class);
    private final HuddleService service=new HuddleService(repository,notifications,new ObjectMapper());
    private final UUID tenant=UUID.randomUUID();
    private final HuddleService.Participant barry=person("Barry","barry@example.com");
    private final HuddleService.Participant alex=person("Alex","alex@example.com");

    @Test void liveMessagesRemainInMemoryUntilSomeoneExplicitlySaves(){
        var huddle=service.create(tenant,barry,"INVENTORY","467936|MAIN","Morning Round Cranberry","/app/inventory",
            "{\"on_hand\":1,\"available\":0}",List.of(alex));
        var updated=service.message(tenant,huddle.id(),barry.id(),"Can you check the freezer count?");

        assertThat(updated.status()).isEqualTo("ACTIVE");
        assertThat(updated.messages()).singleElement().satisfies(message->{
            assertThat(message.senderName()).isEqualTo("Barry");assertThat(message.body()).isEqualTo("Can you check the freezer count?");
        });
        verify(repository,never()).saveHuddle(any(),any(),any(),any(),any(),any(),any(),any(),eq(false));
    }

    @Test void invitedTeammateMustJoinTheExactRoomBeforeSending(){
        var huddle=service.create(tenant,barry,"INVENTORY","467936|MAIN","Morning Round Cranberry","/app/inventory","{}",List.of(alex));
        assertThat(huddle.joinedParticipantIds()).containsExactly(barry.id());
        assertThatThrownBy(()->service.message(tenant,huddle.id(),alex.id(),"Can Barry see this?"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Join this huddle");
        var joined=service.join(tenant,huddle.id(),alex.id());
        assertThat(joined.joinedParticipantIds()).containsExactlyInAnyOrder(barry.id(),alex.id());
        assertThat(service.message(tenant,huddle.id(),alex.id(),"Now we are in the same room.").messages()).hasSize(1);
    }

    @Test void saveForFollowUpCreatesOneRegularActiveConversationAndNotifiesOtherParticipants(){
        UUID reviewId=UUID.randomUUID(),messageId=UUID.randomUUID();
        when(repository.saveHuddle(any(),any(),any(),any(),any(),any(),any(),any(),eq(false)))
            .thenReturn(new CollaborationRepository.PostedMessage(reviewId,messageId));
        when(repository.queueNotifications(tenant,reviewId,messageId,Set.of(alex.id()),"MENTION")).thenReturn(1);
        var huddle=service.create(tenant,barry,"ORDER","112-123","Amazon order 112-123","/app/orders","{}",List.of(alex));
        service.message(tenant,huddle.id(),barry.id(),"No answer yet; keep this for tomorrow.");

        var saved=service.save(tenant,huddle.id(),barry.id(),false);

        assertThat(saved.reviewId()).isEqualTo(reviewId);assertThat(saved.huddle().status()).isEqualTo("SAVED");
        verify(repository).queueNotifications(tenant,reviewId,messageId,Set.of(alex.id()),"MENTION");
        verify(notifications).deliverAsync(tenant);
    }

    @Test void saveAndCompletePreservesHistoryWithoutCreatingAnActiveFollowUpNotification(){
        UUID reviewId=UUID.randomUUID(),messageId=UUID.randomUUID();
        when(repository.saveHuddle(any(),any(),any(),any(),any(),any(),any(),any(),eq(true)))
            .thenReturn(new CollaborationRepository.PostedMessage(reviewId,messageId));
        when(repository.queueNotifications(tenant,reviewId,messageId,Set.of(barry.id()),"COMPLETED")).thenReturn(1);
        var huddle=service.create(tenant,barry,"CATALOG","B0123","Cold item","/app/catalog","{}",List.of(alex));
        service.join(tenant,huddle.id(),alex.id());
        service.message(tenant,huddle.id(),alex.id(),"Resolved—the mapping is correct.");

        service.save(tenant,huddle.id(),alex.id(),true);

        verify(repository).queueNotifications(tenant,reviewId,messageId,Set.of(barry.id()),"COMPLETED");
        verify(notifications).deliverAsync(tenant);
    }

    @Test void tenantAndParticipantIsolationAreEnforced(){
        var huddle=service.create(tenant,barry,"LABEL","label-1","Shipping label","/app/shipping","{}",List.of(alex));
        var outsider=person("Morgan","morgan@example.com");
        assertThat(service.find(UUID.randomUUID(),huddle.id(),barry.id())).isNull();
        assertThatThrownBy(()->service.message(tenant,huddle.id(),outsider.id(),"Hello"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not available");
    }

    @Test void huddlesExpireAndDisappearWithoutWritingHistory(){
        var huddle=service.create(tenant,barry,"PLATFORM","GENERAL","General team huddle","/app/collaboration","{}",List.of(alex));
        var expired=service.expire(Instant.now().plus(HuddleService.IDLE_TTL).plusSeconds(1));
        assertThat(expired).singleElement().satisfies(item->{
            assertThat(item.tenantId()).isEqualTo(tenant);assertThat(item.huddle().status()).isEqualTo("ENDED");
        });
        assertThat(service.forParticipant(tenant,barry.id())).isEmpty();
        verify(repository,never()).saveHuddle(any(),any(),any(),any(),any(),any(),any(),any(),any(Boolean.class));
    }

    @Test void startingTheSameRecordAgainAddsTheCurrentlySelectedTeammate(){
        var morgan=person("Morgan","morgan@example.com");
        var original=service.create(tenant,barry,"INVENTORY","467936|MAIN","Morning Round Cranberry","/app/inventory","{}",List.of(alex));

        var refreshed=service.create(tenant,barry,"INVENTORY","467936|MAIN","Morning Round Cranberry","/app/inventory","{}",List.of(morgan));

        assertThat(refreshed.id()).isEqualTo(original.id());
        assertThat(refreshed.participants()).extracting(HuddleService.Participant::id).containsExactlyInAnyOrder(barry.id(),alex.id(),morgan.id());
    }

    private static HuddleService.Participant person(String name,String email){return new HuddleService.Participant(UUID.randomUUID(),name,email);}
}
