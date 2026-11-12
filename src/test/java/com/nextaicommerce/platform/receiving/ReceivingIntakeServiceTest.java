package com.nextaicommerce.platform.receiving;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ReceivingIntakeServiceTest {
    @Test void failureNamesTheExactFileAndPreservesTheOriginalCause(){
        var repository=mock(ReceivingRepository.class);
        var failure=new IllegalArgumentException("Row 2 needs a valid quantity. Nothing was saved.");
        doThrow(failure).when(repository).addDocument(any(),any(),any(),anyString(),eq("second.csv"),anyString(),anyString(),anyList(),anyString());
        var service=new ReceivingIntakeService(repository);
        var documents=List.of(new ReceivingIntakeService.IntakeDocument("first.csv","a",List.of()),new ReceivingIntakeService.IntakeDocument("second.csv","b",List.of()));
        assertThatThrownBy(()->service.addDocuments(UUID.randomUUID(),UUID.randomUUID(),"fixture",UUID.randomUUID(),"PACKING_LIST","USD",documents))
            .hasMessage("second.csv: Row 2 needs a valid quantity. Nothing was saved.").hasCause(failure);
    }
}
