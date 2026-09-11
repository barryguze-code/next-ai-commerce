package com.nextaicommerce.platform.receiving;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReceivingIntakeService {
    private final ReceivingRepository receiving;

    public ReceivingIntakeService(ReceivingRepository receiving) {
        this.receiving = receiving;
    }

    public record IntakeDocument(String filename, String sha256, List<Map<String,String>> rows) {}

    @Transactional
    public UUID start(UUID tenantId, String actorEmail, UUID vendorId, String documentType,
            String currency, String reference, BigDecimal freight, BigDecimal duty, BigDecimal other,
            List<IntakeDocument> documents) {
        UUID sessionId = receiving.createSession(tenantId, actorEmail, reference, currency,
            freight, duty, other, "VALUE");
        addDocuments(tenantId, sessionId, actorEmail, vendorId, documentType, currency, documents);
        return sessionId;
    }

    @Transactional
    public int addDocuments(UUID tenantId, UUID sessionId, String actorEmail, UUID vendorId,
            String documentType, String currency, List<IntakeDocument> documents) {
        for (IntakeDocument document : documents) {
            receiving.addDocument(tenantId, sessionId, vendorId, documentType, document.filename(),
                document.sha256(), currency, document.rows(), actorEmail);
        }
        return documents.size();
    }
}
