const { test } = require('node:test');
const assert = require('node:assert/strict');
const { isInternal, mayOpenExternal } = require('../src/window-policy.cjs');
test('native window keeps navigation within its own server origin', () => {
  assert.equal(isInternal('http://127.0.0.1:8095/workspace','http://127.0.0.1:8095'),true);
  assert.equal(isInternal('http://127.0.0.1:9000/','http://127.0.0.1:8095'),false);
  assert.equal(isInternal('https://evil.invalid/','http://127.0.0.1:8095'),false);
});
test('external navigation cannot launch scripts or local files', () => {
  assert.equal(mayOpenExternal('https://example.com'),true);
  for(const url of ['javascript:alert(1)','file:///C:/private','data:text/html,test','not a url']) assert.equal(mayOpenExternal(url),false);
});
