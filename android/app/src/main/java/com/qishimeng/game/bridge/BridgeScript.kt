package com.qishimeng.game.bridge

object BridgeScript {
    const val SOURCE = """
        (function(){
          if (window.QSMH5APP) return;
          var callbacks = {};
          var nextId = 1;
          window.__QSMH5APP_CALLBACK__ = function(id, result) {
            var callback = callbacks[id];
            if (!callback) return;
            delete callbacks[id];
            callback(result);
          };
          var invoke = function(method, args, callback) {
            if (method === 'saveLocalStorage') {
              var normalizeEntry = function(entry) {
                if (!entry || typeof entry !== 'object' || Array.isArray(entry)) return entry;
                var value = entry.value;
                return {key: entry.key, value: typeof value === 'string' || typeof value === 'number' ? value : ''};
              };
              args = Array.isArray(args) ? args.map(normalizeEntry) : normalizeEntry(args);
            }
            var id = String(nextId++);
            if (typeof callback === 'function') callbacks[id] = callback;
            window.QSMNative.postMessage(JSON.stringify({id:id, method:method, args:args || {}}));
          };
          var methods = ['agreePrivacyPolicy','rejectPrivacyPolicy','requestPhoneLogin','notifyLoginSuccess','getAccountToken','logout','getGameInfo','saveGameRole','getGameRole','saveLocalStorage','getLocalStorage','isAppInstalled','openApp','setFloatingBall','openMiniWindow','closeMiniWindow','changeGameUrl','closePage'];
          var bridge = {};
          methods.forEach(function(method) { bridge[method] = function(args, callback) { if (typeof args === 'function') { callback = args; args = {}; } invoke(method, args, callback); }; });
          bridge.on = function() {};
          bridge.off = function() {};
          window.QSMH5APP = bridge;
          window.dispatchEvent(new Event('QSMH5APPReady'));
        })();
    """
}
