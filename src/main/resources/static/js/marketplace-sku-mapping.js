(() => {
  const timers = new WeakMap();
  const cache = new Map();
  let createTarget = null;

  const resultsFor = input => input.parentElement.querySelector('.sku-product-results');
  const hiddenFor = input => input.parentElement.querySelector('input[name="productId"]');
  const labelFor = product => [product.vendorItemCode, product.name, product.accountSku]
    .filter(Boolean).join(' · ');
  const setMappingPicture=(line,product)=>window.NextAiMappingWidget.picture(line,product);

  const openQuickCreate = input => {
    const drawer = document.getElementById('catalog-quick-create');
    if (!drawer) return;
    createTarget = input;
    const form = drawer.querySelector('form');
    form.reset();
    form.querySelector('[name="vendorId"]').disabled = false;
    form.querySelector('[data-new-vendor]').hidden = true;
    form.querySelector('[data-new-vendor-toggle]').textContent = '＋ New vendor';
    form.querySelectorAll('[data-new-vendor] input').forEach(field => field.required = false);
    form.querySelector('[data-unit-cost]').hidden = true;
    form.querySelector('[name="unitCost"]').required = false;
    form.querySelector('[data-create-feedback]').hidden = true;
    const query = input.value.trim();
    const looksLikeCode = /\d/.test(query) && /^[a-z0-9._/-]+$/i.test(query);
    if (looksLikeCode) form.querySelector('[name="accountSku"]').value = query;
    else form.querySelector('[name="name"]').value = query;
    const sellerSku = input.closest('.sku-mapping-dialog')?.querySelector('.mapping-dialog-header h2')?.textContent?.trim();
    form.querySelector('[data-create-context]').textContent = sellerSku
      ? `Creating for ${sellerSku}. Your mapping stays open behind this panel.`
      : 'Your mapping stays open behind this panel.';
    drawer.showModal();
    window.setTimeout(() => form.querySelector(query && !looksLikeCode ? '[name="accountSku"]' : '[name="name"]').focus(), 80);
  };

  window.searchMappingProduct = input => {
    hiddenFor(input).value = '';
    setMappingPicture(input.closest('.mapping-product-line'),null);
    input.setCustomValidity('Choose a product from the account catalogue.');
    clearTimeout(timers.get(input));
    const results = resultsFor(input);
    const query = input.value.trim();
    if (query.length < 2) { results.replaceChildren(); return; }
    timers.set(input, setTimeout(async () => {
      try {
        let products = cache.get(query.toLowerCase());
        if (!products) {
          const response = await fetch('/app/catalog/product-search?q=' + encodeURIComponent(query), {
            headers: { Accept: 'application/json' }
          });
          if (!response.ok) throw new Error('Search unavailable');
          products = await response.json();
          cache.set(query.toLowerCase(), products);
          if (cache.size > 40) cache.delete(cache.keys().next().value);
        }
        if (input.value.trim() !== query) return;
        results.replaceChildren(...products.map(product => {
          const option = document.createElement('button');
          option.type = 'button';
          option.innerHTML = '<strong></strong><small></small>';
          window.NextAiMappingWidget.option(option,product);
          option.querySelector('strong').textContent = product.name;
          option.querySelector('small').textContent = [product.brand, product.vendorItemCode, product.identifier]
            .filter(Boolean).join(' · ');
          option.addEventListener('click', () => {
            input.value = labelFor(product);
            hiddenFor(input).value = product.id;
            input.setCustomValidity('');
            setMappingPicture(input.closest('.mapping-product-line'), product);
            results.replaceChildren();
          });
          return option;
        }));
        if (!products.length) {
          const empty = document.createElement('div');
          empty.className = 'sku-product-result-empty';
          empty.innerHTML = '<span><strong>No catalogue match</strong><small>Add it without closing this mapping.</small></span>';
          if (document.getElementById('catalog-quick-create')) {
            const create = document.createElement('button');
            create.type = 'button';
            create.textContent = '＋ Create product';
            create.addEventListener('click', () => openQuickCreate(input));
            empty.append(create);
          }
          results.replaceChildren(empty);
        }
      } catch (_) {
        results.replaceChildren();
      }
    }, 180));
  };

  window.addMappingLine = button => {
    const lines = button.closest('.dialog-body').querySelector('.mapping-lines');
    lines.append(document.getElementById('mapping-product-template').content.cloneNode(true));
    setMappingPicture(lines.lastElementChild, null);
    lines.lastElementChild.querySelector('input[type="search"]').focus();
  };

  window.removeMappingLine = button => {
    const lines = button.closest('.mapping-lines');
    if (lines.children.length === 1) {
      const line = button.closest('.mapping-product-line');
      line.querySelector('input[type="search"]').value = '';
      line.querySelector('input[name="productId"]').value = '';
      line.querySelector('input[name="quantity"]').value = '1';
      line.querySelector('.sku-product-results').replaceChildren();
      return;
    }
    button.closest('.mapping-product-line').remove();
  };

  window.validateMappingForm = form => {
    const missing = [...form.querySelectorAll('input[name="productId"]')]
      .find(input => !input.value);
    if (missing) {
      const search = missing.parentElement.querySelector('input[type="search"]');
      search.setCustomValidity('Choose a product from the account catalogue.');
      search.reportValidity();
      return false;
    }
    return form.checkValidity();
  };

  document.addEventListener('click', event => {
    if (!event.target.closest('.mapping-product-line')) {
      document.querySelectorAll('.sku-product-results').forEach(results => results.replaceChildren());
    }
  });

  document.querySelectorAll('.sku-mapping-dialog').forEach(dialog => dialog.addEventListener('click', event => {
    if (event.target === dialog) dialog.close();
  }));
  document.querySelectorAll('.mapping-product-line').forEach(line => setMappingPicture(line, null));

  const createDrawer = document.getElementById('catalog-quick-create');
  if (createDrawer) {
    const form = createDrawer.querySelector('form');
    const vendor = form.querySelector('[data-catalog-vendor]');
    const newVendor = form.querySelector('[data-new-vendor]');
    const unitCostLabel = form.querySelector('[data-unit-cost]');
    const updateVendorFields = () => {
      const usesVendor = vendor.disabled || Boolean(vendor.value);
      unitCostLabel.hidden = !usesVendor;
      unitCostLabel.querySelector('input').required = usesVendor;
    };
    form.querySelector('[data-new-vendor-toggle]').addEventListener('click', event => {
      const opening = newVendor.hidden;
      newVendor.hidden = !opening;
      vendor.disabled = opening;
      if (opening) vendor.value = '';
      newVendor.querySelector('[name="newVendorName"]').required = opening;
      newVendor.querySelector('[name="newVendorCurrency"]').required = opening;
      event.currentTarget.textContent = opening ? 'Use an existing vendor' : '＋ New vendor';
      updateVendorFields();
      if (opening) newVendor.querySelector('[name="newVendorName"]').focus();
    });
    vendor.addEventListener('change', updateVendorFields);
    form.querySelectorAll('[data-create-close]').forEach(button => button.addEventListener('click', () => createDrawer.close()));
    createDrawer.addEventListener('click', event => { if (event.target === createDrawer) createDrawer.close(); });
    form.addEventListener('submit', async event => {
      event.preventDefault();
      if (!form.reportValidity()) return;
      const submit = form.querySelector('[data-create-submit]');
      const feedback = form.querySelector('[data-create-feedback]');
      const original = submit.textContent;
      submit.disabled = true; submit.textContent = 'Adding…'; feedback.hidden = true; feedback.classList.remove('error');
      try {
        const response = await fetch('/app/catalog/mapping-product-options', {method:'POST', body:new FormData(form), headers:{Accept:'application/json'}});
        const product = await response.json();
        if (!response.ok) throw new Error(product.error || 'The catalogue item could not be added.');
        if (!createTarget?.isConnected) throw new Error('The original mapping is no longer open. Search for the new product to select it.');
        createTarget.value = labelFor(product);
        hiddenFor(createTarget).value = product.id;
        createTarget.setCustomValidity('');
        setMappingPicture(createTarget.closest('.mapping-product-line'), product);
        resultsFor(createTarget).replaceChildren();
        if (product.vendorId && !vendor.querySelector(`option[value="${CSS.escape(product.vendorId)}"]`)) {
          const option = new Option(product.vendorName, product.vendorId);
          vendor.add(option);
        }
        cache.clear();
        createDrawer.close();
        createTarget.closest('.mapping-product-line').querySelector('[name="quantity"]').focus();
      } catch (problem) {
        feedback.textContent = problem.message;
        feedback.classList.add('error'); feedback.hidden = false;
      } finally { submit.disabled = false; submit.textContent = original; }
    });
  }
})();
