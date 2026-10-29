function selectChannel(channel) {
  const amazonField = document.getElementById("amazon-marketplace-field");
  const amazonMarketplace = document.getElementById("new-marketplace");
  const amazonSellerField = document.getElementById("amazon-seller-field");
  const amazonSellerIdentifier = document.getElementById("new-seller-identifier");
  const walmartMarketplace = document.getElementById("walmart-marketplace");
  const amazonSelected = channel === "AMAZON";

  amazonField.hidden = !amazonSelected;
  amazonSellerField.hidden = !amazonSelected;
  amazonMarketplace.disabled = !amazonSelected;
  amazonMarketplace.required = amazonSelected;
  amazonSellerIdentifier.disabled = !amazonSelected;
  amazonSellerIdentifier.required = amazonSelected;
  walmartMarketplace.disabled = channel !== "WALMART";
  const shopify = channel === "SHOPIFY";
  document.getElementById("shopify-add-fields").hidden = !shopify;
  const domain = document.getElementById("shopify-marketplace");
  domain.disabled = !shopify;domain.required = shopify;
  document.getElementById("add-connection-form").action = shopify ? "/app/connections/shopify" : "/app/connections";

  if (!amazonSelected) {
    amazonMarketplace.value = "";
    amazonSellerIdentifier.value = "";
  }
}

function openShopifySetup(button) {
  const dialog=document.getElementById('shopify-connection-dialog');
  const form=document.getElementById('shopify-credential-form');
  form.reset();form.action='/app/connections/'+button.dataset.id+'/shopify/credentials';
  document.getElementById('shopify-store-name').textContent=button.dataset.name;
  document.getElementById('shopify-domain').textContent=button.dataset.shop;
  const verify=document.getElementById('shopify-verify-form');
  verify.action='/app/connections/'+button.dataset.id+'/shopify/verify';
  verify.hidden=button.dataset.stored!=='true';const permissions=document.getElementById('shopify-permissions-form');if(permissions){permissions.action='/app/connections/'+button.dataset.id+'/shopify/permissions';permissions.hidden=button.dataset.stored!=='true';}dialog.showModal();
}
for(const id of ['shopify-credential-form','shopify-verify-form','shopify-permissions-form']) {
  document.getElementById(id)?.addEventListener('submit',event=>{
    const button=event.currentTarget.querySelector('[type=submit]');button.disabled=true;button.textContent='Verifying…';
  });
}
const retry=document.getElementById('shopify-connection-dialog')?.dataset.retryId;
if(retry){const button=[...document.querySelectorAll('[data-channel="SHOPIFY"][data-id]')].find(b=>b.dataset.id===retry);if(button)openShopifySetup(button);}
