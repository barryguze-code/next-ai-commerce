package com.nextaicommerce.platform.fba;

import com.nextaicommerce.platform.shipping.ShippingLabelCrypto;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.apache.pdfbox.Loader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import static com.nextaicommerce.platform.fba.FbaInboundWorkflow.*;

/** Immediate ingestion of a genuine short-lived Amazon document into scoped encrypted storage.
 * Signed download URLs never enter logs, persistence or browser responses. No redirect is followed.
 * The exact Amazon-controlled download hosts must be reviewed/configured before live activation.
 */
@Component
public class FbaInboundDocuments implements FbaInboundOrchestration.Documents {
 @org.springframework.beans.factory.annotation.Autowired void configureLocalSafety(com.nextaicommerce.platform.config.LocalIntegrationSafety safety){if(download instanceof AmazonDownload amazon)amazon.client=safety.guard(amazon.client);}
 static final int MAX_BYTES=10*1024*1024;
 interface Download { byte[] pdf(URI uri); }
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ShippingLabelCrypto crypto;
 private final FbaInboundOrchestration.Checkpoints checkpoints;private final boolean enabled,amazonWrites;private final Download download;
 @Autowired public FbaInboundDocuments(JdbcTemplate jdbc,TransactionTemplate tx,ShippingLabelCrypto crypto,FbaInboundOrchestration.Checkpoints checkpoints,
   @Value("${app.fba.shipment-write-enabled:false}") boolean enabled,@Value("${app.amazon.write-enabled:false}") boolean amazonWrites,
   @Value("${app.integrations.external-enabled:true}") boolean externalEnabled,@Value("${app.fba.documents.allowed-hosts:}") String hosts){this(jdbc,tx,crypto,checkpoints,enabled&&externalEnabled,amazonWrites,new AmazonDownload(hosts));}
 FbaInboundDocuments(JdbcTemplate jdbc,TransactionTemplate tx,ShippingLabelCrypto crypto,FbaInboundOrchestration.Checkpoints checkpoints,boolean enabled,boolean amazonWrites,Download download){this.jdbc=jdbc;this.tx=tx;this.crypto=crypto;this.checkpoints=checkpoints;this.enabled=enabled;this.amazonWrites=amazonWrites;this.download=download;}
 public record Pdf(UUID id,String child,long packingVersion,DocumentKind kind,boolean stale,String mime,byte[] payload){public Pdf{payload=payload.clone();}@Override public byte[] payload(){return payload.clone();}}
 @Override public String ingest(Scope scope,UUID warehouse,String child,long packingVersion,URI genuineAmazonDownload){return ingest(scope,warehouse,child,packingVersion,genuineAmazonDownload,DocumentKind.SHIPPING_LABEL);}
 @Override public String ingest(Scope scope,UUID warehouse,String child,long packingVersion,URI genuineAmazonDownload,DocumentKind kind){
  if(kind==null||kind==DocumentKind.ITEM_LABEL)throw new IllegalArgumentException("Choose a supported Amazon shipment document.");
  if(!enabled||!amazonWrites)throw new IllegalStateException("Amazon-issued document retrieval is disabled in Local UAT.");
  if(!crypto.configured())throw new IllegalStateException("Shipping document encryption is not configured.");
  current(scope,warehouse,child,packingVersion,kind);byte[] bytes=download.pdf(Objects.requireNonNull(genuineAmazonDownload));
  try{
   int pages=validatePdf(bytes);String hash=hash(bytes);UUID id=UUID.randomUUID();var encrypted=crypto.encrypt(bytes,context(scope,warehouse,child,packingVersion,kind,hash,id));
   return tx.execute(status->{scope(scope);jdbc.queryForObject("SELECT id FROM fba_replenishment_plans WHERE tenant_id=? AND marketplace_connection_id=? AND id=? FOR UPDATE",UUID.class,scope.tenant(),scope.connection(),scope.replenishment());current(scope,warehouse,child,packingVersion,kind);
    jdbc.update("""
     INSERT INTO fba_replenishment_documents(id,tenant_id,marketplace_connection_id,plan_id,warehouse_id,amazon_shipment_id,packing_version,document_type,sha256,encrypted_payload,encryption_nonce,page_count)
     VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
     ON CONFLICT(tenant_id,marketplace_connection_id,plan_id,warehouse_id,amazon_shipment_id,packing_version,document_type,sha256) DO NOTHING
     """,id,scope.tenant(),scope.connection(),scope.replenishment(),warehouse,child,packingVersion,kind.name(),hash,encrypted.payload(),encrypted.nonce(),pages);
    return jdbc.queryForObject("""
     SELECT id FROM fba_replenishment_documents WHERE tenant_id=? AND marketplace_connection_id=? AND plan_id=? AND warehouse_id=?
      AND amazon_shipment_id=? AND packing_version=? AND document_type=? AND sha256=?
     """,UUID.class,scope.tenant(),scope.connection(),scope.replenishment(),warehouse,child,packingVersion,kind.name(),hash).toString();
   });
  }finally{Arrays.fill(bytes,(byte)0);}
 }
 void scope(Scope scope){if(!scope.tenant().equals(scope.account()))throw new IllegalArgumentException("Account scope mismatch.");jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,scope.tenant().toString());}
 void current(Scope scope,UUID warehouse,String child,long version,DocumentKind kind){
  if(!scope.tenant().equals(scope.account())||version<1||child==null||child.isBlank()||child.length()>100)throw new IllegalArgumentException("A verified document scope and version are required.");
  var state=checkpoints.read(scope,warehouse).checkpoint();var origin=state.origin();
  if(!scope.equals(origin.scope())||!warehouse.equals(origin.warehouse())||origin.packingVersion()!=version||!origin.children().containsKey(child))throw new IllegalStateException("Shipment or packing version changed; retrieve current documents again.");
  FbaInboundOrchestration.documentConfirmation(state,child,kind);
 }
 static boolean stale(FbaInboundOrchestration.Checkpoint state,UUID artifact,String child,long version,DocumentKind kind){
  var origin=state.origin();if(version!=origin.packingVersion()||!origin.children().containsKey(child))return true;
  try{var request=FbaInboundOrchestration.documentConfirmation(state,child,kind);var attempt=origin.attempts().get(request);
   return origin.documents().stream().noneMatch(d->d.amazonDocumentId().equals(artifact.toString())&&d.kind()==kind&&d.childId().equals(child)&&d.packingVersion()==version&&d.amazonOperationId().equals(attempt.amazonOperationId()));
  }catch(IllegalStateException e){return true;}
 }
 /** Caller still enforces shipment-view permission. The query binds all tenant/store/plan/origin keys. */
 public Pdf read(Scope scope,UUID warehouse,UUID artifact){
  var reads=new TransactionTemplate(Objects.requireNonNull(tx.getTransactionManager()));reads.setReadOnly(true);
  return reads.execute(status->{scope(scope);var rows=jdbc.queryForList("""
   SELECT id,amazon_shipment_id,packing_version,document_type,sha256,encrypted_payload,encryption_nonce,mime_type
   FROM fba_replenishment_documents WHERE tenant_id=? AND marketplace_connection_id=? AND plan_id=? AND warehouse_id=? AND id=?
   """,scope.tenant(),scope.connection(),scope.replenishment(),warehouse,artifact);if(rows.size()!=1)throw new IllegalArgumentException("Document not found in this shipment.");var r=rows.getFirst();String child=(String)r.get("amazon_shipment_id"),hash=(String)r.get("sha256");long version=((Number)r.get("packing_version")).longValue();var kind=DocumentKind.valueOf((String)r.get("document_type"));
   var state=checkpoints.read(scope,warehouse).checkpoint();if(!scope.equals(state.origin().scope())||!warehouse.equals(state.origin().warehouse()))throw new IllegalArgumentException("Document belongs to another origin.");
   byte[] plain=crypto.decrypt((byte[])r.get("encrypted_payload"),(byte[])r.get("encryption_nonce"),context(scope,warehouse,child,version,kind,hash,artifact));try{if(!hash.equals(hash(plain)))throw new IllegalStateException("Stored document integrity check failed.");return new Pdf(artifact,child,version,kind,stale(state,artifact,child,version,kind),(String)r.get("mime_type"),plain);}finally{Arrays.fill(plain,(byte)0);}
  });
 }
 static byte[] context(Scope scope,UUID warehouse,String child,long version,DocumentKind kind,String hash,UUID id){return ("FBA_DOCUMENT:"+scope.tenant()+":"+scope.account()+":"+scope.connection()+":"+scope.replenishment()+":"+warehouse+":"+child+":"+version+":"+kind+":"+hash+":"+id).getBytes(StandardCharsets.UTF_8);}
 static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException("Document integrity check unavailable.");}}
 static int validatePdf(byte[] bytes){
  if(bytes==null||bytes.length<5||bytes.length>MAX_BYTES||!new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))throw new IllegalArgumentException("Amazon did not return a supported bounded PDF document.");
  try(var pdf=Loader.loadPDF(bytes)){int count=pdf.getNumberOfPages();if(pdf.isEncrypted()||count<1||count>200)throw new IllegalArgumentException("Amazon PDF requires review before storage.");return count;}catch(IOException e){throw new IllegalArgumentException("Amazon PDF could not be verified.");}
 }
 static class AmazonDownload implements Download {
  private final Set<String> hosts;private HttpClient client;
  AmazonDownload(String configured){var values=new HashSet<String>();if(configured!=null)for(String host:configured.split(","))if(!host.isBlank()){host=host.trim().toLowerCase(Locale.ROOT);if(!trustedHost(host))throw new IllegalArgumentException("Document host must be an exact reviewed Amazon-controlled host.");values.add(host);}hosts=Set.copyOf(values);client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();}
  static boolean trustedHost(String host){return host.matches("[a-z0-9]+(?:[a-z0-9.-]*[a-z0-9])?")&&(host.endsWith(".amazonaws.com")||host.endsWith(".amazonaws.com.cn")||host.endsWith(".amazon.com"))&&!host.contains("..");}
  void validate(URI uri){if(uri==null||!"https".equalsIgnoreCase(uri.getScheme())||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getHost()==null||uri.getPort()!=-1&&uri.getPort()!=443||uri.toString().length()>16384||!hosts.contains(uri.getHost().toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Amazon document download host is not approved.");}
  static boolean publicAddress(InetAddress address){
   if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())return false;byte[] b=address.getAddress();int a=b[0]&255;if(b.length==4){int c=b[1]&255;return a!=0&&a<224&&!(a==100&&c>=64&&c<=127)&&!(a==198&&(c==18||c==19))&&!(a==192&&c==0)&&!(a==198&&c==51)&&!(a==203&&c==0);}return a>=0x20&&a<=0x3f&&!(a==0x20&&(b[1]&255)==1&&(b[2]&255)==0x0d&&(b[3]&255)==0xb8);
  }
  @Override public byte[] pdf(URI uri){validate(uri);CompletableFuture<HttpResponse<byte[]>> future=null;
   try{
    var addresses=InetAddress.getAllByName(uri.getHost());if(addresses.length==0||Arrays.stream(addresses).anyMatch(a->!publicAddress(a)))throw new IllegalArgumentException("Amazon document destination is not public.");
    var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("Accept","application/pdf").GET().build();
    future=client.sendAsync(request,info->{if(info.statusCode()!=200)throw new IllegalStateException("Amazon document download was not successful.");String mime=info.headers().firstValue("Content-Type").orElse("").split(";",2)[0].trim();if(!Set.of("application/pdf","application/octet-stream").contains(mime)||info.headers().firstValueAsLong("Content-Length").orElse(0)>MAX_BYTES)throw new IllegalStateException("Amazon document response is not a supported bounded PDF.");return new LimitedBody();});
    byte[] bytes=future.get(20,TimeUnit.SECONDS).body();validate(uri);return bytes;
   }catch(InterruptedException e){Thread.currentThread().interrupt();if(future!=null)future.cancel(true);throw new IllegalStateException("Amazon document download was interrupted.");}
   catch(Exception e){if(future!=null)future.cancel(true);throw new IllegalStateException("Amazon document could not be downloaded safely. Request a fresh issued document.");}
  }
 }
 static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
  private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
  @Override public CompletionStage<byte[]> getBody(){return result;}
  @Override public void onSubscribe(Flow.Subscription value){if(subscription!=null){value.cancel();return;}subscription=value;value.request(1);}
  @Override public void onNext(List<ByteBuffer> values){try{for(var value:values){if(value.remaining()>MAX_BYTES-bytes.size())throw new IllegalStateException("Document size exceeded limit.");byte[] part=new byte[value.remaining()];value.get(part);bytes.write(part);Arrays.fill(part,(byte)0);}subscription.request(1);}catch(Exception e){subscription.cancel();result.completeExceptionally(new IllegalStateException("Document size exceeded limit."));}}
  @Override public void onError(Throwable failure){result.completeExceptionally(new IllegalStateException("Document download interrupted."));}
  @Override public void onComplete(){result.complete(bytes.toByteArray());}
 }
}
