const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function make() {
  const requests = [], listeners = {};
  const window = {webkit: {messageHandlers: {qsm: {postMessage(payload) { requests.push(JSON.parse(payload)); }}}},
    addEventListener(name, callback) {listeners[name] = callback;}, dispatchEvent() {}};
  const source = fs.readFileSync(path.join(__dirname, '../Resources/bridge.js'), 'utf8').replace('__QSM_DOCUMENT_ID__', 'document-one');
  vm.runInNewContext(source, {window, Event: function () {}});
  return {window, requests, listeners};
}

test('保留非对象错误参数、请求 id 和原方法', () => {
  const {window, requests} = make();
  window.QSMH5APP.getLocalStorage(123, () => {});
  window.QSMH5APP.getGameInfo(false, () => {});
  assert.deepEqual(requests.map(x => [x.id, x.method, x.args]), [['1', 'getLocalStorage', 123], ['2', 'getGameInfo', false]]);
});
test('按文档和 id 路由并发回调且最多一次', () => {
  const {window} = make(), results = [];
  window.QSMH5APP.getGameInfo({}, r => results.push(['one', r.data]));
  window.QSMH5APP.getAccountToken({}, r => results.push(['two', r.data]));
  window.__QSMH5APP_CALLBACK__('other-document', '1', {data: 'wrong'});
  window.__QSMH5APP_CALLBACK__('document-one', '2', {data: 'b'});
  window.__QSMH5APP_CALLBACK__('document-one', '1', {data: 'a'});
  window.__QSMH5APP_CALLBACK__('document-one', '1', {data: 'duplicate'});
  assert.deepEqual(results, [['two', 'b'], ['one', 'a']]);
});
test('pagehide 清理回调且数组参数不改写', () => {
  const {window, requests, listeners} = make();
  let called = false;
  window.QSMH5APP.saveLocalStorage([{key: 'a', value: 42}], () => {called = true;});
  assert.deepEqual(requests[0].args, [{key: 'a', value: 42}]);
  listeners.pagehide(); window.__QSMH5APP_CALLBACK__('document-one', '1', {data: true});
  assert.equal(called, false);
});
test('无法序列化的参数回调错误，且省略参数签名可用', () => {
  const {window, requests} = make();
  let result;
  const circular = {}; circular.self = circular;
  window.QSMH5APP.changeGameUrl(circular, r => {result = r;});
  assert.equal(result.code, 1001);
  window.QSMH5APP.closePage(() => {});
  assert.deepEqual(requests[0].args, {});
});
