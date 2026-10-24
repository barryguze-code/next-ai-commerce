package com.nextaicommerce.platform.fees;
import jakarta.servlet.http.HttpSession;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
@ControllerAdvice(assignableTypes={com.nextaicommerce.platform.orders.OrderController.class,com.nextaicommerce.platform.marketplace.MarketplaceSkuController.class})
public class FeeVarianceAdvice {
 private final AmazonFeeVarianceRepository fees;
 public FeeVarianceAdvice(org.springframework.beans.factory.ObjectProvider<AmazonFeeVarianceRepository> fees){this.fees=fees.getIfAvailable();}
 @ModelAttribute void attributes(HttpSession session,Model model){
  model.addAttribute("feeVarianceEnabled",fees!=null&&fees.visible());
 }
}
