package com.nextaicommerce.platform.catalog;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
class CatalogCaseMeasurementsTest {
 final Map<String,String> mapping=Map.of("caseLength","L","caseWidth","W","caseHeight","H","caseWeight","Weight","caseDimensionUnit","DU","caseWeightUnit","WU");
 @Test void explicitCaseMeasurementsPreserveOriginalUnitsAndMagnitude(){var m=CatalogImportService.caseMeasurements(Map.of("L","20.25","W","12","H","8","Weight","18.5","DU","inches","WU","lbs"),mapping);assertEquals(new BigDecimal("20.25"),m.length());assertEquals(new BigDecimal("18.5"),m.weight());assertEquals("in",m.dimensionUnit());assertEquals("lb",m.weightUnit());}
 @Test void absentMeasurementsDoNotManufactureDefaultsOrEraseExisting(){var m=CatalogImportService.caseMeasurements(Map.of(),Map.of("caseDimensionUnitDefault","in","caseWeightUnitDefault","lb"));assertNull(m.length());assertNull(m.width());assertNull(m.height());assertNull(m.dimensionUnit());assertNull(m.weight());assertNull(m.weightUnit());}
 @Test void missingOrInvalidUnitsNeedUserReview(){assertThrows(IllegalArgumentException.class,()->CatalogImportService.caseMeasurements(Map.of("L","5"),mapping));assertThrows(IllegalArgumentException.class,()->CatalogImportService.caseMeasurements(Map.of("Weight","2","WU","stone"),mapping));}
 @Test void partialDimensionsRetainExplicitUnitWithoutInferringOtherAxes(){var m=CatalogImportService.caseMeasurements(Map.of("L","12","DU","cm"),mapping);assertEquals(new BigDecimal("12"),m.length());assertNull(m.width());assertNull(m.height());assertEquals("cm",m.dimensionUnit());}
 @Test void negativeInvalidAndExcessPrecisionFailBeforeImport(){for(String raw:List.of("0","-1","word","1.12345","10000000000"))assertThrows(IllegalArgumentException.class,()->CatalogImportService.caseMeasurements(Map.of("L",raw,"DU","in"),mapping));}
 @Test void explicitReviewUnitAppliesOnlyWhenSourceUnitIsAbsent(){var fields=new HashMap<>(mapping);fields.put("caseDimensionUnitDefault","in");assertEquals("in",CatalogImportService.caseMeasurements(Map.of("L","8"),fields).dimensionUnit());assertEquals("cm",CatalogImportService.caseMeasurements(Map.of("L","8","DU","cm"),fields).dimensionUnit());assertThrows(IllegalArgumentException.class,()->CatalogImportService.caseMeasurements(Map.of("L","8","DU","bad"),fields));}
}
