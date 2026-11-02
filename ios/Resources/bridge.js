(function () {
  'use strict';
  if (window.QSMH5APP) return;
  var documentID = '__QSM_DOCUMENT_ID__';
  var callbacks = Object.create(null), nextID = 1;
  window.__QSMH5APP_CALLBACK__ = function (stamp, id, result) {
    if (stamp !== documentID) return;
    var callback = callbacks[id];
    if (!callback) return;
    delete callbacks[id];
    callback(result);
  };
  function invoke(method, args, callback) {
    if (typeof args === 'function') { callback = args; args = {}; }
    if (typeof args === 'undefined') args = {};
    var id = String(nextID++);
    if (typeof callback === 'function') callbacks[id] = callback;
    try {
      if (method === 'saveLocalStorage') {
        var normalizeEntry = function (entry) {
          if (!entry || typeof entry !== 'object' || Array.isArray(entry)) return entry;
          var value = entry.value;
          return {key: entry.key, value: typeof value === 'string' || typeof value === 'number' ? value : ''};
        };
        args = Array.isArray(args) ? args.map(normalizeEntry) : normalizeEntry(args);
      }
      // 以 JSON 字符串发送，保留数组、null 和参数错误，交由原生按方法校验。
      window.webkit.messageHandlers.qsm.postMessage(JSON.stringify({documentID: documentID, id: id, method: method, args: args}));
    } catch (error) {
      var fn = callbacks[id]; delete callbacks[id];
      if (fn) fn({code: 1001, msg: '参数无法序列化', data: method === 'getLocalStorage' ? '' : false});
    }
  }
  var bridge = {};
  ['agreePrivacyPolicy','rejectPrivacyPolicy','revokePrivacyPolicy','requestPhoneLogin','notifyLoginSuccess',
   'getAccountToken','getGameInfo','logout','openMiniWindow','closeMiniWindow','changeGameUrl','closePage',
   'saveLocalStorage','getLocalStorage','saveGameRole','getGameRole','setFloatingBall','isAppInstalled','openApp']
    .forEach(function (method) { bridge[method] = function (args, callback) { invoke(method, args, callback); }; });
  bridge.on = function () {};
  bridge.off = function () {};
  window.QSMH5APP = bridge;
  window.addEventListener('pagehide', function () { callbacks = Object.create(null); });
  window.dispatchEvent(new Event('QSMH5APPReady'));
})();
