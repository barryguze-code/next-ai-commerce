package com.nextaicommerce.platform.fba;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;import java.util.*;import java.math.*;import static com.nextaicommerce.platform.fba.FbaPackingManifest.*;
class FbaPackingManifestTest {
 UUID origin=UUID.randomUUID();Carton box(boolean confirmed,Map<String,Integer> contents){return new Carton(UUID.randomUUID(),origin,"02. PAK",BigDecimal.TEN,BigDecimal.TEN,BigDecimal.ONE,"in",BigDecimal.ONE,"lb",confirmed,contents);}
 @Test void completeActualCartonPassesWithoutChangingCatalog(){assertDoesNotThrow(()->validate(List.of(box(true,Map.of("SKU",4))),Map.of(origin+":SKU",4),Set.of("02. PAK")));}
 @Test void estimatesAreNotActualMeasurements(){assertThrows(IllegalArgumentException.class,()->validate(List.of(box(false,Map.of("SKU",4))),Map.of(origin+":SKU",4),Set.of("02. PAK")));}
 @Test void noCarrierCodeSubstitutionOrIncompletePacking(){assertThrows(IllegalArgumentException.class,()->validate(List.of(box(true,Map.of("SKU",4))),Map.of(origin+":SKU",4),Set.of("FEDEX_PAK")));assertThrows(IllegalArgumentException.class,()->validate(List.of(box(true,Map.of("SKU",3))),Map.of(origin+":SKU",4),Set.of("02. PAK")));}
 @Test void separateOriginsNeverShareCartonContents(){assertThrows(IllegalArgumentException.class,()->validate(List.of(box(true,Map.of("SKU",4))),Map.of(UUID.randomUUID()+":SKU",4),Set.of("02. PAK")));}
}
