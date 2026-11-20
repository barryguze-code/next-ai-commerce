package com.nextaicommerce.platform.marketplace;

import java.util.*;
import java.util.regex.Pattern;

/** Only explicit retail multipacks count; pieces, servings, ounces and dimensions do not. */
public final class BarcodePackRules {
 private BarcodePackRules(){}
 private static final Pattern PACK=Pattern.compile("(?i)\\bpack\\s+of\\s+(\\d{1,4})\\b|\\b(\\d{1,4})\\s*[- ]?packs?\\b");
 public static Integer quantity(String title){
  if(title==null||title.isBlank())return null;
  String text=title.toLowerCase(Locale.ROOT);
  if(Pattern.compile("\\b(variety|assorted|bundle|combo|case|twin|triple|count|ct)\\b|\\b\\d+\\s*[x×]\\s*\\d+").matcher(text).find())return null;
  var matcher=PACK.matcher(title);Set<Integer> counts=new HashSet<>();
  while(matcher.find())counts.add(Integer.parseInt(matcher.group(1)==null?matcher.group(2):matcher.group(1)));
  if(counts.size()>1)return null;
  if(counts.isEmpty())return text.contains("pack")?null:1;
  int count=counts.iterator().next();return count>0&&count<=100?count:null;
 }
 /** Normalize equivalent UPC/EAN encodings, without accepting ASINs or invalid checksums. */
 public static String gtin(String value){
  if(value==null)return null;
  String digits=value.trim();if(!digits.matches("(?:[0-9]{8}|[0-9]{12}|[0-9]{13}|[0-9]{14})")||digits.matches("0+"))return null;
  int sum=0;for(int i=digits.length()-2,weight=3;i>=0;i--,weight=4-weight)sum+=(digits.charAt(i)-'0')*weight;
  if((10-sum%10)%10!=digits.charAt(digits.length()-1)-'0')return null;
  return "0".repeat(14-digits.length())+digits;
 }
}
