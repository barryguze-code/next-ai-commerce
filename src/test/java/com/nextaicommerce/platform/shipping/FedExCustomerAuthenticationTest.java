package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FedExCustomerAuthenticationTest {
    final ObjectMapper json=new ObjectMapper();
    final UUID tenant=UUID.randomUUID(),id=UUID.randomUUID();
    final CarrierConnectionRepository repo=mock(CarrierConnectionRepository.class);
    final ShippingLabelCrypto crypto=new ShippingLabelCrypto(Base64.getEncoder().encodeToString(new byte[32]));
    final AtomicReference<CarrierConnectionRepository.Connection> row=new AtomicReference<>(connection(1,"SANDBOX","PLATFORM","ACTIVE"));
    final AtomicReference<ShippingLabelCrypto.Encrypted> envelope=new AtomicReference<>();
    final List<java.net.http.HttpRequest> requests=new ArrayList<>();
    final FedExClient client=new FedExClient(json,true,r->{requests.add(r);return new FedExClient.Reply(200,
        r.uri().getPath().equals("/oauth/token")?"{\"access_token\":\"unit-token\",\"expires_in\":3600}":"{}");},FedExClient.RateApi.COMPREHENSIVE);

    CarrierConnectionRepository.Connection connection(long revision,String environment,String source,String health) {
        return new CarrierConnectionRepository.Connection(id,"Fixture","001234567",environment,source,revision,health,null,null);
    }
    CarrierConnectionService service(boolean enabled) {
        when(repo.find(tenant,id)).thenAnswer(a->row.get());
        when(repo.registeredCustomer(eq(tenant),eq(id),anyLong(),eq("001234567"),eq("SANDBOX"))).thenAnswer(a->
            row.get().revision()==a.<Long>getArgument(2)?envelope.get():null);
        when(repo.publishRegisteredCustomer(eq(tenant),eq(id),anyLong(),eq("001234567"),any())).thenAnswer(a->{
            if(row.get().revision()!=a.<Long>getArgument(2))return false;
            envelope.set(a.getArgument(4));row.set(connection(row.get().revision()+1,"SANDBOX","PLATFORM","PENDING"));return true;
        });
        doAnswer(a->{if(row.get().revision()==a.<Long>getArgument(2))row.set(connection(row.get().revision(),row.get().environment(),row.get().credentialSource(),a.getArgument(3)));return null;})
            .when(repo).health(eq(tenant),eq(id),anyLong(),anyString(),anyString());
        return new CarrierConnectionService(repo,crypto,client,json,"project-unit-key","project-unit-secret",FedExClient.Environment.SANDBOX,enabled);
    }
    tools.jackson.databind.node.ObjectNode invoice() {
        var response=json.createObjectNode();response.putObject("output").put("child_Key","child-unit-key").put("child_secret","child-unit-secret");return response;
    }
    void publish(CarrierConnectionService service) {
        service.publishRegisteredCustomer(tenant,id,1,CarrierConnectionService.RegistrationEvidence.INVOICE,200,invoice(),Instant.now());
    }
    @Test void missingEvidenceNeverUsesOldParentOAuthOrSendsHttp() {
        var service=service(true);
        assertThat(service.view(tenant,row.get()).authenticationState()).isEqualTo("CUSTOMER_VERIFICATION_REQUIRED");
        assertThatThrownBy(()->service.credentials(tenant,row.get())).hasMessageContaining("Customer verification required");
        service.health(tenant,id);
        assertThat(requests).isEmpty();
    }
    @Test void publicationIsEncryptedAtNextRevisionAndActualClientUsesComprehensiveCsp() {
        var service=service(true);publish(service);
        assertThat(row.get().revision()).isEqualTo(2);
        assertThat(new String(envelope.get().payload(),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("child-unit");
        assertThat(envelope.get().nonce()).hasSize(12);
        assertThat(service.view(tenant,row.get()).authenticationState()).isEqualTo("AUTHENTICATION_NOT_CHECKED");
        assertThat(requests).isEmpty();
        service.health(tenant,id);
        var view=service.view(tenant,row.get());
        assertThat(view.authenticationState()).isEqualTo("CUSTOMER_AUTHENTICATED");
        assertThat(view.message()).contains("Rates and label access not yet verified");
        var payload=json.createObjectNode();payload.putObject("accountNumber").put("value","001234567");
        client.rates(tenant,id,2,FedExClient.Environment.SANDBOX,service.credentials(tenant,row.get()),payload);
        assertThat(requests).hasSize(2);
        assertThat(requests.getLast().uri().getPath()).isEqualTo("/rate/v1/comprehensiverates/quotes");
        assertThat(json.writeValueAsString(view)).doesNotContain("child-unit","project-unit","payload","nonce");
    }
    @Test void directModeOffAndMerchantAuthenticationRemainUnchanged() {
        var direct=service(false).credentials(tenant,row.get());assertThat(direct.customer).isNull();
        assertThat(direct.clientId).isEqualTo("project-unit-key");
        row.set(connection(1,"SANDBOX","MERCHANT","PENDING"));
        when(repo.secret(tenant,id)).thenReturn(crypto.encrypt("{\"clientId\":\"merchant-key\",\"secret\":\"merchant-secret\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var merchant=service(true).credentials(tenant,row.get());assertThat(merchant.customer).isNull();assertThat(merchant.clientId).isEqualTo("merchant-key");
        verify(repo,never()).registeredCustomer(any(),any(),anyLong(),anyString(),anyString());
    }
    @Test void comprehensiveConfigurationDoesNotRerouteDirectOrMerchantKeys() {
        var direct=service(false).credentials(tenant,row.get());var payload=json.createObjectNode();payload.putObject("accountNumber").put("value","001234567");
        client.rates(tenant,id,1,FedExClient.Environment.SANDBOX,direct,payload);
        assertThat(requests.getLast().uri().getPath()).isEqualTo("/rate/v1/rates/quotes");
    }
    @Test void foreignTenantStaleRevisionAndChangedAccountFailBeforeHttp() {
        var service=service(true);publish(service);var current=row.get();
        UUID other=UUID.randomUUID();when(repo.find(other,id)).thenThrow(new IllegalArgumentException("Not found"));
        assertThatThrownBy(()->service.credentials(other,current)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.credentials(tenant,connection(1,"SANDBOX","PLATFORM","ACTIVE"))).isInstanceOf(IllegalStateException.class);
        var wrong=new CarrierConnectionRepository.Connection(id,"Fixture","009876543","SANDBOX","PLATFORM",2,"ACTIVE",null,null);
        assertThatThrownBy(()->service.credentials(tenant,wrong)).isInstanceOf(IllegalStateException.class);
        assertThat(requests).isEmpty();
    }
    @Test void tamperedNonceFailsEvenWithCachedCustomerToken() {
        var service=service(true);publish(service);service.health(tenant,id);assertThat(requests).hasSize(1);
        envelope.set(new ShippingLabelCrypto.Encrypted(envelope.get().payload(),new byte[12]));
        assertThatThrownBy(()->service.credentials(tenant,row.get())).isInstanceOf(IllegalStateException.class);
        assertThat(requests).hasSize(1);
    }
    @Test void stalePublicationLosesCasWithoutExposingCredentials() {
        var service=service(true);when(repo.publishRegisteredCustomer(eq(tenant),eq(id),eq(1L),anyString(),any())).thenReturn(false);
        assertThatThrownBy(()->publish(service)).hasMessageContaining("changed during verification").hasMessageNotContaining("child-unit");
        assertThat(envelope.get()).isNull();assertThat(row.get().revision()).isEqualTo(1);assertThat(requests).isEmpty();
    }
    @Test void addressRequiresCompleteNestedCredentialsAndNoPendingMfa() {
        var service=service(true);var response=json.createObjectNode();response.putObject("output").putObject("credentials").put("child_Key","child-unit-key").put("child_secret","child-unit-secret");
        service.publishRegisteredCustomer(tenant,id,1,CarrierConnectionService.RegistrationEvidence.ADDRESS,200,response,Instant.now());
        assertThat(row.get().revision()).isEqualTo(2);
    }
    @Test void incompleteConflictingStaleAndMalformedEvidenceNeverPublishes() {
        var service=service(true);
        var pending=json.createObjectNode();var option=pending.putObject("output").putArray("mfaOptions").addObject();option.put("accountAuthToken","must-not-be-stored").put("mfaRequired",true).putObject("options").put("invoice","INVOICE");
        assertThatThrownBy(()->service.publishRegisteredCustomer(tenant,id,1,CarrierConnectionService.RegistrationEvidence.ADDRESS,200,pending,Instant.now())).isInstanceOf(IllegalArgumentException.class);
        var conflict=invoice();conflict.withObject("output").putObject("credentials").put("child_Key","other");
        assertThatThrownBy(()->service.publishRegisteredCustomer(tenant,id,1,CarrierConnectionService.RegistrationEvidence.INVOICE,200,conflict,Instant.now())).isInstanceOf(IllegalArgumentException.class);
        var incomplete=invoice();incomplete.withObject("output").remove("child_secret");
        assertThatThrownBy(()->service.publishRegisteredCustomer(tenant,id,1,CarrierConnectionService.RegistrationEvidence.INVOICE,200,incomplete,Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.publishRegisteredCustomer(tenant,id,1,CarrierConnectionService.RegistrationEvidence.INVOICE,503,invoice(),Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.publishRegisteredCustomer(tenant,id,1,CarrierConnectionService.RegistrationEvidence.INVOICE,200,invoice(),Instant.now().minusSeconds(901))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.publishRegisteredCustomer(tenant,id,1,CarrierConnectionService.RegistrationEvidence.INVOICE,200,invoice(),Instant.now().plusSeconds(60))).isInstanceOf(IllegalArgumentException.class);
        verify(repo,never()).publishRegisteredCustomer(any(),any(),anyLong(),anyString(),any());assertThat(requests).isEmpty();
    }
    @Test void editAndDeleteInvalidateCachedTokens() {
        var service=service(true);publish(service);service.health(tenant,id);assertThat(requests).hasSize(1);
        when(repo.save(eq(tenant),eq(id),anyString(),anyString(),anyString(),anyString(),isNull())).thenAnswer(a->{envelope.set(null);row.set(connection(3,"SANDBOX","PLATFORM","PENDING"));return id;});
        service.save(tenant,new CarrierConnectionService.Input(id,"Edited","001234567","SANDBOX","PLATFORM",null,null));
        assertThatThrownBy(()->service.credentials(tenant,row.get())).hasMessageContaining("Customer verification required");
        client.health(tenant,id,2,FedExClient.Environment.SANDBOX,FedExClient.Credentials.registered("project-unit-key","project-unit-secret",
            new FedExRegisteredCustomer(new FedExRegisteredCustomer.Scope(tenant,id,2,FedExClient.Environment.SANDBOX,"001234567"),"old-unit","old-unit")));
        assertThat(requests).hasSize(2);service.delete(tenant,id);verify(repo).delete(tenant,id);
    }
}
