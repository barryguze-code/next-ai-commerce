package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class CarrierConfirmationGuardTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
        .withUserConfiguration(CarrierConfirmationWorker.class)
        .withBean(JdbcTemplate.class,()->mock(JdbcTemplate.class))
        .withBean(TransactionTemplate.class,()->mock(TransactionTemplate.class))
        .withBean(CarrierShipmentConfirmation.class,()->mock(CarrierShipmentConfirmation.class))
        .withBean(ObjectMapper.class,ObjectMapper::new);
    @Test void bothMissingIsOff(){runner.run(c->assertThat(c).doesNotHaveBean(CarrierConfirmationWorker.class));}
    @Test void existingWriteAloneCannotEnableNewWorker(){runner.withPropertyValues("app.amazon.write-enabled=true").run(c->assertThat(c).doesNotHaveBean(CarrierConfirmationWorker.class));}
    @Test void explicitFalseRemainsOff(){runner.withPropertyValues("app.amazon.write-enabled=true","app.fedex.confirmation-enabled=false").run(c->assertThat(c).doesNotHaveBean(CarrierConfirmationWorker.class));}
    @Test void writeFalseWins(){runner.withPropertyValues("app.amazon.write-enabled=false","app.fedex.confirmation-enabled=true").run(c->assertThat(c).doesNotHaveBean(CarrierConfirmationWorker.class));}
    @Test void bothExplicitTrueAreRequired(){runner.withPropertyValues("app.amazon.write-enabled=true","app.fedex.confirmation-enabled=true").run(c->assertThat(c).hasSingleBean(CarrierConfirmationWorker.class));}
}
