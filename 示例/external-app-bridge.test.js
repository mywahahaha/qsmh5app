const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

function loadBridge() {
  const calls = [];
  const window = {
    QSMH5APP: {
      isAppInstalled(args, callback) { calls.push({ method: 'isAppInstalled', args, callback }); },
      openApp(args, callback) { calls.push({ method: 'openApp', args, callback }); }
    }
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, 'qsm-jsbridge.js'), 'utf8'), { window, Promise, URL });
  return { window, calls };
}

test('Promise 应用接口传入包名并返回真正的布尔值', async () => {
  const { window, calls } = loadBridge();
  const installed = window.GameBridge.isAppInstalled('com.me74.qsmhz');
  const opened = window.GameBridge.openApp('com.example.missing');
  assert.deepEqual(JSON.parse(JSON.stringify(calls.map(c => ({ method: c.method, args: c.args })))), [
    { method: 'isAppInstalled', args: { packageName: 'com.me74.qsmhz' } },
    { method: 'openApp', args: { packageName: 'com.example.missing' } }
  ]);
  calls[1].callback({ code: 0, msg: 'ok', data: false });
  calls[0].callback({ code: 0, msg: 'ok', data: true });
  assert.equal(await installed, true);
  assert.equal(await opened, false);
});

test('参数错误与缺少原生环境时 Promise 拒绝而非误报未安装', async () => {
  const { window, calls } = loadBridge();
  const result = window.GameBridge.isAppInstalled('');
  calls[0].callback({ code: 1001, msg: 'packageName 参数无效', data: false });
  await assert.rejects(result, error => error.code === 1001);
  delete window.QSMH5APP;
  await assert.rejects(window.GameBridge.openApp('com.me74.qsmhz'), /未检测到/);
});
