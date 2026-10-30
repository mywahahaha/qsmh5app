'use strict';

var assert = require('node:assert/strict');
var fs = require('node:fs');
var vm = require('node:vm');

var bridgeCallback;
var context = {
  QSMH5APP: {
    getGameInfo: function (params, callback) {
      bridgeCallback = callback;
    }
  },
  Promise: Promise,
  URL: URL
};
context.window = context;
vm.runInNewContext(fs.readFileSync(__dirname + '/qsm-jsbridge.js', 'utf8'), context);

function makeAxios(defaults) {
  return {
    defaults: defaults || {},
    interceptors: {
      request: {
        use: function (fulfilled, rejected) {
          this.fulfilled = fulfilled;
          this.rejected = rejected;
          return 0;
        },
        eject: function (id) {
          this.ejected = id;
        }
      }
    }
  };
}

async function run() {
  var axios = makeAxios({ baseURL: 'https://box.74meng.com/api' });
  var uninstall = context.GameBridge.installAxiosInterceptors(axios);
  var configPromise = axios.interceptors.request.fulfilled({
    url: '/user/info',
    headers: {}
  });

  bridgeCallback({
    code: 0,
    data: {
      packageCode: 'channel-a',
      gameId: 92,
      gameVersion: '1.0.1',
      accountToken: 'token-value',
      deviceType: 'android',
      deviceCode: 'DEVICE'
    }
  });

  var config = await configPromise;
  assert.deepEqual(JSON.parse(JSON.stringify(config.headers)), {
    Gameid: '92',
    Devicecode: 'DEVICE',
    Packagecode: 'channel-a',
    Devicetype: 'android',
    Accountoken: 'token-value',
    Gameversion: '1.0.1'
  });

  var thirdPartyConfig = { url: 'https://tracking.example/collect', headers: {} };
  assert.equal(await axios.interceptors.request.fulfilled(thirdPartyConfig), thirdPartyConfig);
  assert.deepEqual(thirdPartyConfig.headers, {});

  uninstall();
  assert.equal(axios.interceptors.request.ejected, 0);
  console.log('Axios Bridge interceptor tests passed');
}

run().catch(function (error) {
  console.error(error);
  process.exitCode = 1;
});
