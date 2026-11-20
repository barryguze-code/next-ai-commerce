package com.nextaicommerce.platform.shipping;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final boolean registeredCustomers;
    private final FedExRegisteredCustomerCodec customerCodec;
    @Autowired
    public CarrierConnectionService(CarrierConnectionRepository repository,ShippingLabelCrypto crypto,FedExClient fedex,ObjectMapper json,
        @Value("${FEDEX_CLIENT_ID:}") String clientId,@Value("${FEDEX_CLIENT_SECRET:}") String secret,
        @Value("${app.fedex.platform-environment:SANDBOX}") FedExClient.Environment platformEnvironment,
        @Value("${app.fedex.registered-customer-enabled:false}") boolean registeredCustomers) {
        this.repository=repository;this.crypto=crypto;this.fedex=fedex;this.json=json;
        this.clientId=clientId;this.secret=secret;this.platformEnvironment=platformEnvironment;
        this.registeredCustomers=registeredCustomers;this.customerCodec=new FedExRegisteredCustomerCodec(crypto,json);
    }
    public CarrierConnectionService(CarrierConnectionRepository repository,ShippingLabelCrypto crypto,FedExClient fedex,ObjectMapper json,
        String clientId,String secret,FedExClient.Environment platformEnvironment) {
        this(repository,crypto,fedex,json,clientId,secret,platformEnvironment,false);
    }
    public record Input(UUID id,String name,String accountNumber,String environment,String credentialSource,String clientId,String clientSecret) {
        @Override public String toString(){return "Carrier connection input [protected]";}
    }
    public List<CarrierConnectionRepository.Connection> list(UUID tenant){return repository.list(tenant).stream().filter(c->!c.simulated()||fedex.simulated()).toList();}
    public record View(UUID id,String name,String accountNumber,String environment,String credentialSource,long revision,
        String healthStatus,Instant checkedAt,String message,String authenticationState) {}
    public List<View> views(UUID tenant){return list(tenant).stream().map(c->view(tenant,c)).toList();}
    public View view(UUID tenant,CarrierConnectionRepository.Connection connection) {
        String state="ACTIVE".equals(connection.healthStatus())?"OAUTH_VERIFIED":connection.healthStatus();
        String message=connection.message();
        if(connection.simulated()) {
            state="SIMULATED";message="Local simulation only. No FedEx request, credentials, charge or shipment.";
        } else if(requiresRegistration(connection)) {
            try {
                credentials(tenant,connection);
                state="ACTIVE".equals(connection.healthStatus())?"CUSTOMER_AUTHENTICATED":"ERROR".equals(connection.healthStatus())?"ERROR":"AUTHENTICATION_NOT_CHECKED";
                message="CUSTOMER_AUTHENTICATED".equals(state)?"Customer authenticated. Rates and label access not yet verified.":
                    "ERROR".equals(state)?"Customer authentication failed. Rates and label access not yet verified.":"Customer credentials saved. Authentication not checked; rates and label access not yet verified.";
            } catch(IllegalArgumentException|IllegalStateException error) {
                state="CUSTOMER_VERIFICATION_REQUIRED";message="Customer verification required. Complete sandbox customer verification before checking rates or creating a label.";
            }
        }
        return new View(connection.id(),connection.name(),connection.accountNumber(),connection.environment(),connection.credentialSource(),
            connection.revision(),connection.healthStatus(),connection.checkedAt(),message,state);
    }
    public CarrierConnectionRepository.Connection save(UUID tenant,Input input) {
        if(input==null)throw new IllegalArgumentException("Complete the connection details.");
        String name=required(input.name(),120,"Connection name"),account=required(input.accountNumber(),20,"Account number");
        var environment=environment(input.environment());
        if(!account.matches(environment==FedExClient.Environment.SANDBOX?"[0-9]{8,12}":"[0-9]{9,12}"))throw new IllegalArgumentException(environment==FedExClient.Environment.SANDBOX?"Enter the 8–12 digit test account exactly as issued in your FedEx sandbox project.":"Enter a 9–12 digit FedEx account number.");
        if(!List.of("PLATFORM","MERCHANT").contains(input.credentialSource()))throw new IllegalArgumentException("Choose an API key source.");
        var existing=input.id()==null?null:repository.find(tenant,input.id());
        if(existing!=null&&existing.simulated())throw new IllegalArgumentException("Local simulation connection is managed with the test fixtures.");
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
        fedex.invalidate(tenant,id);
        return repository.find(tenant,id);
    }
    public CarrierConnectionRepository.Connection health(UUID tenant,UUID id) {
        var connection=repository.find(tenant,id);
        if(connection.simulated())return connection;
        try {
            fedex.health(tenant,id,connection.revision(),environment(connection.environment()),credentials(tenant,connection));
            repository.health(tenant,id,connection.revision(),"ACTIVE",requiresRegistration(connection)?
                "Customer authenticated. Rates and label access not yet verified.":"OAuth verified. Rate and shipment permissions are checked when used.");
        } catch(IllegalArgumentException|IllegalStateException|FedExClient.CarrierException ex) {
            repository.health(tenant,id,connection.revision(),"ERROR",ex.getMessage());
        }
        return repository.find(tenant,id);
    }
    public FedExClient.Credentials credentials(UUID tenant,CarrierConnectionRepository.Connection connection) {
        if(connection.simulated()) {
            if(!fedex.simulated()||!"SANDBOX".equals(connection.environment()))throw new IllegalStateException("Local simulation is disabled.");
            var current=repository.find(tenant,connection.id());
            if(!current.simulated()||current.revision()!=connection.revision())throw new IllegalStateException("Simulation connection changed.");
            return new FedExClient.Credentials("local-simulation","local-simulation");
        }
        if(fedex.simulated())throw new IllegalStateException("Choose the dedicated local simulation connection.");
        if("PLATFORM".equals(connection.credentialSource())) {
            if(environment(connection.environment())!=platformEnvironment)
                throw new IllegalStateException("Platform API keys are not configured for this FedEx environment.");
            if(requiresRegistration(connection)) {
                var current=registrationConnection(tenant,connection.id(),connection.revision());
                if(!current.accountNumber().equals(connection.accountNumber()))throw new IllegalStateException("The carrier connection changed. Reload it before continuing.");
                var encrypted=repository.registeredCustomer(tenant,current.id(),current.revision(),current.accountNumber(),current.environment());
                if(encrypted==null||encrypted.payload()==null||encrypted.nonce()==null)
                    throw new IllegalStateException("Customer verification required. Complete sandbox customer verification before checking rates or creating a label.");
                return FedExClient.Credentials.registered(clientId,secret,customerCodec.open(scope(tenant,current,current.revision()),encrypted));
            }
            return new FedExClient.Credentials(clientId,secret);
        }
        var stored=repository.secret(tenant,connection.id());
        if(stored==null||stored.payload()==null)throw new IllegalStateException("Carrier credentials are not available.");
        try {
            var value=json.readTree(crypto.decrypt(stored.payload(),stored.nonce()));
            return new FedExClient.Credentials(value.path("clientId").asText(),value.path("secret").asText());
        } catch(Exception ex) {throw new IllegalStateException("Stored carrier credentials could not be opened. Re-enter both keys.");}
    }
    boolean requiresRegistration(CarrierConnectionRepository.Connection connection) {
        return registeredCustomers&&!connection.simulated()&&"PLATFORM".equals(connection.credentialSource())&&"SANDBOX".equals(connection.environment());
    }
    private CarrierConnectionRepository.Connection registrationConnection(UUID tenant,UUID id,long revision) {
        if(!registeredCustomers||fedex.simulated()||platformEnvironment!=FedExClient.Environment.SANDBOX)
            throw new IllegalStateException("Local sandbox customer verification is disabled.");
        var current=repository.find(tenant,id);
        if(!requiresRegistration(current)||current.revision()!=revision)
            throw new IllegalStateException("The carrier connection changed. Reload it before continuing.");
        return current;
    }
    private FedExRegisteredCustomer.Scope scope(UUID tenant,CarrierConnectionRepository.Connection connection,long revision) {
        return new FedExRegisteredCustomer.Scope(tenant,connection.id(),revision,environment(connection.environment()),connection.accountNumber());
    }
    enum RegistrationEvidence { ADDRESS, INVOICE }
    /** Internal seam only: no HTTP controller, browser input, registration retry or credential disclosure. */
    CarrierConnectionRepository.Connection publishRegisteredCustomer(UUID tenant,UUID id,long revision,RegistrationEvidence source,
        int httpStatus,tools.jackson.databind.JsonNode response,Instant observedAt) {
        var current=registrationConnection(tenant,id,revision);Instant now=Instant.now();
        if(httpStatus!=200||source==null||response==null||response.has("errors")&&(!response.path("errors").isArray()||response.path("errors").size()!=0)||observedAt==null
            ||observedAt.isAfter(now)||observedAt.isBefore(now.minus(Duration.ofMinutes(15))))
            throw new IllegalArgumentException("Fresh, complete successful FedEx registration evidence is required.");
        var output=response.path("output");
        var credentials=source==RegistrationEvidence.ADDRESS?output.path("credentials"):output;
        if(source==RegistrationEvidence.ADDRESS&&(output.has("mfaOptions")&&(!output.path("mfaOptions").isArray()||output.path("mfaOptions").size()!=0)||output.has("child_Key")||output.has("child_secret"))
            ||source==RegistrationEvidence.INVOICE&&(output.has("credentials")||output.has("mfaOptions")))
            throw new IllegalArgumentException("Customer verification is incomplete or conflicting. No credentials were saved.");
        if(!credentials.path("child_Key").isTextual()||!credentials.path("child_secret").isTextual())
            throw new IllegalArgumentException("Complete registered customer credentials are required.");
        String childKey=credentials.path("child_Key").asText(""),childSecret=credentials.path("child_secret").asText("");
        var verified=new FedExRegisteredCustomer(scope(tenant,current,Math.addExact(revision,1)),childKey,childSecret);
        var sealed=customerCodec.seal(verified);
        if(!repository.publishRegisteredCustomer(tenant,id,revision,current.accountNumber(),sealed))
            throw new IllegalStateException("The carrier connection changed during verification. No credentials were saved.");
        fedex.invalidate(tenant,id);
        return repository.find(tenant,id);
    }
    public void delete(UUID tenant,UUID id){repository.delete(tenant,id);fedex.invalidate(tenant,id);}
    static FedExClient.Environment environment(String value) {
        try{return FedExClient.Environment.valueOf(value==null?"SANDBOX":value);}
        catch(IllegalArgumentException ex){throw new IllegalArgumentException("Choose Sandbox or Production.");}
    }
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static String required(String value,int max,String label){if(blank(value)||value.trim().length()>max)throw new IllegalArgumentException(label+" is required (maximum "+max+" characters).");return value.trim();}
}
