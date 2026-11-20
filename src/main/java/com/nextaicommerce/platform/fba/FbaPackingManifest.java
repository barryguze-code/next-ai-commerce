package com.nextaicommerce.platform.fba;
import java.math.*;import java.util.*;
/** Actual cartons are authoritative. Unit/case estimates never become packed measurements. */
public final class FbaPackingManifest {
 private FbaPackingManifest(){}
 public record Carton(UUID id,UUID warehouse,String packageName,BigDecimal length,BigDecimal width,BigDecimal height,String dimensionUnit,BigDecimal weight,String weightUnit,boolean actualConfirmed,Map<String,Integer> contents,Map<String,java.time.LocalDate> expirations){
  public Carton(UUID id,UUID warehouse,String packageName,BigDecimal length,BigDecimal width,BigDecimal height,String dimensionUnit,BigDecimal weight,String weightUnit,boolean actualConfirmed,Map<String,Integer> contents){this(id,warehouse,packageName,length,width,height,dimensionUnit,weight,weightUnit,actualConfirmed,contents,Map.of());}
 }
 public static void validate(List<Carton> cartons,Map<String,Integer> expected,Set<String> managedNames){
  if(cartons==null||cartons.isEmpty()||cartons.size()>1000)throw new IllegalArgumentException("Enter between 1 and 1,000 packed cartons.");var seen=new HashSet<UUID>();var actual=new HashMap<String,Integer>();
  for(var c:cartons){if(c==null||c.id()==null||!seen.add(c.id())||c.warehouse()==null||c.packageName()==null||!managedNames.contains(c.packageName()))throw new IllegalArgumentException("Choose an account-defined package and source warehouse.");
   if(!c.actualConfirmed()||!positive(c.length())||!positive(c.width())||!positive(c.height())||!positive(c.weight())||!Set.of("in","cm").contains(c.dimensionUnit())||!Set.of("lb","kg","oz","g").contains(c.weightUnit()))throw new IllegalArgumentException("Confirm actual carton length, width, height and packed weight.");
   if(c.expirations()!=null&&c.expirations().keySet().stream().anyMatch(sku->c.contents()==null||!c.contents().containsKey(sku)))throw new IllegalArgumentException("Carton expirations must belong to its actual SKU contents.");
   if(c.contents()==null||c.contents().isEmpty()||c.contents().size()>500)throw new IllegalArgumentException("Specify complete SKU units in each carton.");for(var line:c.contents().entrySet()){if(line.getKey()==null||line.getValue()==null||line.getValue()<1||line.getValue()>1000000)throw new IllegalArgumentException("Use positive whole SKU units.");actual.merge(c.warehouse()+":"+line.getKey(),line.getValue(),Math::addExact);}
  }
  if(!actual.equals(expected))throw new IllegalArgumentException("Packed SKU quantities must exactly match the confirmed allocation at each origin.");
 }
 static boolean positive(BigDecimal n){return n!=null&&n.signum()>0&&n.compareTo(new BigDecimal("100000"))<=0;}
}
