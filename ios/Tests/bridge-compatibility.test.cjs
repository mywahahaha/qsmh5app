const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function bridge(platform) {
  const requests = [], events = [];
  const send = payload => requests.push(JSON.parse(payload));
  const window = {QSMNative: {postMessage: send}, webkit: {messageHandlers: {qsm: {postMessage: send}}},
    addEventListener() {}, dispatchEvent(event) {events.push(event.type);}};
  const source = platform === 'ios'
    ? fs.readFileSync(path.join(__dirname, '../Resources/bridge.js'), 'utf8').replace('__QSM_DOCUMENT_ID__', 'document')
    : fs.readFileSync(path.join(__dirname, '../../android/app/src/main/java/com/qishimeng/game/bridge/BridgeScript.kt'), 'utf8').split('"""')[1];
  vm.runInNewContext(source, {window, Event: function(type) {this.type = type;}});
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../示例/qsm-jsbridge.js'), 'utf8'), {window, URL, Promise});
  return {window, requests, events, reply(id, result) {
    if (platform === 'ios') window.__QSMH5APP_CALLBACK__('document', id, result);
    else window.__QSMH5APP_CALLBACK__(id, result);
  }};
}

for (const platform of ['android', 'ios']) {
  test(`${platform} 公共 H5 包装及 Axios Header 使用原生返回的平台和渠道码`, async () => {
    const b = bridge(platform);
    assert.deepEqual(b.events, ['QSMH5APPReady']);
    let intercept;
    const axios = {defaults: {baseURL: 'https://box.74meng.com'}, interceptors: {request: {use(fn) {intercept = fn;}, eject() {}}}};
    b.window.GameBridge.installAxiosInterceptors(axios);
    const pending = intercept({url: '/api/test', headers: {}});
    const req = b.requests.at(-1);
    assert.equal(req.method, 'getGameInfo');
    b.reply(req.id, {code: 0, msg: 'ok', data: {packageCode: '1-218', deviceType: platform, gameId: 29, gameVersion: '1.0.1', deviceCode: '', accountToken: ''}});
    const config = await pending;
    assert.equal(config.headers.Packagecode, '1-218');
    assert.equal(config.headers.Devicetype, platform);
    assert.equal(config.headers.Accountoken, '');
  });
  test(`${platform} 公共签名支持省略参数及存储非字符串值归一化`, () => {
    const b = bridge(platform);
    const circular = {}; circular.self = circular;
    b.window.QSMH5APP.getGameInfo(() => {});
    assert.deepEqual(b.requests[0].args, {});
    const values = [true, null, undefined, {}, [], () => {}, Symbol('x'), BigInt(2), circular];
    b.window.QSMH5APP.saveLocalStorage(values.map((value, i) => ({key: String(i), value})), () => {});
    assert.deepEqual(b.requests[1].args, values.map((_, i) => ({key: String(i), value: ''})));
  });
}
