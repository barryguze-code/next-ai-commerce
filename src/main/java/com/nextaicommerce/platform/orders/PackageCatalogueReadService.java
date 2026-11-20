package com.nextaicommerce.platform.orders;

import com.nextaicommerce.platform.config.PlatformReadCache;
import com.nextaicommerce.platform.profit.ProfitCostRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Display metadata only. Authorization and every package write still use fresh checks. */
@Service
public class PackageCatalogueReadService {
 private final PackingPackageNames names;private final ProfitCostRepository costs;private final PlatformReadCache cache;
 public PackageCatalogueReadService(PackingPackageNames names,ProfitCostRepository costs,PlatformReadCache cache){this.names=names;this.costs=costs;this.cache=cache;}
 public List<PackingPackageNames.Choice> choices(UUID account,LocalDate date){
  return cache.get(account,"managed-package-choices",date,()->List.copyOf(names.choices(account,costs.rates(account),date)));
 }
}
