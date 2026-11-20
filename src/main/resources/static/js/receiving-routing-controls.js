// Keep the condition chooser on the condition/action side of the workspace so it
// never obscures the expiration date or destination being reviewed.
placeConditionPicker = picker => {
  const panel=picker.querySelector('.condition-options');
  const box=picker.querySelector('summary').getBoundingClientRect();
  const width=Math.min(390,window.innerWidth-24);
  const height=Math.min(panel.scrollHeight,330);
  let top=box.bottom+7;
  if(top+height>window.innerHeight-12)top=Math.max(12,box.top-height-7);
  panel.style.left=Math.max(12,window.innerWidth-width-14)+'px';
  panel.style.top=top+'px';
};
