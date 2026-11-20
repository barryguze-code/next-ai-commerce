package com.nextaicommerce.platform.shipping;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CarrierConnectionRepository {
    private final JdbcTemplate jdbc;
    public CarrierConnectionRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Transactional public Connection localSandboxUat(UUID tenant,String account){
        tenant(tenant);jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",r->{},"local-fedex-uat:"+tenant);
        var existing=list(tenant).stream().filter(c->"LOCAL FEDEX SANDBOX UAT".equals(c.name())&&account.equals(c.accountNumber())&&"SANDBOX".equals(c.environment())&&"PLATFORM".equals(c.credentialSource())&&!c.simulated()).toList();
        if(existing.size()>1)throw new IllegalStateException("Conflicting local sandbox connections.");
        if(!existing.isEmpty())return existing.getFirst();return find(tenant,save(tenant,null,"LOCAL FEDEX SANDBOX UAT",account,"SANDBOX","PLATFORM",null));
    }
    public record Connection(UUID id,String name,String accountNumber,String environment,String credentialSource,
        long revision,String healthStatus,Instant checkedAt,String message,boolean simulated) {
        public Connection(UUID id,String name,String accountNumber,String environment,String credentialSource,long revision,String healthStatus,Instant checkedAt,String message){
            this(id,name,accountNumber,environment,credentialSource,revision,healthStatus,checkedAt,message,false);
        }
    }
    @Transactional(readOnly=true)
    public List<Connection> list(UUID tenant) {
        tenant(tenant);
        return jdbc.query("SELECT * FROM carrier_connections WHERE tenant_id=? AND deleted_at IS NULL ORDER BY created_at,id",
            (rs,n)->new Connection(rs.getObject("id",UUID.class),rs.getString("display_name"),rs.getString("account_number"),
                rs.getString("environment"),rs.getString("credential_source"),rs.getLong("revision"),rs.getString("health_status"),
                rs.getTimestamp("health_checked_at")==null?null:rs.getTimestamp("health_checked_at").toInstant(),rs.getString("health_message"),rs.getBoolean("is_simulated")),tenant);
    }
    @Transactional(readOnly=true)
    public Connection find(UUID tenant,UUID id) {
        return list(tenant).stream().filter(c->c.id().equals(id)).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Carrier connection was not found in this account."));
    }
    @Transactional
    public UUID save(UUID tenant,UUID id,String name,String account,String environment,String source,ShippingLabelCrypto.Encrypted encrypted) {
        tenant(tenant);
        byte[] payload=encrypted==null?null:encrypted.payload(),nonce=encrypted==null?null:encrypted.nonce();
        if(id==null) {
            id=UUID.randomUUID();
            jdbc.update("INSERT INTO carrier_connections(id,tenant_id,display_name,account_number,environment,credential_source,credential_payload,credential_nonce) VALUES (?,?,?,?,?,?,?,?)",
                id,tenant,name,account,environment,source,payload,nonce);
        } else {
            int changed=jdbc.update("""
                UPDATE carrier_connections SET display_name=?,account_number=?,environment=?,credential_source=?,
                  credential_payload=CASE WHEN ?='PLATFORM' THEN NULL ELSE coalesce(?,credential_payload) END,
                  credential_nonce=CASE WHEN ?='PLATFORM' THEN NULL ELSE coalesce(?,credential_nonce) END,
                  registered_customer_payload=NULL,registered_customer_nonce=NULL,
                  revision=revision+1,health_status='PENDING',health_checked_at=NULL,health_message=NULL,updated_at=now()
                WHERE tenant_id=? AND id=? AND deleted_at IS NULL
                """,name,account,environment,source,source,payload,source,nonce,tenant,id);
            if(changed!=1)throw new IllegalArgumentException("Carrier connection was not found.");
        }
        return id;
    }
    @Transactional(readOnly=true)
    public ShippingLabelCrypto.Encrypted secret(UUID tenant,UUID id) {
        tenant(tenant);
        return jdbc.query("SELECT credential_payload,credential_nonce FROM carrier_connections WHERE tenant_id=? AND id=? AND deleted_at IS NULL",
            rs->rs.next()?new ShippingLabelCrypto.Encrypted(rs.getBytes(1),rs.getBytes(2)):null,tenant,id);
    }
    @Transactional
    public void health(UUID tenant,UUID id,long revision,String status,String message) {
        tenant(tenant);
        jdbc.update("UPDATE carrier_connections SET health_status=?,health_message=?,health_checked_at=now() WHERE tenant_id=? AND id=? AND revision=? AND deleted_at IS NULL",
            status,message,tenant,id,revision);
    }
    @Transactional(readOnly=true)
    public ShippingLabelCrypto.Encrypted registeredCustomer(UUID tenant,UUID id,long revision,String account,String environment) {
        tenant(tenant);
        return jdbc.query("""
            SELECT registered_customer_payload,registered_customer_nonce FROM carrier_connections
            WHERE tenant_id=? AND id=? AND revision=? AND account_number=? AND environment=?
              AND credential_source='PLATFORM' AND deleted_at IS NULL
            """,rs->rs.next()?new ShippingLabelCrypto.Encrypted(rs.getBytes(1),rs.getBytes(2)):null,
            tenant,id,revision,account,environment);
    }
    @Transactional
    boolean publishRegisteredCustomer(UUID tenant,UUID id,long revision,String account,ShippingLabelCrypto.Encrypted encrypted) {
        tenant(tenant);
        return jdbc.update("""
            UPDATE carrier_connections SET registered_customer_payload=?,registered_customer_nonce=?,
              revision=revision+1,health_status='PENDING',health_checked_at=NULL,health_message=NULL,updated_at=now()
            WHERE tenant_id=? AND id=? AND revision=? AND account_number=? AND environment='SANDBOX'
              AND credential_source='PLATFORM' AND NOT is_simulated AND deleted_at IS NULL
            """,encrypted.payload(),encrypted.nonce(),tenant,id,revision,account)==1;
    }
    @Transactional
    public void delete(UUID tenant,UUID id) {
        tenant(tenant);
        if(jdbc.update("UPDATE carrier_connections SET deleted_at=now(),credential_payload=NULL,credential_nonce=NULL,registered_customer_payload=NULL,registered_customer_nonce=NULL,revision=revision+1 WHERE tenant_id=? AND id=? AND deleted_at IS NULL",tenant,id)!=1)
            throw new IllegalArgumentException("Carrier connection was not found.");
    }
    private void tenant(UUID tenant) { jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString()); }
}
