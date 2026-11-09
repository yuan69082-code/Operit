document.getElementById('connect').addEventListener('submit', async event => {
  event.preventDefault();
  const button = document.getElementById('open'), error = document.getElementById('error');
  button.disabled = true; error.textContent = '';
  try { await window.operitPhone.open(document.getElementById('address').value.trim()); window.close(); }
  catch (failure) { console.error('phone.connect', failure); error.textContent = failure.message; }
  finally { button.disabled = false; }
});
