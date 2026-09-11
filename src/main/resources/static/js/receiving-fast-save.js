(() => {
  const unitText = value => Math.round(Number(value) || 0).toLocaleString();

  const showMessage = (message, error = false) => {
    const notice = document.getElementById('receive-live-notice');
    if (!notice) return;
    notice.textContent = message;
    notice.classList.toggle('error', error);
    notice.hidden = false;
    if (!error) window.setTimeout(() => { notice.hidden = true; }, 4500);
  };

  const updateProgress = result => {
    const progress = document.getElementById('receive-progress');
    if (!progress) return;
    progress.querySelector('strong').textContent = unitText(result.completedLines);
    progress.querySelector('small').textContent = 'of ' + unitText(result.totalLines);
    progress.style.setProperty('--progress', (result.totalLines ? result.completedLines * 100 / result.totalLines : 0) + '%');
    const ready = result.totalLines > 0 && result.completedLines === result.totalLines;
    const title = document.getElementById('receive-progress-title');
    const copy = document.getElementById('receive-progress-copy');
    if (title) title.textContent = ready ? 'Ready to close' : 'Continue receiving';
    if (copy) copy.textContent = ready
      ? 'Inventory is already available. Close this receiving to lock it and finalize shared landed costs.'
      : 'Complete each line or explain the remaining quantity as a vendor discrepancy. Saved quantities are available immediately.';
    const close = document.getElementById('close-receiving');
    if (close && result.sessionStatus !== 'POSTED') close.disabled = !ready;
  };

  const updateRow = (form, result) => {
    const row = form.closest('.receive-line');
    if (!row) return;
    const totals = row.querySelectorAll('.receive-quantity strong');
    if (totals[1]) totals[1].textContent = unitText(result.receivedEach);
    if (totals[2]) totals[2].textContent = unitText(result.remainingEach);
    const remaining = Number(result.remainingEach) || 0;
    row.classList.remove('pending', 'partial', 'complete');
    row.classList.add(remaining === 0 ? 'complete' : 'partial');
    if (remaining === 0) {
      row.querySelectorAll('[form="' + CSS.escape(form.id) + '"]').forEach(control => {
        control.disabled = true;
      });
      row.querySelector('.cost-change-details')?.removeAttribute('open');
      const button = row.querySelector('.receive-save');
      if (button) {
        const check = document.createElement('span');
        check.className = 'receive-check';
        check.textContent = '✓';
        button.replaceWith(check);
      }
    } else {
      const unitsPerCase = Number(row.querySelector('input[name="unitsPerCase"]')?.value) || 1;
      const cases = row.querySelector('input[name="caseQuantity"]');
      const eaches = row.querySelector('input[name="eachQuantity"]');
      if (cases) cases.value = Math.floor(remaining / unitsPerCase);
      if (eaches) eaches.value = Math.round(remaining % unitsPerCase);
      const expiration = row.querySelector('input[name="expirationDate"]');
      if (expiration) expiration.value = '';
    }
  };

  document.addEventListener('submit', async event => {
    const form = event.target.closest('.receive-line-form');
    if (!form) return;
    event.preventDefault();
    const button = document.querySelector('[form="' + CSS.escape(form.id) + '"].receive-save');
    if (button?.disabled) return;
    if (button) {
      button.disabled = true;
      button.dataset.label = button.textContent;
      button.textContent = 'Saving…';
    }
    try {
      const response = await fetch(form.action, {
        method: 'POST',
        body: new FormData(form),
        headers: { Accept: 'application/json' }
      });
      const result = await response.json();
      if (!response.ok) throw new Error(result.message || 'The receipt could not be saved.');
      updateRow(form, result);
      updateProgress(result);
      showMessage(result.message);
    } catch (error) {
      showMessage(error.message || 'The receipt could not be saved.', true);
    } finally {
      if (button?.isConnected) {
        button.disabled = false;
        button.textContent = button.dataset.label || 'Receive';
      }
    }
  });
})();
