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
  walmartMarketplace.disabled = amazonSelected;

  if (!amazonSelected) {
    amazonMarketplace.value = "";
    amazonSellerIdentifier.value = "";
  }
}
