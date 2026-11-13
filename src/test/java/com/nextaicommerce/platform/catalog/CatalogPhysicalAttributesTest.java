package com.nextaicommerce.platform.catalog;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class CatalogPhysicalAttributesTest {
 private CatalogRepository.PhysicalAttributes values(String pack,String weight,String unit,String length,String dimension){return new CatalogRepository.PhysicalAttributes(new BigDecimal(pack),weight==null?null:new BigDecimal(weight),unit,length==null?null:new BigDecimal(length),null,null,dimension);}
 @Test void masterCaseMustBeWhole(){assertThatCode(()->values("4.0000",null,"oz",null,"in").validate()).doesNotThrowAnyException();assertThatThrownBy(()->values("4.5",null,"oz",null,"in").validate()).isInstanceOf(IllegalArgumentException.class).hasMessage("Units / case must be a whole number.");}
 @Test void optionalMeasurementsRemainOptional(){assertThatCode(()->values("12",null,"oz",null,"in").validate()).doesNotThrowAnyException();}
 @Test void explicitMetricAndImperialMeasurementsAreAccepted(){assertThatCode(()->values("6","2.2500","lb","12.1250","in").validate()).doesNotThrowAnyException();assertThatCode(()->values("6","250","g","30.8","cm").validate()).doesNotThrowAnyException();}
 @Test void rejectsInvalidUnitsAndNonpositiveQuantities(){assertThatThrownBy(()->values("0",null,"oz",null,"in").validate()).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->values("6","-2","oz",null,"in").validate()).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->values("6","2","stone",null,"in").validate()).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->values("6",null,"oz","2","ft").validate()).isInstanceOf(IllegalArgumentException.class);}
 @Test void legacyWeightIsOnlyRecognizedForAnExplicitWeightUnit(){var v=CatalogRepository.physicalValues(BigDecimal.ONE,null,null,null,null,null,null,"12.5","OZ");assertThat(v.netWeight()).isEqualByComparingTo("12.5");assertThat(v.weightUnit()).isEqualTo("oz");assertThat(CatalogRepository.physicalValues(BigDecimal.ONE,null,null,null,null,null,null,"12","EA").netWeight()).isNull();}
 @Test void rejectsPrecisionThatCannotBeStored(){assertThatThrownBy(()->values("6","2.12345","oz",null,"in").validate()).isInstanceOf(IllegalArgumentException.class);}
}
