package com.nextaicommerce.platform.fba;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.nextaicommerce.platform.web.MemberAccessService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

class FbaBatchDeductionInvariantTest {
    final UUID tenant=UUID.randomUUID(),store=UUID.randomUUID(),request=UUID.randomUUID();
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final FbaDepletionRepository stock=mock(FbaDepletionRepository.class);
    final MemberAccessService access=mock(MemberAccessService.class);
    final ObjectMapper json=new ObjectMapper();
    FbaBatchDeduction service(){when(access.activePlatformAdministrator("owner")).thenReturn(true);return new FbaBatchDeduction(stock,jdbc,access);}
    List<FbaBatchDeduction.Choice> choices(){return List.of(new FbaBatchDeduction.Choice("A",List.of(new FbaDepletionRepository.Allocation(UUID.randomUUID(),new BigDecimal("5")))));}
    void savedBatch(List<FbaBatchDeduction.Choice> choices,Map<String,Object> parent,UUID id)throws Exception{
        String signature=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(List.of(store,"review",choices,parent)).getBytes(StandardCharsets.UTF_8)));
        String approval=json.writeValueAsString(Map.of("batch",Map.of("signature",signature)));
        when(jdbc.query(contains("SELECT id,shipment_id,approval::text"),org.mockito.ArgumentMatchers.<RowMapper<Object[]>>any(),eq(tenant),eq(request.toString())))
            .thenReturn(Collections.singletonList(new Object[]{id,"A",approval}));
    }
    @Test void successfulReplayReturnsOriginalDeductionWithoutAnotherStockMutation()throws Exception{
        var service=service();var choices=choices();var parent=Map.<String,Object>of("parent","P","identity","identity","review","group-review");UUID id=UUID.randomUUID();savedBatch(choices,parent,id);
        assertThat(service.confirm(tenant,store,"owner",request,"review",choices,parent)).containsExactly(id);
        assertThat(service.confirm(tenant,store,"owner",request,"review",choices,parent)).containsExactly(id);
        verifyNoInteractions(stock);
    }
    @Test void replayCannotBorrowAnotherConnectionOrParentContext()throws Exception{
        var service=service();var choices=choices();var parent=Map.<String,Object>of("parent","P","identity","identity","review","group-review");savedBatch(choices,parent,UUID.randomUUID());
        assertThatThrownBy(()->service.confirm(tenant,UUID.randomUUID(),"owner",request,"review",choices,parent)).hasMessageContaining("different selection or allocation");
        assertThatThrownBy(()->service.confirm(tenant,store,"owner",request,"review",choices,Map.of("parent","OTHER"))).hasMessageContaining("different selection or allocation");
        verifyNoInteractions(stock);
    }
    @Test void failureOfLaterChildRollsBackTheRequiredTransactionInsteadOfCommittingEarlierChild(){
        var target=spy(service());var a=new FbaBatchDeduction.Choice("A",List.of(new FbaDepletionRepository.Allocation(UUID.randomUUID(),BigDecimal.ONE)));
        var b=new FbaBatchDeduction.Choice("B",List.of(new FbaDepletionRepository.Allocation(UUID.randomUUID(),BigDecimal.ONE)));
        doReturn(new FbaBatchDeduction.Review(List.of(),List.of(),"review")).when(target).preview(tenant,store,List.of("A","B"));
        when(stock.preview(tenant,store,"A")).thenReturn(preview("A"));when(stock.preview(tenant,store,"B")).thenReturn(preview("B"));
        when(stock.deplete(eq(tenant),eq(store),eq("A"),eq("owner"),any(),eq("A-revision"),anyList(),anyMap())).thenReturn(UUID.randomUUID());
        when(stock.deplete(eq(tenant),eq(store),eq("B"),eq("owner"),any(),eq("B-revision"),anyList(),anyMap())).thenThrow(new IllegalArgumentException("Insufficient stock for B"));
        var transactions=new RecordingTransactions();var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        var service=(FbaBatchDeduction)proxy.getProxy();
        assertThatThrownBy(()->service.confirm(tenant,store,"owner",request,"review",List.of(a,b),Map.of("parent","P"))).hasMessageContaining("Insufficient stock for B");
        assertThat(transactions.propagation).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(transactions.rollbacks).isEqualTo(1);assertThat(transactions.commits).isZero();
        verify(stock).deplete(eq(tenant),eq(store),eq("A"),eq("owner"),any(),eq("A-revision"),anyList(),anyMap());
    }
    private static FbaDepletionRepository.Preview preview(String shipment){return new FbaDepletionRepository.Preview(shipment,null,"Warehouse","ZIP",shipment+"-revision",List.of(),List.of(),List.of(),List.of(),false,false,true);}
    private static class RecordingTransactions implements PlatformTransactionManager {
        int propagation,commits,rollbacks;
        @Override public TransactionStatus getTransaction(TransactionDefinition definition){propagation=definition.getPropagationBehavior();return new SimpleTransactionStatus();}
        @Override public void commit(TransactionStatus status){commits++;}
        @Override public void rollback(TransactionStatus status){rollbacks++;}
    }
}
