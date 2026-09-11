(() => {
  const search = document.getElementById('uninvoiced-product-search');
  const productId = document.getElementById('uninvoiced-product-id');
  const results = document.getElementById('uninvoiced-product-results');
  const selection = document.getElementById('uninvoiced-product-selection');
  const expiration = document.getElementById('uninvoiced-expiration');
  const expirationHelp = document.getElementById('uninvoiced-expiration-help');
  const cache = new Map();
  let timer;

  const clearResults = () => results?.replaceChildren();
  const productLabel = product => [product.name, product.brand, product.vendorItemCode || product.identifier]
    .filter(Boolean).join(' · ');

  if (search) search.addEventListener('input', () => {
    productId.value = '';
    search.setCustomValidity('Choose a product from the account catalogue.');
    selection.textContent = 'Choose a product from the account catalogue.';
    expiration.required = false;
    expirationHelp.textContent = 'Required only for expiration-tracked products.';
    clearTimeout(timer);
    clearResults();
    const query = search.value.trim();
    if (query.length < 2) return;
    timer = window.setTimeout(async () => {
      try {
        let products = cache.get(query.toLowerCase());
        if (!products) {
          const response = await fetch('/app/catalog/product-search?q=' + encodeURIComponent(query), {
            headers: { Accept: 'application/json' }
          });
          if (!response.ok) throw new Error();
          products = await response.json();
          cache.set(query.toLowerCase(), products);
          if (cache.size > 30) cache.delete(cache.keys().next().value);
        }
        if (search.value.trim() !== query) return;
        results.replaceChildren(...products.map(product => {
          const option = document.createElement('button');
          option.type = 'button';
          option.innerHTML = '<strong></strong><small></small>';
          option.querySelector('strong').textContent = product.name;
          option.querySelector('small').textContent = [product.brand, product.accountSku,
            product.vendorItemCode, product.identifier].filter(Boolean).join(' · ');
          option.addEventListener('click', () => {
            search.value = productLabel(product);
            productId.value = product.id;
            search.setCustomValidity('');
            selection.textContent = product.accountSku
              ? 'Selected · ' + product.accountSku
              : 'Selected · Marketplace SKU not required';
            expiration.required = product.expirationRequired === true;
            expirationHelp.textContent = expiration.required
              ? 'Required for this expiration-tracked product.'
              : 'Optional; leave blank for FIFO inventory.';
            clearResults();
          });
          return option;
        }));
        if (!products.length) {
          const empty = document.createElement('span');
          empty.textContent = 'No matching account catalogue product';
          results.replaceChildren(empty);
        }
      } catch (_) {
        const error = document.createElement('span');
        error.textContent = 'Product search is temporarily unavailable';
        results.replaceChildren(error);
      }
    }, 180);
  });

  window.validateUninvoicedReceipt = form => {
    if (!productId?.value) {
      search.setCustomValidity('Choose a product from the account catalogue.');
      search.reportValidity();
      return false;
    }
    return form.checkValidity();
  };

  window.openOutOfStockReceipt = product => {
    const dialog = document.getElementById('uninvoiced-receipt-dialog');
    if (!dialog || !product || !search || !productId) return;
    search.value = [product.name, product.brand, product.vendorItemCode || product.identifier]
      .filter(Boolean).join(' · ');
    productId.value = product.id;
    search.setCustomValidity('');
    selection.textContent = product.accountSku
      ? 'Selected · ' + product.accountSku
      : 'Selected · Marketplace SKU not required';
    expiration.required = product.expirationRequired === true;
    expirationHelp.textContent = expiration.required
      ? 'Required for this expiration-tracked product.'
      : 'Optional; leave blank for FIFO inventory.';
    clearResults();
    dialog.showModal();
    dialog.querySelector('input[name="quantity"]')?.focus();
  };

  const menu = document.getElementById('inventory-action-menu');
  const skuCache = new Map();
  const defaultCache = new Map();
  let menuTrigger;
  let menuContext = {};
  window.closeInventoryActionMenu = () => {
    if (!menu) return;
    menu.hidden = true;
    menuTrigger?.setAttribute('aria-expanded', 'false');
    menuTrigger = null;
  };
  window.openInventoryActionMenu = button => {
    if (!menu) return;
    if (menuTrigger === button && !menu.hidden) {
      window.closeInventoryActionMenu();
      return;
    }
    menuTrigger = button;
    menuContext = { ...button.dataset };
    document.getElementById('quick-action-item').value = button.dataset.item;
    document.getElementById('quick-action-location').value = button.dataset.location;
    document.getElementById('quick-action-location-code').value = button.dataset.locationCode;
    document.getElementById('quick-action-expiration').value = button.dataset.expiration;
    document.getElementById('quick-action-product').textContent = button.dataset.product;
    document.getElementById('quick-action-date').textContent = 'Expiration ' + button.dataset.expiration
      + (button.dataset.warningText ? ' · ' + button.dataset.warningText : '');
    document.getElementById('quick-action-clear').hidden = !button.dataset.action;
    document.getElementById('quick-action-clear-copy').textContent = button.dataset.actionLabel
      ? 'Clear “' + button.dataset.actionLabel + '” safely'
      : 'Review what will—and will not—change';
    menu.hidden = false;
    button.setAttribute('aria-expanded', 'true');
    positionActionMenu();
  };
  const positionActionMenu = () => {
    if (!menuTrigger || !menu || menu.hidden) return;
    const box = menuTrigger.getBoundingClientRect();
    if (box.bottom < 0 || box.top > window.innerHeight || box.right < 0 || box.left > window.innerWidth) {
      window.closeInventoryActionMenu();
      return;
    }
    const width = 282;
    const anchor = menuTrigger.hasAttribute('data-context-action') ? box.right + 8 : box.right - width;
    const left = Math.max(12, Math.min(anchor, window.innerWidth - width - 12));
    const height = menu.offsetHeight;
    const top = box.bottom + 8 + height < window.innerHeight
      ? box.bottom + 8
      : Math.max(12, box.top - height - 8);
    menu.style.left = left + 'px';
    menu.style.top = top + 'px';
  };

  const loadSkus = async itemId => {
    if (!skuCache.has(itemId)) skuCache.set(itemId, fetch('/app/catalog/products/' + encodeURIComponent(itemId) + '/marketplace-skus', {
      headers: { Accept: 'application/json' }
    }).then(response => { if (!response.ok) throw new Error(); return response.json(); }).catch(error => { skuCache.delete(itemId); throw error; }));
    return skuCache.get(itemId);
  };

  const loadSaleDefaults = async itemId => {
    if (!defaultCache.has(itemId)) defaultCache.set(itemId, fetch('/app/inventory/sale-plan-defaults?itemId=' + encodeURIComponent(itemId), {
      headers: { Accept: 'application/json' }
    }).then(response => { if (!response.ok) throw new Error(); return response.json(); }).catch(error => { defaultCache.delete(itemId); throw error; }));
    return defaultCache.get(itemId);
  };

  const skuMeta = sku => [sku.channel, sku.asin ? 'ASIN ' + sku.asin : '', sku.fulfillment || '',
    sku.marketplaceQuantity == null ? '' : sku.marketplaceQuantity + ' listed'].filter(Boolean).join(' · ');

  const renderSaleSkus = skus => {
    const list = document.getElementById('sale-sku-list');
    const base = document.getElementById('sale-discount').value || '10';
    if (!skus.length) {
      list.innerHTML = '<div class="operation-empty"><strong>No Marketplace SKU is mapped</strong><small>Map a SKU before saving a listing-specific sale plan.</small></div>';
      document.getElementById('sale-plan-submit').disabled = true;
      return;
    }
    list.replaceChildren(...skus.map(sku => {
      const row = document.createElement('article');
      row.className = 'operation-sku-row';
      row.dataset.sku = sku.sku;
      const selector = document.createElement('input');
      selector.type = 'checkbox'; selector.checked = true; selector.className = 'sku-selector';
      selector.setAttribute('aria-label', 'Include ' + sku.sku);
      const identity = document.createElement('div');
      identity.className = 'sku-operation-identity';
      const title = document.createElement('strong'); title.textContent = sku.sku;
      const meta = document.createElement('small'); meta.textContent = skuMeta(sku) || 'Mapped listing';
      identity.append(title, meta);
      const price = document.createElement('span');
      price.className = 'sku-current-price';
      price.textContent = sku.price == null ? 'Price not imported' : (sku.currency || 'USD') + ' ' + Number(sku.price).toFixed(2);
      const discount = document.createElement('label'); discount.className = 'sku-discount';
      const input = document.createElement('input'); input.type = 'number'; input.min = '.01'; input.max = '99.99';
      input.step = '.01'; input.value = base; input.setAttribute('aria-label', 'Discount for ' + sku.sku);
      input.addEventListener('input', () => { input.dataset.overridden = 'true'; });
      const unit = document.createElement('span'); unit.textContent = '%';
      discount.append(input, unit);
      selector.addEventListener('change', () => row.classList.toggle('not-selected', !selector.checked));
      row.append(selector, identity, price, discount);
      return row;
    }));
    document.getElementById('sale-select-all').textContent = 'Clear all';
    document.getElementById('sale-plan-submit').disabled = false;
  };

  const renderAwarenessSkus = skus => {
    const list = document.getElementById('disposition-sku-list');
    if (!skus.length) {
      list.innerHTML = '<div class="operation-empty"><strong>No related Marketplace SKUs</strong><small>The local inventory flag can still be saved.</small></div>';
      return;
    }
    list.replaceChildren(...skus.map(sku => {
      const row = document.createElement('article'); row.className = 'operation-sku-row awareness';
      const marker = document.createElement('span'); marker.className = 'sku-channel-mark'; marker.textContent = (sku.channel || 'M').charAt(0);
      const identity = document.createElement('div'); identity.className = 'sku-operation-identity';
      const title = document.createElement('strong'); title.textContent = sku.sku;
      const meta = document.createElement('small'); meta.textContent = skuMeta(sku) || 'Mapped listing';
      identity.append(title, meta); row.append(marker, identity); return row;
    }));
  };

  document.getElementById('sale-discount')?.addEventListener('input', event => {
    document.querySelectorAll('#sale-sku-list .sku-discount input').forEach(input => {
      if (!input.dataset.overridden) input.value = event.target.value;
    });
  });

  window.openSalePrice = async form => {
    const dialog = document.getElementById('sale-price-dialog');
    if (!dialog) return;
    const itemId = form.elements.itemId.value;
    document.getElementById('sale-price-item').value = itemId;
    document.getElementById('sale-price-expiration').value = form.elements.expirationDate.value;
    document.getElementById('sale-price-title').textContent = 'Plan a sale · ' + menuContext.product;
    document.getElementById('sale-price-subtitle').textContent = menuContext.locationCode + ' · expires ' + menuContext.expiration;
    document.getElementById('sale-sku-list').innerHTML = '<div class="operation-loading">Loading related SKUs…</div>';
    document.getElementById('sale-target-fields').replaceChildren();
    document.getElementById('sale-plan-submit').disabled = true;
    window.closeInventoryActionMenu();
    dialog.showModal();
    try {
      const [defaults, skus] = await Promise.all([loadSaleDefaults(itemId), loadSkus(itemId)]);
      if (document.getElementById('sale-price-item').value !== itemId) return;
      document.getElementById('sale-discount').value = defaults.discountPercent;
      document.getElementById('sale-start-days').value = defaults.startDays;
      document.getElementById('sale-duration').value = defaults.durationDays;
      dialog.querySelector('input[name="rememberForProduct"]').checked = defaults.productOverride === true;
      renderSaleSkus(skus);
    } catch (_) {
      document.getElementById('sale-sku-list').innerHTML = '<div class="operation-empty error"><strong>Related SKUs could not be loaded</strong><small>Close and try again. No plan has been saved.</small></div>';
    }
  };

  window.toggleAllSaleSkus = () => {
    const boxes = [...document.querySelectorAll('#sale-sku-list .sku-selector')];
    const shouldSelect = boxes.some(box => !box.checked);
    boxes.forEach(box => { box.checked = shouldSelect; box.dispatchEvent(new Event('change')); });
    document.getElementById('sale-select-all').textContent = shouldSelect ? 'Clear all' : 'Select all';
  };

  window.prepareSalePlan = form => {
    const target = document.getElementById('sale-target-fields'); target.replaceChildren();
    const selected = [...document.querySelectorAll('#sale-sku-list .operation-sku-row')]
      .filter(row => row.querySelector('.sku-selector')?.checked);
    if (!selected.length) {
      document.getElementById('sale-plan-submit').setCustomValidity('Choose at least one Marketplace SKU.');
      document.getElementById('sale-plan-submit').reportValidity();
      return false;
    }
    document.getElementById('sale-plan-submit').setCustomValidity('');
    selected.forEach(row => {
      const hidden = document.createElement('input'); hidden.type = 'hidden'; hidden.name = 'skuTarget';
      hidden.value = row.querySelector('.sku-discount input').value + '\t' + row.dataset.sku; target.append(hidden);
    });
    return form.checkValidity();
  };

  window.openDisposition = async form => {
    const dialog = document.getElementById('inventory-disposition-dialog'); if (!dialog) return;
    const itemId = form.elements.itemId.value;
    document.getElementById('disposition-item').value = itemId;
    document.getElementById('disposition-expiration').value = form.elements.expirationDate.value;
    document.getElementById('disposition-title').textContent = 'Hold or remove · ' + menuContext.product;
    document.getElementById('disposition-subtitle').textContent = menuContext.locationCode + ' · expires ' + menuContext.expiration + ' · ' + menuContext.onHand + ' each on hand';
    dialog.querySelector('input[name="availabilityMode"][value="HOLD"]').checked = true;
    window.updateDispositionMode('HOLD');
    document.getElementById('disposition-sku-list').innerHTML = '<div class="operation-loading">Loading related SKUs…</div>';
    window.closeInventoryActionMenu(); dialog.showModal();
    try { renderAwarenessSkus(await loadSkus(itemId)); }
    catch (_) { document.getElementById('disposition-sku-list').innerHTML = '<div class="operation-empty error"><strong>Related SKUs are unavailable</strong><small>The local inventory plan is still safe to save.</small></div>'; }
  };

  window.updateDispositionMode = mode => {
    document.getElementById('removal-methods').hidden = mode !== 'REMOVE';
    document.getElementById('disposition-action').value = mode;
  };

  window.prepareDisposition = form => {
    const mode = form.elements.availabilityMode.value;
    const method = form.elements.removalMethod.value;
    document.getElementById('disposition-action').value = mode === 'HOLD' ? 'HOLD' : (method === 'DONATE' ? 'DONATE' : 'REMOVE');
    [...form.querySelectorAll('input[name="removalMethod"]')].forEach(input => { input.disabled = mode === 'HOLD'; });
    return form.checkValidity();
  };

  window.openInventoryAdjustment = form => {
    const dialog = document.getElementById('inventory-adjustment-dialog');
    if (!dialog) return;
    document.getElementById('adjustment-item').value = form.elements.itemId.value;
    document.getElementById('adjustment-expiration').value = form.elements.expirationDate.value;
    document.getElementById('adjustment-location').value = document.getElementById('quick-action-location').value;
    const balance = document.getElementById('adjustment-position');
    balance.replaceChildren(...[
      ['On hand', menuContext.onHand + ' each'],
      ['Available', menuContext.available + ' each'],
      ['Position', menuContext.locationCode + ' · ' + menuContext.expiration]
    ].map(([label, value]) => {
      const span = document.createElement('span'), small = document.createElement('small'), strong = document.createElement('strong');
      small.textContent = label; strong.textContent = value; span.append(small, strong); return span;
    }));
    document.getElementById('adjustment-title').textContent = 'Adjust inventory · ' + menuContext.product;
    document.getElementById('adjustment-amount').value = '';
    document.getElementById('adjustment-amount').max = '';
    dialog.querySelector('input[name="adjustmentDirection"][value="INCREASE"]').checked = true;
    window.closeInventoryActionMenu();
    dialog.showModal();
    document.getElementById('adjustment-amount').focus();
  };

  window.prepareInventoryAdjustment = form => {
    const amount = Number(document.getElementById('adjustment-amount').value);
    if (!Number.isInteger(amount) || amount < 1) return false;
    const decrease = form.elements.adjustmentDirection.value === 'DECREASE';
    if (decrease && amount > Number(menuContext.available || 0)) {
      const field = document.getElementById('adjustment-amount');
      field.setCustomValidity('You can remove up to ' + menuContext.available + ' available eaches.'); field.reportValidity(); return false;
    }
    document.getElementById('adjustment-amount').setCustomValidity('');
    document.getElementById('adjustment-change').value = String(decrease ? -amount : amount);
    return form.checkValidity();
  };

  window.submitInventoryAdjustment = async (event, form) => {
    event.preventDefault();
    if (!window.prepareInventoryAdjustment(form)) return false;
    const submit = document.getElementById('adjustment-submit'), feedback = document.getElementById('adjustment-feedback');
    submit.disabled = true; submit.textContent = 'Saving…'; feedback.hidden = true;
    try {
      const response = await fetch(form.action, { method: 'POST', body: new FormData(form), headers: { Accept: 'application/json' } });
      const result = await response.json().catch(() => ({ message: 'The inventory adjustment could not be saved.' }));
      if (!response.ok) throw new Error(result.message);
      const change = Number(document.getElementById('adjustment-change').value);
      const row = [...document.querySelectorAll('.available-inventory-table .inventory-row')].find(candidate =>
        candidate.dataset.item === menuContext.item && candidate.dataset.location === menuContext.location && candidate.dataset.expiration === menuContext.expiration);
      if (row) {
        const onHand = Number(row.dataset.onHand || 0) + change;
        const available = Math.max(0, Number(row.dataset.available || 0) + change);
        row.dataset.onHand = String(onHand); row.dataset.available = String(available);
        row.cells[2]?.querySelector('strong')?.replaceChildren(document.createTextNode(onHand + ' each'));
        row.cells[4]?.querySelector('strong')?.replaceChildren(document.createTextNode(available + ' each'));
        const action = row.querySelector('.plan-action-button');
        if (action) { action.dataset.onHand = String(onHand); action.dataset.available = String(available); }
      }
      document.getElementById('inventory-adjustment-dialog').close();
      showInventoryActionFeedback(result.message, false);
    } catch (error) {
      feedback.textContent = error.message; feedback.hidden = false; feedback.classList.add('error');
    } finally { submit.disabled = false; submit.textContent = 'Save adjustment'; }
    return false;
  };

  const showInventoryActionFeedback = (message, error) => {
    let notice = document.getElementById('inventory-inline-notice');
    if (!notice) {
      notice = document.createElement('p'); notice.id = 'inventory-inline-notice';
      document.querySelector('.catalog-card')?.before(notice);
    }
    notice.className = 'notice inventory-inline-notice' + (error ? ' error' : ''); notice.textContent = message;
    window.setTimeout(() => notice.remove(), 5000);
  };

  document.querySelectorAll('#inventory-adjustment-dialog input[name="adjustmentDirection"]').forEach(input => input.addEventListener('change', () => {
    const decrease = input.value === 'DECREASE' && input.checked;
    const amount = document.getElementById('adjustment-amount'); amount.max = decrease ? menuContext.available : '';
    document.getElementById('adjustment-limit').textContent = decrease
      ? 'Up to ' + menuContext.available + ' unreserved eaches can be removed.'
      : 'The increase will be added to on-hand and available inventory.';
  }));

  window.openClearPlan = form => {
    const dialog = document.getElementById('clear-plan-dialog'); if (!dialog) return;
    document.getElementById('clear-plan-item').value = form.elements.itemId.value;
    document.getElementById('clear-plan-expiration').value = form.elements.expirationDate.value;
    document.getElementById('clear-plan-title').textContent = menuContext.product + ' · expires ' + menuContext.expiration;
    document.getElementById('clear-plan-current').textContent = menuContext.actionLabel || 'Current plan';
    window.closeInventoryActionMenu(); dialog.showModal();
  };

  document.addEventListener('click', event => {
    if (!event.target.closest('#inventory-action-menu,.plan-action-button')) window.closeInventoryActionMenu();
    if (!event.target.closest('.inventory-product-picker')) clearResults();
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && menu && !menu.hidden) window.closeInventoryActionMenu();
  });
  window.addEventListener('resize', positionActionMenu);
  window.addEventListener('scroll', positionActionMenu, true);
  document.querySelector('.table-wrap')?.addEventListener('scroll', positionActionMenu);

  const openFromProduct=(event,row)=>{
    if(window.getSelection()?.toString())return;
    event.preventDefault();event.stopPropagation();openInventoryHistory(row);
  };
  document.querySelectorAll('.available-inventory-table .inventory-row').forEach(row=>{
    row.removeAttribute('tabindex');row.removeAttribute('role');row.removeAttribute('onclick');row.removeAttribute('onkeydown');
    const product=row.querySelector('.product-cell'),name=product?.querySelector(':scope > div > strong');
    if(name){name.classList.add('inventory-product-detail-link');name.tabIndex=0;name.setAttribute('role','button');name.setAttribute('aria-label','Open inventory details for '+(row.dataset.product||'product'));name.addEventListener('click',event=>openFromProduct(event,row));name.addEventListener('keydown',event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();openInventoryHistory(row)}})}
    const plainPicture=product?.querySelector(':scope > .product-avatar');
    if(plainPicture){plainPicture.classList.add('inventory-picture-detail-link');plainPicture.tabIndex=0;plainPicture.setAttribute('role','button');plainPicture.addEventListener('click',event=>openFromProduct(event,row));plainPicture.addEventListener('keydown',event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();openInventoryHistory(row)}})}
    const uploadLabel=product?.querySelector('.product-image-upload label');
    if(uploadLabel)uploadLabel.addEventListener('click',event=>{if(event.target.closest('i'))return;openFromProduct(event,row)});
  });
})();
