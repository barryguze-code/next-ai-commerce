package com.nextaicommerce.platform.catalog;
import org.junit.jupiter.api.Test;
import java.nio.file.*;import java.util.*;
import org.thymeleaf.context.Context;import org.thymeleaf.spring6.SpringTemplateEngine;import org.thymeleaf.templateresolver.StringTemplateResolver;
import static org.junit.jupiter.api.Assertions.*;
class CatalogCaseMappingTemplateTest {
 @Test void optionalMasterCaseFieldsRenderRealHeaderMappingsAndExplicitUnits() throws Exception {
  String source=Files.readString(Path.of("src/main/resources/templates/catalog-import.html"));int start=source.indexOf("<fieldset class=\"master-case-mapping\"");String fragment=source.substring(start,source.indexOf("</fieldset>",start)+11);
  var context=new Context();context.setVariable("catalogImport",Map.of("headers",List.of("Description","Master Case Length","Master Case Width","Master Case Height","Case Weight","Dimension Unit","Weight Unit"),"mapping",Map.of("caseLength","Master Case Length","caseWidth","Master Case Width","caseHeight","Master Case Height","caseWeight","Case Weight","caseDimensionUnitDefault","in","caseWeightUnitDefault","lb")));
  var engine=new SpringTemplateEngine();engine.setTemplateResolver(new StringTemplateResolver());String result=engine.process(fragment,context);
  assertTrue(result.contains("Master case length"));assertFalse(result.contains("th:each"));assertTrue(result.contains("value=\"Master Case Length\" selected=\"selected\""));assertTrue(result.contains("value=\"in\" selected=\"selected\""));assertTrue(result.contains("Existing shared product measurements are retained"));
  Files.writeString(Path.of("../recovery/catalog-master-case-component.html"),result);
 }
}
