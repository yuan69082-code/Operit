function isInternal(url, origin) {
  try { return new URL(url).origin === origin; } catch { return false; }
}
function mayOpenExternal(url) {
  try { return ['https:', 'http:'].includes(new URL(url).protocol); } catch { return false; }
}
module.exports = { isInternal, mayOpenExternal };
