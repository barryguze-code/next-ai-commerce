(() => {
  const moneyValue = text => Number((text || '').replace(/[^0-9.-]/g, '')) || 0;

  document.querySelectorAll('.cost-change-details').forEach(details => {
    const summary = details.querySelector(':scope > summary');
    const panel = details.querySelector('.cost-change-panel');
    const comparison = panel?.querySelectorAll('.cost-compare strong');
    const keepOption = panel?.querySelector('input[name="updateDefaultCost"][value="false"]');
    const updateOption = panel?.querySelector('input[name="updateDefaultCost"][value="true"]');
    if (!summary || !panel || !comparison || comparison.length < 2 || !keepOption || !updateOption) return;

    const defaultText = comparison[0].textContent.trim();
    const invoiceText = comparison[1].textContent.trim();
    const direction = moneyValue(invoiceText) > moneyValue(defaultText)
      ? 'Invoice is higher'
      : moneyValue(invoiceText) < moneyValue(defaultText) ? 'Invoice is lower' : 'Invoice differs';

    const renderSummary = () => {
      summary.classList.toggle('default-cost-changed', updateOption.checked);
      if (updateOption.checked) {
        const state = document.createElement('span');
        state.className = 'cost-update-state';
        state.textContent = '↻ Default will update';
        summary.replaceChildren(state);
        return;
      }
      const status = document.createElement('span');
      status.className = 'cost-review-status';
      status.textContent = direction;
      const price = document.createElement('strong');
      price.textContent = invoiceText;
      const review = document.createElement('span');
      review.className = 'review-cost';
      review.textContent = 'Review';
      summary.replaceChildren(status, price, review);
    };

    const openEditor = event => {
      event.preventDefault();
      const dialog = document.createElement('dialog');
      dialog.className = 'cost-choice-dialog';
      const heading = panel.querySelector(':scope > header > strong');
      if (heading) heading.textContent = direction;
      const obsoleteHelp = panel.querySelector(':scope > header .cost-info');
      if (obsoleteHelp) obsoleteHelp.hidden = true;
      const explanation = document.createElement('p');
      explanation.className = 'cost-choice-explanation';
      explanation.textContent = `Your catalogue default is ${defaultText}. This receipt uses ${invoiceText}. Choose the cost to suggest for future receiving.`;
      panel.querySelector(':scope > header')?.after(explanation);
      const actions = document.createElement('footer');
      actions.className = 'cost-choice-actions';
      const note = document.createElement('small');
      note.textContent = 'Your choice is saved when you receive this item.';
      const done = document.createElement('button');
      done.type = 'button';
      done.className = 'primary-button compact-button';
      done.textContent = 'Done';
      done.addEventListener('click', () => dialog.close());
      actions.append(note, done);
      panel.append(actions);
      dialog.append(panel);
      document.body.append(dialog);
      dialog.addEventListener('click', click => { if (click.target === dialog) dialog.close(); });
      dialog.addEventListener('close', () => {
        if (obsoleteHelp) obsoleteHelp.hidden = false;
        explanation.remove();
        actions.remove();
        details.append(panel);
        details.open = false;
        dialog.remove();
        renderSummary();
      }, { once: true });
      details.open = true;
      dialog.showModal();
    };

    summary.addEventListener('click', openEditor);
    keepOption.addEventListener('change', renderSummary);
    updateOption.addEventListener('change', renderSummary);
    renderSummary();
  });
})();
