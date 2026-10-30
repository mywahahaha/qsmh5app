const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

function injectedBridge() {
  const kotlin = fs.readFileSync(path.join(__dirname, '../android/app/src/main/java/com/qishimeng/game/bridge/BridgeScript.kt'), 'utf8');
  const requests = [];
  const window = { QSMNative: { postMessage: payload => requests.push(JSON.parse(payload)) }, dispatchEvent() {} };
  vm.runInNewContext(kotlin.split('"""')[1], { window, Event: function () {} });
  return { window, requests };
}

test('应用接口异步回调保留布尔值且并发请求不会串回调', () => {
  const { window, requests } = injectedBridge();
  const replies = [];
  window.QSMH5APP.isAppInstalled({ packageName: 'com.me74.qsmhz' }, r => replies.push(['installed', r.data]));
  window.QSMH5APP.openApp({ packageName: 'com.example.missing' }, r => replies.push(['opened', r.data]));
  assert.deepEqual(replies, []);
  assert.deepEqual(requests, [
    { id: '1', method: 'isAppInstalled', args: { packageName: 'com.me74.qsmhz' } },
    { id: '2', method: 'openApp', args: { packageName: 'com.example.missing' } }
  ]);
  window.__QSMH5APP_CALLBACK__('2', { code: 0, msg: 'ok', data: false });
  window.__QSMH5APP_CALLBACK__('1', { code: 0, msg: 'ok', data: true });
  window.__QSMH5APP_CALLBACK__('1', { code: 0, msg: 'ok', data: false });
  assert.deepEqual(replies, [['opened', false], ['installed', true]]);
});

test('单个键值对象可保存并按同一 key 读取字符串回调', () => {
  const { window, requests } = injectedBridge();
  let saved;
  window.QSMH5APP.saveLocalStorage({ key: 'abc', value: '123' }, result => { saved = result; });
  assert.deepEqual(requests[0], { id: '1', method: 'saveLocalStorage', args: { key: 'abc', value: '123' } });
  window.__QSMH5APP_CALLBACK__('1', { code: 0, msg: 'ok', data: true });
  assert.equal(saved.data, true);
  let loaded;
  window.QSMH5APP.getLocalStorage({ key: 'abc' }, result => { loaded = result; });
  assert.deepEqual(requests[1], { id: '2', method: 'getLocalStorage', args: { key: 'abc' } });
  window.__QSMH5APP_CALLBACK__('2', { code: 0, msg: 'ok', data: '123' });
  assert.equal(loaded.data, '123');
});

test('批量键值数组保留顺序、重复 key 和数字参数', () => {
  const { window, requests } = injectedBridge();
  const batch = [{ key: 'abc', value: 123 }, { key: 'def', value: '456' }, { key: 'abc', value: '000' }];
  window.QSMH5APP.saveLocalStorage(batch, function () {});
  assert.deepEqual(requests[0].args, batch);
  assert.equal(batch[0].value, 123);
});

test('单项和批量的非字符串数字 value 变为空串且不修改原参数', () => {
  const { window, requests } = injectedBridge();
  const circular = {}; circular.self = circular;
  const values = [undefined, null, true, {}, [], function () {}, Symbol('test'), BigInt(123), circular];
  const batch = values.map((value, index) => ({ key: String(index), value }));
  window.QSMH5APP.saveLocalStorage(batch, function () {});
  assert.deepEqual(requests[0].args, values.map((_, index) => ({ key: String(index), value: '' })));
  assert.equal(batch[2].value, true);
  assert.equal(batch[8].value, circular);
  window.QSMH5APP.saveLocalStorage({ key: '__proto__', value: circular }, function () {});
  assert.deepEqual(requests[1].args, { key: '__proto__', value: '' });
});
