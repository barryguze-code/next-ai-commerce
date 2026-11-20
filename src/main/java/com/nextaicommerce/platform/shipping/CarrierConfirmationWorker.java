package com.nextaicommerce.platform.shipping;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name={"app.amazon.write-enabled","app.fedex.confirmation-enabled"},havingValue="true")
public class CarrierConfirmationWorker {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final CarrierShipmentConfirmation confirmation;private final ObjectMapper json;
    public CarrierConfirmationWorker(JdbcTemplate jdbc,TransactionTemplate tx,CarrierShipmentConfirmation confirmation,ObjectMapper json){this.jdbc=jdbc;this.tx=tx;this.confirmation=confirmation;this.json=json;}
    record Command(UUID id,UUID store,String order,JsonNode payload) {}
    @Scheduled(fixedDelayString="${app.fedex.confirmation-delay-ms:15000}",initialDelay=30000)
    public void work(){
        for(UUID tenant:jdbc.query("SELECT id FROM tenants WHERE status='ACTIVE'",(rs,n)->rs.getObject(1,UUID.class))){
            Command command=tx.execute(s->{tenant(tenant);
                // A process crash after the Amazon call requires review, never a second label purchase.
                jdbc.update("UPDATE carrier_shipments SET confirmation_state='REVIEW' WHERE tenant_id=? AND confirmation_state='IN_PROGRESS' AND confirmation_attempted_at<now()-interval '5 minutes'",tenant);
                return jdbc.query("""
                    UPDATE carrier_shipments SET confirmation_state='IN_PROGRESS',confirmation_attempts=confirmation_attempts+1,confirmation_attempted_at=now()
                    WHERE id=(SELECT id FROM carrier_shipments WHERE tenant_id=? AND environment='PRODUCTION' AND state='PURCHASED' AND confirmation_state='PENDING' ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1)
                    RETURNING id,marketplace_connection_id,amazon_order_id,confirmation_payload
                    """,rs->rs.next()?new Command(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),json.readTree(rs.getString(4))):null,tenant);
            });
            if(command==null)continue;
            String result;
            try{confirmation.confirm(tenant,command.store(),command.order(),FedExClient.Environment.PRODUCTION,command.payload());result="CONFIRMED";}
            catch(RuntimeException ex){result="REVIEW";}
            String state=result;tx.executeWithoutResult(s->{tenant(tenant);jdbc.update("UPDATE carrier_shipments SET confirmation_state=?,updated_at=now() WHERE tenant_id=? AND id=? AND confirmation_state='IN_PROGRESS'",state,tenant,command.id());});
        }
    }
    private void tenant(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
}
