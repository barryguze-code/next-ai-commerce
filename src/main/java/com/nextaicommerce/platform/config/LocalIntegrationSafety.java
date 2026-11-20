package com.nextaicommerce.platform.config;

import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import javax.net.ssl.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Local policy is independent of restored data and individual integration write flags. */
@Component
public class LocalIntegrationSafety {
    private final boolean local;
    private final ThreadLocal<Integer> manualDepth=ThreadLocal.withInitial(()->0);
    private final Map<UUID,Instant> manualRuns=new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public LocalIntegrationSafety(Environment env){
        this(env.getProperty("app.local-development",Boolean.class,false)
            ||Arrays.stream(env.getActiveProfiles()).anyMatch(p->Set.of("local","fee-release-uat").contains(p)));
    }
    public LocalIntegrationSafety(boolean local){this.local=local;}
    public boolean local(){return local;}
    public Scope manualRequest(){manualDepth.set(manualDepth.get()+1);return ()->{int n=manualDepth.get()-1;if(n==0)manualDepth.remove();else manualDepth.set(n);};}
    public interface Scope extends AutoCloseable { @Override void close(); }
    public void allowRun(UUID run){if(local&&run!=null){requireManualRead();manualRuns.put(run,Instant.now().plus(Duration.ofHours(2)));}}
    public boolean allowedRun(UUID run){if(!local)return true;Instant end=manualRuns.get(run);if(end==null)return false;if(end.isBefore(Instant.now())){manualRuns.remove(run);return false;}return true;}
    public Set<UUID> allowedRuns(){manualRuns.entrySet().removeIf(e->e.getValue().isBefore(Instant.now()));return Set.copyOf(manualRuns.keySet());}
    public void requireManualRead(){if(local&&manualDepth.get()==0)throw new IllegalStateException("Local marketplace reads require an explicit user request. No request was sent.");}
    public void requireBusinessWrite(){if(local)throw new IllegalStateException("External marketplace and carrier changes are disabled locally. No request was sent.");}
    public HttpClient guard(HttpClient delegate){return local?new Guarded(delegate):delegate;}
    public boolean manualReadActive(){return manualDepth.get()>0;}
    public void checkRequest(HttpRequest request){check(request);}
    void check(HttpRequest request){
        if(!local)return;
        if(!readOperation(request))requireBusinessWrite();
        requireManualRead();
    }
    static boolean readOperation(HttpRequest request){
        String method=request.method(),host=Objects.toString(request.uri().getHost(),""),path=request.uri().getPath();
        if(Set.of("GET","HEAD").contains(method))return true;
        if(!"POST".equals(method))return false;
        if(host.equals("api.amazon.com")&&path.equals("/auth/o2/token"))return true;
        if(host.matches("sellingpartnerapi-(na|eu|fe)[.]amazon[.]com"))return path.equals("/reports/2021-06-30/reports")
            ||path.matches("/products/fees/v0/listings/[^/]+/feesEstimate")||path.equals("/products/fees/v0/feesEstimate")
            ||path.equals("/tokens/2021-03-01/restrictedDataToken")||path.equals("/batches/products/pricing/v0/itemOffers")||path.equals("/mfn/v0/eligibleShippingServices");
        if(host.endsWith(".myshopify.com")){
            if(path.equals("/admin/oauth/access_token"))return true;
            if(path.matches("/admin/api/[0-9]{4}-[0-9]{2}/graphql[.]json")){
                try{var parsed=new tools.jackson.databind.ObjectMapper().readTree(body(request));String query=parsed.path("query").asText("").stripLeading();
                    return (query.startsWith("query ")||query.startsWith("query{")||query.startsWith("query(")||query.startsWith("{"))&&!query.matches("(?s).*\\b(mutation|subscription)\\b.*");
                }catch(RuntimeException ex){return false;}
            }
        }
        if(Set.of("apis.fedex.com","apis-sandbox.fedex.com").contains(host))return Set.of("/oauth/token","/rate/v1/rates/quotes","/rate/v1/comprehensiverates/quotes","/track/v1/trackingnumbers").contains(path);
        if(host.equals("marketplace.walmartapis.com")&&path.equals("/v3/token"))return true;
        return false;
    }
    private static String body(HttpRequest request){
        var result=new CompletableFuture<String>();var bytes=new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
            private Flow.Subscription subscription;
            public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
            public void onNext(ByteBuffer b){if(bytes.size()+b.remaining()>1_000_000){subscription.cancel();result.completeExceptionally(new IllegalArgumentException("Request too large"));return;}byte[] v=new byte[b.remaining()];b.get(v);bytes.writeBytes(v);subscription.request(1);}
            public void onError(Throwable t){result.completeExceptionally(t);}
            public void onComplete(){result.complete(bytes.toString(StandardCharsets.UTF_8));}
        });
        try{return result.get(1,TimeUnit.SECONDS);}catch(Exception ex){throw new IllegalStateException("Local request could not be classified safely.");}
    }
    private final class Guarded extends HttpClient {
        private final HttpClient delegate;
        Guarded(HttpClient delegate){this.delegate=delegate;}
        public <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> handler)throws java.io.IOException,InterruptedException{check(request);return delegate.send(request,handler);}
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler){check(request);return delegate.sendAsync(request,handler);}
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler,HttpResponse.PushPromiseHandler<T> push){check(request);return delegate.sendAsync(request,handler,push);}
        public Optional<CookieHandler> cookieHandler(){return delegate.cookieHandler();}public Optional<Duration> connectTimeout(){return delegate.connectTimeout();}
        public Redirect followRedirects(){return Redirect.NEVER;}public Optional<ProxySelector> proxy(){return delegate.proxy();}public SSLContext sslContext(){return delegate.sslContext();}
        public SSLParameters sslParameters(){return delegate.sslParameters();}public Optional<Authenticator> authenticator(){return delegate.authenticator();}public Version version(){return delegate.version();}
        public Optional<Executor> executor(){return delegate.executor();}
    }
}
