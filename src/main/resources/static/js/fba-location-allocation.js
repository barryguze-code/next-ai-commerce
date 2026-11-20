/* Read-only split allocation preview; the server revalidates every receipt in one transaction. */
((root)=>{
 function allocate(review,selections){
  const remaining=new Map(),choices=[],details=new Map(),errors=[];
  for(const plan of review.plans)for(const lot of plan.shipment.stock)remaining.set(lot.layer,Math.min(remaining.get(lot.layer)??Infinity,Number(lot.available)));
  const initial=new Map(remaining);
  for(const plan of review.plans){const p=plan.shipment,totals=new Map();
   for(const line of p.lines){const products=review.presentation?.find(v=>v.sku===line.sku)?.products;
    if(!products?.length){errors.push('Mapping details are unavailable for '+line.sku+'.');continue;}
    for(const product of products){const key=p.shipment+'|'+line.sku+'|'+product.item,sources=p.stock.filter(l=>l.item===product.item),locationIds=[...new Set(sources.map(l=>l.location))],value=selections.get(key),required=Number(line.unitsToDeduct)*Number(product.eachPerSku),rows=Array.isArray(value)?value:[{location:value||(locationIds.length===1?locationIds[0]:''),quantity:required}],splits=[],used=[],seen=new Set();let error='',missingLocation=false,allocated=0;
     if(!Number.isSafeInteger(required)||required<0)error='Enter whole units.';
     for(const [index,row] of rows.entries()){
      const selected=row.location,quantity=Number(row.quantity),lots=sources.filter(l=>l.location===selected),available=lots.reduce((n,l)=>n+(remaining.get(l.layer)||0),0),detail={lots,used:[],available,sourceAvailable:lots.reduce((n,l)=>n+(initial.get(l.layer)||0),0),required:quantity,index};
      if(required===0&&rows.length===1&&quantity===0){splits.push(detail);continue;}
      if(!selected){detail.error='Select a location';missingLocation=true;}
      else if(!locationIds.includes(selected))detail.error='Select an available location.';
      else if(seen.has(selected))detail.error='This location is already selected.';
      else if(String(row.quantity).trim()===''||!Number.isSafeInteger(quantity)||quantity<=0)detail.error='Enter positive whole eaches.';
      seen.add(selected);allocated+=Number.isSafeInteger(quantity)?quantity:0;
      if(!detail.error){let left=quantity;for(const lot of lots){const take=Math.min(left,remaining.get(lot.layer)||0);if(take>0){if(!Number.isSafeInteger(take)){detail.error='Review whole-unit receipt stock.';break;}totals.set(lot.layer,(totals.get(lot.layer)||0)+take);remaining.set(lot.layer,remaining.get(lot.layer)-take);left-=take;detail.used.push({lot,quantity:take});used.push({lot,quantity:take});}}if(left>0)detail.error='Not enough stock at '+(lots[0]?.locationName||'selected location')+'. Available: '+available+' each; required: '+quantity+' each.';}
      if(detail.error)error=error||detail.error;splits.push(detail);
     }
     if(!error&&allocated!==required)error='Allocate exactly '+required+' each.';
     const lots=splits.flatMap(s=>s.lots),detail={splits,used,lots,required,allocated,missingLocation,error,available:splits[0]?.available||0,sourceAvailable:splits[0]?.sourceAvailable||0};details.set(key,detail);if(error)errors.push(error);
    }
   }choices.push({shipment:p.shipment,allocations:[...totals].map(([layer,quantity])=>({layer,quantity}))});
  }
  for(const detail of details.values()){detail.remaining=detail.lots.reduce((n,l)=>n+(remaining.get(l.layer)||0),0);for(const split of detail.splits)split.remaining=split.lots.reduce((n,l)=>n+(remaining.get(l.layer)||0),0);}
  return {choices,details,errors:[...new Set(errors)]};
 }
 root.FbaLocationAllocation={allocate};if(typeof module!=='undefined')module.exports={allocate};
})(typeof window==='undefined'?globalThis:window);
