package com.nextaicommerce.platform.shipping;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class CarrierConnectionService {
    private final CarrierConnectionRepository repository;
    private final ShippingLabelCrypto crypto;
    private final FedExClient fedex;
    private final ObjectMapper json;
    private final String clientId,secret;
    private final FedExClient.Environment platformEnvironment;
    public CarrierConnectionService(CarrierConnectionRepository repository,ShippingLabelCrypto crypto,FedExClient fedex,ObjectMapper json,
        @Value("${FEDEX_CLIENT_ID:}") String clientId,@Value("${FEDEX_CLIENT_SECRET:}") String secret,
        @Value("${app.fedex.platform-environment:SANDBOX}") FedExClient.Environment platformEnvironment) {
        this.repository=repository;this.crypto=crypto;this.fedex=fedex;this.json=json;
        this.clientId=clientId;this.secret=secret;this.platformEnvironment=platformEnvironment;
    }
    public record Input(UUID id,String name,String accountNumber,String environment,String credentialSource,String clientId,String clientSecret) {
        @Override public String toString(){return "Carrier connection input [protected]";}
    }
    public List<CarrierConnectionRepository.Connection> list(UUID tenant){return repository.list(tenant);}
    public CarrierConnectionRepository.Connection save(UUID tenant,Input input) {
        if(input==null)throw new IllegalArgumentException("Complete the connection details.");
        String name=required(input.name(),120,"Connection name"),account=required(input.accountNumber(),20,"Account number");
        if(!account.matches("[0-9]{9,12}"))throw new IllegalArgumentException("Enter a 9–12 digit FedEx account number.");
        var environment=environment(input.environment());
        if(!List.of("PLATFORM","MERCHANT").contains(input.credentialSource()))throw new IllegalArgumentException("Choose an API key source.");
        var existing=input.id()==null?null:repository.find(tenant,input.id());
        ShippingLabelCrypto.Encrypted encrypted=null;
        if("MERCHANT".equals(input.credentialSource())) {
            boolean empty=blank(input.clientId())&&blank(input.clientSecret());
            if(!empty || existing==null || !"MERCHANT".equals(existing.credentialSource())) {
                String key=required(input.clientId(),500,"Client ID"),secret=required(input.clientSecret(),2000,"Client secret");
                encrypted=crypto.encrypt(json.createObjectNode().put("clientId",key).put("secret",secret).toString().getBytes(StandardCharsets.UTF_8));
            } else if(!existing.environment().equals(environment.name())) {
                throw new IllegalArgumentException("Enter new API keys when changing the FedEx environment.");
            }
        }
        UUID id=repository.save(tenant,input.id(),name,account,environment.name(),input.credentialSource(),encrypted);
        return repository.find(tenant,id);
    }
    public CarrierConnectionRepository.Connection health(UUID tenant,UUID id) {
        var connection=repository.find(tenant,id);
        try {
            fedex.health(tenant,id,connection.revision(),environment(connection.environment()),credentials(tenant,connection));
            repository.health(tenant,id,connection.revision(),"ACTIVE","OAuth verified. Rate and shipment permissions are checked when used.");
        } catch(IllegalArgumentException|IllegalStateException|FedExClient.CarrierException ex) {
            repository.health(tenant,id,connection.revision(),"ERROR",ex.getMessage());
        }
        return repository.find(tenant,id);
    }
    public FedExClient.Credentials credentials(UUID tenant,CarrierConnectionRepository.Connection connection) {
        if("PLATFORM".equals(connection.credentialSource())) {
            if(environment(connection.environment())!=platformEnvironment)
                throw new IllegalStateException("Platform API keys are not configured for this FedEx environment.");
            return new FedExClient.Credentials(clientId,secret);
        }
        var stored=repository.secret(tenant,connection.id());
        if(stored==null||stored.payload()==null)throw new IllegalStateException("Carrier credentials are not available.");
        try {
            var value=json.readTree(crypto.decrypt(stored.payload(),stored.nonce()));
            return new FedExClient.Credentials(value.path("clientId").asText(),value.path("secret").asText());
        } catch(Exception ex) {throw new IllegalStateException("Stored carrier credentials could not be opened. Re-enter both keys.");}
    }
    public void delete(UUID tenant,UUID id){repository.delete(tenant,id);}
    static FedExClient.Environment environment(String value) {
        try{return FedExClient.Environment.valueOf(value==null?"SANDBOX":value);}
        catch(IllegalArgumentException ex){throw new IllegalArgumentException("Choose Sandbox or Production.");}
    }
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static String required(String value,int max,String label){if(blank(value)||value.trim().length()>max)throw new IllegalArgumentException(label+" is required (maximum "+max+" characters).");return value.trim();}
}
