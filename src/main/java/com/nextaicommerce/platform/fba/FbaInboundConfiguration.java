package com.nextaicommerce.platform.fba;
import java.time.Clock;
import org.springframework.context.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class FbaInboundConfiguration {
 @Bean FbaInboundOrchestration fbaInboundOrchestration(FbaInboundCheckpoints checkpoints,FbaInboundPayloads payloads,FbaInboundDocuments documents,FbaInboundAdapter adapter,ObjectMapper json){return new FbaInboundOrchestration(checkpoints,payloads,documents,adapter,json,Clock.systemUTC());}
}
