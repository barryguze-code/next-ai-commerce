package com.nextaicommerce.platform.shipping;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class CarrierSandboxAccountTest {
 final CarrierConnectionRepository repo=mock(CarrierConnectionRepository.class);
 CarrierConnectionService service(){return new CarrierConnectionService(repo,mock(ShippingLabelCrypto.class),mock(FedExClient.class),new ObjectMapper(),"fixture","fixture",FedExClient.Environment.SANDBOX);}
 @Test void portalIssuedEightDigitSandboxAccountIsPreservedExactly(){
  UUID tenant=UUID.randomUUID(),id=UUID.randomUUID();when(repo.save(tenant,null,"Sandbox","12345678","SANDBOX","PLATFORM",null)).thenReturn(id);
  service().save(tenant,new CarrierConnectionService.Input(null,"Sandbox","12345678","SANDBOX","PLATFORM",null,null));
  verify(repo).save(tenant,null,"Sandbox","12345678","SANDBOX","PLATFORM",null);
 }
 @Test void productionStillRejectsEightDigitsAndSandboxRejectsInvalidFormats(){
  for(String environment:java.util.List.of("PRODUCTION","SANDBOX"))for(String account:java.util.List.of("1234567","1234ABCD","1234567890123"))
   assertThatThrownBy(()->service().save(UUID.randomUUID(),new CarrierConnectionService.Input(null,"Fixture",account,environment,"PLATFORM",null,null))).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->service().save(UUID.randomUUID(),new CarrierConnectionService.Input(null,"Fixture","12345678","PRODUCTION","PLATFORM",null,null))).isInstanceOf(IllegalArgumentException.class);
  verifyNoInteractions(repo);
 }
}
