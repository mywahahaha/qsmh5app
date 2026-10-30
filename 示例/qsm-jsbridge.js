(function (global) {
  'use strict';

  function callNativeBridge(method, params) {
    return new Promise(function (resolve, reject) {
      var bridge = global.QSMH5APP;
      if (!bridge || typeof bridge[method] !== 'function') {
        reject(new Error('未检测到 QSMH5APP.' + method + '，请确认页面由 SDK WebView 加载。'));
        return;
      }

      try {
        bridge[method](params || {}, function (result) {
          if (!result) {
            reject(new Error('SDK 未返回结果'));
            return;
          }
          if (result.code !== 0) {
            var error = new Error(result.msg || 'Bridge 调用失败');
            error.code = result.code;
            reject(error);
            return;
          }
          resolve(result.data);
        });
      } catch (error) {
        reject(error);
      }
    });
  }

  function isSameApiOrigin(config, axiosInstance) {
    var requestUrl = config.url;
    var baseUrl = config.baseURL || (axiosInstance.defaults && axiosInstance.defaults.baseURL);

    if (!requestUrl) {
      return false;
    }

    try {
      var pageUrl = global.location && global.location.href;
      var base = new URL(baseUrl || pageUrl, pageUrl);
      var request = new URL(requestUrl, base);
      return request.origin === base.origin;
    } catch (error) {
      return false;
    }
  }

  function setHeader(headers, name, value) {
    var headerValue = value == null ? '' : String(value);
    if (typeof headers.set === 'function') {
      headers.set(name, headerValue);
    } else {
      headers[name] = headerValue;
    }
  }

  function installAxiosInterceptors(axiosInstance) {
    if (!axiosInstance || !axiosInstance.interceptors || !axiosInstance.interceptors.request) {
      throw new Error('请传入有效的 Axios 实例');
    }

    var interceptorId = axiosInstance.interceptors.request.use(function (config) {
      if (!isSameApiOrigin(config, axiosInstance)) {
        return config;
      }

      return callNativeBridge('getGameInfo', {}).then(function (gameInfo) {
        var headers = config.headers || (config.headers = {});
        setHeader(headers, 'Gameid', gameInfo && gameInfo.gameId);
        setHeader(headers, 'Devicecode', gameInfo && gameInfo.deviceCode);
        setHeader(headers, 'Packagecode', gameInfo && gameInfo.packageCode);
        setHeader(headers, 'Devicetype', gameInfo && gameInfo.deviceType);
        setHeader(headers, 'Accountoken', gameInfo && gameInfo.accountToken);
        setHeader(headers, 'Gameversion', gameInfo && gameInfo.gameVersion);
        return config;
      });
    });

    return function () {
      axiosInstance.interceptors.request.eject(interceptorId);
    };
  }

  global.GameBridge = {
    call: callNativeBridge,
    installAxiosInterceptors: installAxiosInterceptors,
    getGameInfo: function () {
      return callNativeBridge('getGameInfo', {});
    },
    getAccountToken: function () {
      return callNativeBridge('getAccountToken', {});
    },
    isAppInstalled: function (packageName) {
      return callNativeBridge('isAppInstalled', { packageName: packageName });
    },
    openApp: function (packageName) {
      return callNativeBridge('openApp', { packageName: packageName });
    },
    closeMiniWindow: function (page) {
      return callNativeBridge('closeMiniWindow', { page: page });
    }
  };
}(window));
