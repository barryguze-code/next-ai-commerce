package com.nextaicommerce.platform.receiving;

import com.sun.net.httpserver.*;
import com.nextaicommerce.platform.catalog.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import tools.jackson.databind.json.JsonMapper;

/** Test-only local browser harness. Isolated schema and synthetic user; no production app or Amazon services. */
public class ReceivingWorkflowPreview {
    public static void main(String[] args)throws Exception{
        ReceivingWorkflowDatabaseTest.setup();
        var fixture=new ReceivingWorkflowDatabaseTest();fixture.account();
        var first=fixture.fixture("INVOICE");fixture.fixture("PACKING_LIST");fixture.receive(first,3);
        var catalog=ReceivingWorkflowDatabaseTest.context.getBean(CatalogRepository.class);
        var controller=new ReceivingWorkflowController(ReceivingWorkflowDatabaseTest.work,catalog);
        var home=new ReceivingController(ReceivingWorkflowDatabaseTest.receiving,catalog,null,null);home.configureWorkflow(ReceivingWorkflowDatabaseTest.work);
        var session=new MockHttpSession();session.setAttribute("selectedTenantId",fixture.tenant);session.setAttribute("selectedTenantName","Receiving QA only");
        var auth=new UsernamePasswordAuthenticationToken(fixture.actor,"unused",List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
        var engine=new SpringTemplateEngine();var resolver=new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");resolver.setSuffix(".html");resolver.setTemplateMode("HTML");resolver.setCacheable(false);engine.setTemplateResolver(resolver);
        var json=JsonMapper.builder().findAndAddModules().build();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",18082),0);
        server.createContext("/",exchange->{
            try{
                String path=exchange.getRequestURI().getPath();byte[] content;String type="text/html; charset=utf-8";int status=200;
                var selected=Arrays.stream(Optional.ofNullable(exchange.getRequestURI().getRawQuery()).orElse("").split("&"))
                    .filter(x->x.startsWith("documents=")).flatMap(x->Arrays.stream(URLDecoder.decode(x.substring(10),StandardCharsets.UTF_8).split(",")))
                    .filter(x->!x.isBlank()).map(UUID::fromString).toList();
                if(path.startsWith("/css/")||path.startsWith("/js/")||path.startsWith("/images/")){
                    Path root=Path.of("src/main/resources/static").toAbsolutePath(),file=root.resolve(path.substring(1)).normalize();
                    if(!file.startsWith(root)||!Files.isRegularFile(file)){exchange.sendResponseHeaders(404,-1);exchange.close();return;}
                    content=Files.readAllBytes(file);type=path.endsWith(".css")?"text/css":path.endsWith(".js")?"text/javascript":Optional.ofNullable(Files.probeContentType(file)).orElse("application/octet-stream");
                }else if(path.equals("/app/receiving/work/state")){
                    content=json.writeValueAsBytes(controller.state(selected,session));type="application/json";
                }else if(path.matches("/app/receiving/work/lines/[^/]+/receipts")){
                    content=json.writeValueAsBytes(controller.receipts(UUID.fromString(path.split("/")[5]),session));type="application/json";
                }else if(exchange.getRequestMethod().equals("POST")&&path.matches("/app/receiving/work/[^/]+/[^/]+")){
                    var parts=path.split("/");var command=json.readValue(exchange.getRequestBody(),ReceivingWorkflowController.Command.class);
                    long began=System.nanoTime();var result=controller.change(parts[4],UUID.fromString(parts[5]),command,auth,session);
                    System.out.println("BROWSER_"+parts[4]+"_MS="+((System.nanoTime()-began)/1_000_000));
                    status=result.getStatusCode().value();content=json.writeValueAsBytes(result.getBody());type="application/json";
                }else if(path.equals("/app/receiving")||path.equals("/app/receiving/work")){
                    Map<String,String> query=new HashMap<>();
                    for(String part:Optional.ofNullable(exchange.getRequestURI().getRawQuery()).orElse("").split("&")){
                        String[] pair=part.split("=",2);if(pair.length==2)query.put(pair[0],URLDecoder.decode(pair[1],StandardCharsets.UTF_8));
                    }
                    var model=new ExtendedModelMap();String template=path.endsWith("/work")?controller.work(selected,auth,session,model,new RedirectAttributesModelMap()):home.index(auth,session,model,query.getOrDefault("q",""),Integer.parseInt(query.getOrDefault("page","0")),Integer.parseInt(query.getOrDefault("size","25")));
                    model.addAttribute("canEditCatalog",true);model.addAttribute("canViewOperations",true);model.addAttribute("isSuperAdmin",false);
                    model.addAttribute("canManageUsers",false);model.addAttribute("canManageConnections",false);
                    model.addAttribute("signedInEmail",fixture.actor);model.addAttribute("roleLabel","QA owner");model.addAttribute("selectedAccountName","Receiving QA only");
                    model.addAttribute("displayVersion","0.9.4 · TEST");model.addAttribute("buildVersion","isolated receiving preview");
                    model.addAttribute("_csrf",new DefaultCsrfToken("X-CSRF-TOKEN","_csrf","preview-only"));
                    var servlet=new MockServletContext();var request=new MockHttpServletRequest(servlet,"GET",path);request.setServerPort(18082);
                    var web=new WebContext(JakartaServletWebApplication.buildApplication(servlet).buildExchange(request,new MockHttpServletResponse()));
                    web.setVariables(model);content=engine.process(template,web).getBytes(StandardCharsets.UTF_8);
                }else{type="application/json";content="{}".getBytes(StandardCharsets.UTF_8);}
                exchange.getResponseHeaders().set("Content-Type",type);exchange.getResponseHeaders().set("Cache-Control","no-store");
                exchange.sendResponseHeaders(status,content.length);exchange.getResponseBody().write(content);exchange.close();
            }catch(Exception e){e.printStackTrace();byte[] error="Preview request failed".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(500,error.length);exchange.getResponseBody().write(error);exchange.close();}
        });
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(0);ReceivingWorkflowDatabaseTest.cleanup();}));
        System.out.println("RECEIVING_PREVIEW_READY http://127.0.0.1:18082/app/receiving");
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
