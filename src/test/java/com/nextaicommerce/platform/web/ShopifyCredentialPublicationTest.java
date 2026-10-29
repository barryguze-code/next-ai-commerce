package com.nextaicommerce.platform.web;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ShopifyCredentialPublicationTest {
 @Test void revokedPermissionStopsPublicationInsideTransactionBoundary(){
  var jdbc=mock(JdbcTemplate.class);var repository=new WorkspaceAccessRepository(jdbc);UUID tenant=UUID.randomUUID(),id=UUID.randomUUID();Instant revision=Instant.parse("2026-10-26T12:00:00Z");
  when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<Instant>>any(),eq(tenant),eq(id))).thenReturn(List.of(revision));
  assertThrows(AccessDeniedException.class,()->repository.publishShopifyCredentials(tenant,id,revision,new byte[1],new byte[12],"UTC",()->{throw new AccessDeniedException("Revoked");}));
  verify(jdbc,never()).update(anyString(),any(Object[].class));
 }
 @Test void staleVerificationCannotReplaceNewerOrDisconnectedAuthorization(){
  var jdbc=mock(JdbcTemplate.class);var repository=new WorkspaceAccessRepository(jdbc);UUID tenant=UUID.randomUUID(),id=UUID.randomUUID();Instant old=Instant.parse("2026-10-26T12:00:00Z");
  when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<Instant>>any(),eq(tenant),eq(id))).thenReturn(List.of(old.plusSeconds(1)),List.of());
  for(int i=0;i<2;i++)assertThrows(IllegalArgumentException.class,()->repository.publishShopifyCredentials(tenant,id,old,new byte[1],new byte[12],"UTC",()->{}));
  verify(jdbc,never()).update(anyString(),any(Object[].class));
 }
}
