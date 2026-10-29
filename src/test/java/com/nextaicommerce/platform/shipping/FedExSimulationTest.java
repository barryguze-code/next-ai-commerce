package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import tools.jackson.databind.ObjectMapper;

class FedExSimulationTest {
    final ObjectMapper json=new ObjectMapper();final UUID tenant=UUID.randomUUID(),store=UUID.randomUUID(),carrier=UUID.randomUUID(),fixtureId=UUID.randomUUID(),quoteId=UUID.randomUUID();
    final String order=LocalShippingFixtures.PREFIX+fixtureId;
    final CarrierConnectionRepository connections=mock(CarrierConnectionRepository.class);
    final FedExShipmentRepository repository=mock(FedExShipmentRepository.class);
    final LocalShippingFixtures fixtures=mock(LocalShippingFixtures.class);
    final CarrierOrderContext orders=mock(CarrierOrderContext.class);
    final ShippingPreferences preferences=mock(ShippingPreferences.class);
    final CarrierShipmentConfirmation confirmation=mock(CarrierShipmentConfirmation.class);
    final ShippingLabelCrypto crypto=new ShippingLabelCrypto(Base64.getEncoder().encodeToString(new byte[32]));
    final FedExClient client=spy(new FedExClient(json,false,FedExClient.RateApi.COMPREHENSIVE,true));
    final CarrierConnectionRepository.Connection connection=new CarrierConnectionRepository.Connection(carrier,"LOCAL SIMULATION","000000000","SANDBOX","PLATFORM",1,"PENDING",null,null,true);
    final List<CarrierLabelComposer.Line> lines=new ArrayList<>(List.of(new CarrierLabelComposer.Line("Test brand","TEST-EAx3","Synthetic product",3)));
    final AtomicReference<FedExShipmentRepository.Shipment> shipment=new AtomicReference<>();
    final AtomicReference<ShippingLabelCrypto.Encrypted> request=new AtomicReference<>(),label=new AtomicReference<>();
    FedExShippingService service;
    final UUID sender=UUID.randomUUID();
    @BeforeEach void setup(){
        var credentials=new CarrierConnectionService(connections,crypto,client,json,"","",FedExClient.Environment.SANDBOX,true);
        service=new FedExShippingService(null,connections,credentials,repository,client,crypto,new CarrierLabelComposer(),json,confirmation,preferences,null);
        ReflectionTestUtils.setField(service,"carrierOrders",orders);ReflectionTestUtils.setField(service,"fixtures",fixtures);ReflectionTestUtils.setField(service,"simulation",true);
        var address=new BuyShippingRepository.Address(sender,"Synthetic sender","Local Sender","LOCAL TEST","10 FedEx Parkway",null,null,"Collierville","TN","38017","US","9015550100",null,true);
        var context=new BuyShippingService.Context(null,new BuyShippingRepository.OrderInfo(order,"Unshipped","MERCHANT",null,null,null,null,null),List.of(new BuyShippingRepository.OrderItem("local-line","TEST-EAx3",null,"Synthetic product",3,0)),List.of(address),List.of(),null,List.of(),Map.of());
        when(orders.context(tenant,store,order)).thenReturn(context);when(orders.packing(tenant,store,order)).thenReturn(lines);
        when(fixtures.get(tenant,store,order)).thenReturn(new LocalShippingFixtures.Fixture(fixtureId,"LOCAL TEST - Manual package",new LocalShippingFixtures.Definition(lines,null),null));
        when(connections.find(tenant,carrier)).thenReturn(connection);when(connections.list(tenant)).thenReturn(List.of(connection));
        when(preferences.get(tenant,store)).thenReturn(new ShippingPreferences.Settings("THERMAL_4X6",CarrierLabelComposer.Layout.ORIGINAL));
        when(repository.list(tenant,store,order)).thenAnswer(a->shipment.get()==null?List.of():List.of(shipment.get()));
        when(repository.protectedShipment(tenant,store,order)).thenAnswer(a->shipment.get()!=null&&shipment.get().state().equals("PURCHASED")?shipment.get():null);
        when(repository.quote(eq(tenant),eq(store),eq(order),eq(connection),any(),anyList(),anyList(),anyString())).thenAnswer(a->{
            request.set(a.getArgument(4));shipment.set(new FedExShipmentRepository.Shipment(quoteId,carrier,1,order,"SANDBOX","RATED",json.valueToTree(a.getArgument(5)),json.valueToTree(a.getArgument(6)),null,null,true));return quoteId;
        });
        var claimed=new AtomicBoolean();when(repository.claim(eq(tenant),eq(store),eq(order),eq(quoteId),anyString(),eq(1L))).thenAnswer(a->claimed.compareAndSet(false,true)?request.get():null);
        doAnswer(a->{label.set(a.getArgument(3));var old=shipment.get();shipment.set(new FedExShipmentRepository.Shipment(quoteId,carrier,1,order,"SANDBOX","PURCHASED",old.rates(),old.packing(),a.getArgument(2),null,true));return null;})
            .when(repository).purchased(eq(tenant),eq(quoteId),anyString(),any(),isNull());
        when(repository.label(tenant,store,quoteId)).thenAnswer(a->label.get());
    }
    @Test void realOrderRateReadRequiresFreshNativeStatusAndNeverTouchesShipmentOrStockPaths(){
        String nativeOrder=UUID.randomUUID().toString();var rates=mock(FedExSandboxRates.class);ReflectionTestUtils.setField(service,"sandboxRates",rates);
        var context=orders.context(tenant,store,order);when(orders.rateOnlyContext(tenant,store,nativeOrder)).thenReturn(context);
        var in=new FedExShippingService.RateOnlyInput(sender,input(BigDecimal.ONE).parcel(),input(BigDecimal.ONE).recipient());
        var result=new FedExSandboxRates.Quote(List.of(),true,"SANDBOX","test route");when(rates.quote(eq(tenant),eq(store),any(),eq(in.recipient()),eq(in.parcel()))).thenReturn(result);
        assertThat(service.sandboxRates(tenant,store,nativeOrder,in,"unit")).isSameAs(result);verify(orders).rateOnlyContext(tenant,store,nativeOrder);verifyNoInteractions(repository,connections,client,confirmation);
        UUID other=UUID.randomUUID();when(orders.rateOnlyContext(tenant,other,nativeOrder)).thenThrow(new IllegalArgumentException("Not in store"));assertThatThrownBy(()->service.sandboxRates(tenant,other,nativeOrder,in,"unit")).isInstanceOf(IllegalArgumentException.class);verify(rates,never()).quote(eq(tenant),eq(other),any(),any(),any());
    }
    @Test void shippedOrderStopsBeforeAnyRateNetwork(){
        String nativeOrder=UUID.randomUUID().toString();var rates=mock(FedExSandboxRates.class);ReflectionTestUtils.setField(service,"sandboxRates",rates);when(orders.rateOnlyContext(tenant,store,nativeOrder)).thenThrow(new IllegalStateException("Shipped orders cannot request quotes."));
        assertThatThrownBy(()->service.sandboxRates(tenant,store,nativeOrder,new FedExShippingService.RateOnlyInput(sender,input(BigDecimal.ONE).parcel(),input(BigDecimal.ONE).recipient()),"unit")).isInstanceOf(IllegalStateException.class).hasMessageContaining("Shipped");verifyNoInteractions(rates,repository,client,confirmation);
    }
    FedExShippingService.QuoteInput input(BigDecimal weight){return new FedExShippingService.QuoteInput(carrier,sender,new FedExPayloads.Address("Synthetic Case Two","9015550100","20 Test Street","Collierville","TN","38017","US",false),new FedExPayloads.Parcel(BigDecimal.valueOf(12),BigDecimal.TEN,BigDecimal.valueOf(8),weight),false,true);}
    @Test void sharedRateClaimArchiveReprintAndPackingFlowNeedsNoCarrierCredentials()throws Exception{
        var quote=service.quote(tenant,store,order,input(BigDecimal.valueOf(3)),"local-unit-test");
        assertThat(quote.simulated()).isTrue();assertThat(quote.rates()).hasSize(1);assertThat(quote.preferredService()).isNull();assertThat(quote.rates().getFirst().amount()).isEqualByComparingTo("12.80");
        var purchased=service.purchase(tenant,store,order,quote.shipmentId(),"FEDEX_GROUND");assertThat(purchased.tracking()).startsWith("SIMULATED-");
        assertThat(service.purchase(tenant,store,order,quote.shipmentId(),"FEDEX_GROUND").id()).isEqualTo(purchased.id());
        verify(client,times(1)).ship(any(),any(),anyLong(),any(),any(),any());verifyNoInteractions(confirmation);
        var pdf=service.label(tenant,store,order,purchased.id(),null);
        try(var document=Loader.loadPDF(pdf)){var text=new PDFTextStripper().getText(document);assertThat(text).contains("SIMULATED - NOT FOR SHIPPING","Synthetic Case Two","20 Test Street","FEDEX_GROUND","TEST-EAx3");assertThat(text).doesNotContain("Tracking number");}
        assertThat(service.label(tenant,store,order,purchased.id(),null)).isNotEmpty();
    }
    @Test void multiLotEvidenceDoesNotCreateAllocationPagesAndFullSkuSurvives()throws Exception{
        lines.clear();var allocations=new ArrayList<CarrierLabelComposer.Allocation>();for(int i=0;i<16;i++)allocations.add(new CarrierLabelComposer.Allocation("TEST-ITEM-"+i,BigDecimal.ONE,java.time.LocalDate.of(2027,6,1).plusDays(i),"TEST-A"));
        lines.add(new CarrierLabelComposer.Line("TEST BRAND","LOCAL-TEST-VERY-LONG-SKU-PEPPERONI-MULTIPACK-EAx12","Long synthetic packing fixture",16,allocations));
        var quote=service.quote(tenant,store,order,input(BigDecimal.valueOf(3)),"local-unit-test");service.purchase(tenant,store,order,quote.shipmentId(),"FEDEX_GROUND");
        try(var doc=Loader.loadPDF(service.label(tenant,store,order,quoteId,null))){assertThat(doc.getNumberOfPages()).isEqualTo(1);for(int i=1;i<=doc.getNumberOfPages();i++){var text=new PDFTextStripper();text.setStartPage(i);text.setEndPage(i);assertThat(text.getText(doc)).contains("SIMULATED - NOT FOR SHIPPING");}}
    }
    @Test void recipientEditIsFixtureOnlyAndDoesNotWriteProviderOrInventory(){
        var recipient=input(BigDecimal.ONE).recipient();service.saveRecipient(tenant,store,order,recipient);
        verify(fixtures).saveRecipient(tenant,store,order,recipient);
        assertThat(service.context(tenant,store,order).recipient().recipient().name()).isEqualTo("Local Test Recipient");
        assertThatThrownBy(()->service.saveRecipient(tenant,store,"REAL-ORDER",recipient)).hasMessageContaining("only explicitly");
        ReflectionTestUtils.setField(service,"simulation",false);
        assertThatThrownBy(()->service.saveRecipient(tenant,store,order,recipient)).hasMessageContaining("unavailable");
        verifyNoInteractions(confirmation);verify(repository,never()).purchased(any(),any(),anyString(),any(),any());
    }
    @Test void newlyChosenPakOverridesSourceMediumButNeverRewritesArchivedPacking()throws Exception{
        lines.clear();var source=CarrierLabelComposer.Line.mapped("Brand","000001-EAx3","Product",1,List.of(new BigDecimal("3")),"Medium",List.of());lines.add(source);
        var oldArchive=json.valueToTree(List.of(source));var ordinary=input(BigDecimal.ONE);
        var selected=new FedExShippingService.QuoteInput(carrier,sender,ordinary.recipient(),new FedExPayloads.Parcel(null,null,null,BigDecimal.ONE,"PAK_PADDED"),false,true);
        var quote=service.quote(tenant,store,order,selected,"unit");service.purchase(tenant,store,order,quote.shipmentId(),"FEDEX_GROUND");
        try(var doc=Loader.loadPDF(service.label(tenant,store,order,quoteId,null))){assertThat(new PDFTextStripper().getText(doc)).contains("Pak Padded").doesNotContain(" - Medium");}
        assertThat(source.packageName()).isEqualTo("Medium");assertThat(oldArchive.path(0).path("packageName").asText()).isEqualTo("Medium");assertThat(shipment.get().packing().path(0).path("packageName").asText()).isEqualTo("Pak Padded");
    }
    @Test void packingOverflowStopsBeforeQuoteClaimOrLabelCreation(){
        lines.clear();for(int i=0;i<20;i++)lines.add(CarrierLabelComposer.Line.mapped("Brand","SKU-"+i,"Product",2,List.of(BigDecimal.TEN),"Medium",List.of()));
        assertThatThrownBy(()->service.quote(tenant,store,order,input(BigDecimal.ONE),"unit")).hasMessageContaining("No items were omitted");
        verify(repository,never()).quote(any(),any(),anyString(),any(),any(),anyList(),anyList(),anyString());
        verify(repository,never()).claim(any(),any(),anyString(),any(),anyString(),anyLong());verify(client,never()).ship(any(),any(),anyLong(),any(),any(),any());verifyNoInteractions(confirmation);
    }
    @Test void zeroWeightStopsBeforeRateOrQuote(){
        assertThatThrownBy(()->service.quote(tenant,store,order,input(BigDecimal.ZERO),"unit")).isInstanceOf(IllegalArgumentException.class);
        verify(client,never()).rates(any(),any(),anyLong(),any(),any(),any());verify(repository,never()).quote(any(),any(),anyString(),any(),any(),anyList(),anyList(),anyString());
    }
    @Test void realOrderAndRealConnectionCannotUseSimulation(){
        var existing=orders.context(tenant,store,order);when(orders.context(tenant,store,"REAL-ORDER")).thenReturn(existing);
        assertThatThrownBy(()->service.context(tenant,store,"REAL-ORDER")).hasMessageContaining("only explicitly created local fixture");
        when(connections.find(tenant,carrier)).thenReturn(new CarrierConnectionRepository.Connection(carrier,"Genuine","123456789","SANDBOX","PLATFORM",1,"ACTIVE",null,null));
        assertThatThrownBy(()->service.quote(tenant,store,order,input(BigDecimal.ONE),"unit")).hasMessageContaining("matching");verify(client,never()).rates(any(),any(),anyLong(),any(),any(),any());
    }
    @Test void simulatedArchiveCannotBeOpenedAsGenuineAndProductionHostRejected(){
        var genuine=new FedExClient(json,false);
        assertThatThrownBy(()->genuine.rates(tenant,carrier,1,FedExClient.Environment.PRODUCTION,new FedExClient.Credentials("local","local"),json.createObjectNode())).hasMessageContaining("disabled");
        assertThatThrownBy(()->client.rates(tenant,carrier,1,FedExClient.Environment.PRODUCTION,new FedExClient.Credentials("local","local"),json.createObjectNode())).hasMessageContaining("production");
        shipment.set(new FedExShipmentRepository.Shipment(quoteId,carrier,1,order,"SANDBOX","PURCHASED",json.createArrayNode(),json.createArrayNode(),"123",null,false));
        assertThatThrownBy(()->service.label(tenant,store,order,quoteId,null)).hasMessageContaining("matching mode");
    }
}
