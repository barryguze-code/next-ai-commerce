/* One accessible dialog for all visible FBA rows. No background requests or observers. */
(() => {
  if (window.nextAiFbaStockReady) return;
  window.nextAiFbaStockReady = true;
  document.addEventListener('click', event => {
    const summary = event.target.closest('.fba-stock-details > summary');
    if (!summary || !window.HTMLDialogElement) return;
    event.preventDefault();
    const source = summary.parentElement.querySelector('.fba-stock-breakdown');
    if (!source) return;
    const dialog = document.createElement('dialog');
    dialog.className = 'fba-stock-dialog';
    dialog.setAttribute('aria-labelledby', 'fba-stock-dialog-title');
    const header = document.createElement('header'), title = document.createElement('h2');
    title.id = 'fba-stock-dialog-title'; title.textContent = 'FBA stock & fees';
    const close = document.createElement('button');
    close.type = 'button'; close.textContent = '×'; close.setAttribute('aria-label', 'Close FBA stock');
    const content = source.cloneNode(true);
    const footer = document.createElement('footer'), done = document.createElement('button');
    done.type = 'button'; done.className = 'primary-button'; done.textContent = 'Done';
    const dismiss = () => { dialog.close(); dialog.remove(); summary.focus(); };
    close.addEventListener('click', dismiss); done.addEventListener('click', dismiss);
    dialog.addEventListener('cancel', e => { e.preventDefault(); dismiss(); });
    header.append(title, close); footer.append(done); dialog.append(header, content, footer);
    document.body.append(dialog); dialog.showModal(); close.focus();
  });
})();
