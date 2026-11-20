package com.nextaicommerce.platform.invitation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class LocalEmailDeliveryGuardTest {
    @Test void localUatLimitsDeliveryToItsConfiguredMailbox(){
        var environment=new MockEnvironment();environment.setActiveProfiles("local");
        var guard=new LocalEmailDeliveryGuard(environment,true,"barry.guze@nextaicommerce.com");
        assertThatCode(()->guard.verify("Barry.Guze@nextaicommerce.com")).doesNotThrowAnyException();
        assertThatThrownBy(()->guard.verify("someone@example.com")).isInstanceOf(InvitationException.class);
    }
}
