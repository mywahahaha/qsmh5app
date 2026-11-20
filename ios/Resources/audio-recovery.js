(function () {
  'use strict';

  // 此脚本只在 audioControl=true 的 web_game 中由 WKUserScript 注入。
  // 不注册原生消息处理器，不读取页面业务数据，也不向页面传递任何业务数据。
  if (window.__qsmAudioRecoveryShim) return;
  try {
    Object.defineProperty(window, '__qsmAudioRecoveryShim', {value: true});
  } catch (_) {
    window.__qsmAudioRecoveryShim = true;
  }

  var lifecycleEvent = '__qsmAudioLifecycle';
  var messageKey = '__qsmAudioLifecycleMessage';
  var gameFrameID = 'game_if';
  var contextRecords = [];
  var resumeContexts = [];
  var mediaRecords = [];
  var resumeMedia = [];
  var generation = 0;
  var recoveryTimers = [];
  var forwardingTimers = [];
  var foregroundUntil = 0;
  var gestureGeneration = -1;
  var controlledByParent = false;
  var localLifecycleSequence = -1;
  var topLifecycleSequence = 0;
  var topLifecycleState = null;
  var backgroundActive = false;
  var sawVisibilityBackground = false;

  function isTopFrame() {
    try { return window.top === window; } catch (_) { return false; }
  }

  function isLifecycleState(value) {
    return value === 'background' || value === 'foreground';
  }

  function isLifecycleSequence(value) {
    return typeof value === 'number' && isFinite(value);
  }

  function forgetTimers(timers) {
    while (timers.length) {
      try { window.clearTimeout(timers.pop()); } catch (_) {}
    }
  }

  function stateOf(context) {
    try { return context && context.state; } catch (_) { return 'closed'; }
  }

  function isLiveState(state) {
    return state !== 'closed';
  }

  function isRecoverableState(state) {
    // Safari/WebKit 可能在中断期间报告 interrupted，而不是 suspended。
    return state === 'running' || state === 'interrupted';
  }

  function contextRecord(context) {
    for (var index = 0; index < contextRecords.length; index += 1) {
      if (contextRecords[index].context === context) return contextRecords[index];
    }
    return null;
  }

  function updateContextRecord(record) {
    var state = stateOf(record.context);
    record.lastState = state;
    if (isRecoverableState(state)) record.hasRun = true;
    return state;
  }

  function rememberContext(context) {
    if (!context) return null;
    var existing = contextRecord(context);
    if (existing) return existing;
    var state = stateOf(context);
    var record = {context: context, lastState: state, hasRun: isRecoverableState(state)};
    contextRecords.push(record);
    try {
      if (typeof context.addEventListener === 'function') {
        context.addEventListener('statechange', function () { updateContextRecord(record); });
      }
    } catch (_) {}
    return record;
  }

  function captureConstructor(name) {
    var Native = window[name];
    if (typeof Native !== 'function' || typeof Proxy !== 'function' ||
        typeof Reflect === 'undefined' || typeof Reflect.construct !== 'function') return;
    try {
      var Wrapped = new Proxy(Native, {
        construct: function (target, args, newTarget) {
          var context = Reflect.construct(target, args, newTarget === Wrapped ? target : newTarget);
          rememberContext(context);
          return context;
        }
      });
      window[name] = Wrapped;
    } catch (_) {
      // 不能包装时保持第三方游戏的原始构造器，不阻断游戏加载。
    }
  }

  function mediaRecord(element) {
    for (var index = 0; index < mediaRecords.length; index += 1) {
      if (mediaRecords[index].element === element) return mediaRecords[index];
    }
    return null;
  }

  function mediaLooksPlaying(record) {
    try {
      return !!record.element && !record.element.paused && !record.element.ended;
    } catch (_) {
      return false;
    }
  }

  function rememberMedia(element) {
    if (!element) return null;
    var existing = mediaRecord(element);
    if (existing) return existing;
    var record = {element: element, wasPlaying: false};
    mediaRecords.push(record);
    if (mediaLooksPlaying(record)) record.wasPlaying = true;
    try {
      element.addEventListener('playing', function () { record.wasPlaying = true; });
      element.addEventListener('play', function () { record.wasPlaying = true; });
      // 应用已进入后台后的 pause 很可能由 WebKit 触发，不能把它误当作玩家主动暂停。
      element.addEventListener('pause', function () { if (!backgroundActive) record.wasPlaying = false; });
      element.addEventListener('ended', function () { record.wasPlaying = false; });
    } catch (_) {}
    return record;
  }

  function scanMedia() {
    var items;
    try { items = document.querySelectorAll ? document.querySelectorAll('audio,video') : null; } catch (_) { return; }
    if (!items) return;
    for (var index = 0; index < items.length; index += 1) rememberMedia(items[index]);
  }

  function captureMediaPlayback() {
    var prototype;
    try { prototype = window.HTMLMediaElement && window.HTMLMediaElement.prototype; } catch (_) { return; }
    if (!prototype || typeof prototype.play !== 'function') return;
    try {
      if (prototype.__qsmAudioRecoveryPlayWrapped) return;
      Object.defineProperty(prototype, '__qsmAudioRecoveryPlayWrapped', {value: true});
      var original = prototype.play;
      prototype.play = function () {
        rememberMedia(this);
        return original.apply(this, arguments);
      };
    } catch (_) {
      // 某些页面会冻结媒体原型；扫描已存在元素仍可提供有限兜底。
    }
  }

  // 外层 H5 只负责定位 #game_if，不干预它自身的音频对象。
  if (!isTopFrame()) {
    captureConstructor('AudioContext');
    captureConstructor('webkitAudioContext');
    captureMediaPlayback();
  }

  function ignore(value) {
    try {
      if (value && typeof value.catch === 'function') value.catch(function () {});
    } catch (_) {}
  }

  function resume(record) {
    if (!record || !isLiveState(stateOf(record.context))) return;
    try { ignore(record.context.resume()); } catch (_) {}
  }

  function restart(record) {
    if (!record) return;
    var context = record.context;
    if (!isLiveState(stateOf(context))) return;
    if (stateOf(context) !== 'running' || typeof context.suspend !== 'function') {
      resume(record);
      return;
    }
    try {
      var paused = context.suspend();
      if (paused && typeof paused.then === 'function') paused.then(function () { resume(record); }, function () { resume(record); });
      else resume(record);
    } catch (_) {
      resume(record);
    }
  }

  function play(record) {
    if (!record) return;
    try {
      if (record.element && !record.element.ended && typeof record.element.play === 'function') {
        ignore(record.element.play());
      }
    } catch (_) {}
  }

  function appendUnique(target, records) {
    records.forEach(function (record) {
      if (target.indexOf(record) === -1) target.push(record);
    });
  }

  function snapshotContexts() {
    return contextRecords.filter(function (record) {
      return isRecoverableState(updateContextRecord(record));
    });
  }

  function fallbackContexts() {
    return contextRecords.filter(function (record) {
      var state = updateContextRecord(record);
      // 没有及时拿到后台快照时，只补偿已确实运行过的上下文；不唤醒从未解锁的音频。
      return isLiveState(state) && (record.hasRun || isRecoverableState(state));
    });
  }

  function snapshotMedia() {
    scanMedia();
    return mediaRecords.filter(function (record) {
      return mediaLooksPlaying(record) || record.wasPlaying;
    });
  }

  function snapshot() {
    if (!backgroundActive) {
      generation += 1;
      forgetTimers(recoveryTimers);
      forgetTimers(forwardingTimers);
      foregroundUntil = 0;
      gestureGeneration = -1;
      resumeContexts = [];
      resumeMedia = [];
      backgroundActive = true;
    }
    // visibilitychange 与原生信号的先后顺序不固定；同一轮后台只合并候选，避免后到信号抹掉早到快照。
    appendUnique(resumeContexts, snapshotContexts());
    appendUnique(resumeMedia, snapshotMedia());
    return generation;
  }

  function recover(revision) {
    if (revision !== generation) return;
    resumeContexts.slice().forEach(resume);
    resumeMedia.slice().forEach(play);
  }

  function scheduleRecovery(revision, delay) {
    var timer = window.setTimeout(function () { recover(revision); }, delay);
    recoveryTimers.push(timer);
  }

  function clockOf(record) {
    try {
      var value = Number(record.context.currentTime);
      return isFinite(value) ? value : null;
    } catch (_) {
      return null;
    }
  }

  function restartOnlyIfClockStalled(revision, samples) {
    if (revision !== generation) return;
    samples.forEach(function (sample) {
      var state = stateOf(sample.record.context);
      if (!isLiveState(state)) return;
      // 前几次 resume 仍未生效时，最后一次兜底仍要再试一次 resume。
      if (state !== 'running') {
        restart(sample.record);
        return;
      }
      var current = clockOf(sample.record);
      if (sample.time !== null && current !== null && current <= sample.time + 0.01) {
        restart(sample.record);
      }
    });
  }

  function restartAfterWebKitWakeup(revision) {
    if (revision !== generation) return;
    // WebKit 可能显示 running 且 currentTime 推进，但真实输出仍静音；约 5 秒后只做一次受控重启。
    resumeContexts.slice().forEach(restart);
    resumeMedia.slice().forEach(play);
  }

  function beginRecovery() {
    forgetTimers(recoveryTimers);
    var useFallback = !backgroundActive || sawVisibilityBackground;
    var revision = ++generation;
    backgroundActive = false;
    sawVisibilityBackground = false;
    if (!resumeContexts.length && useFallback) appendUnique(resumeContexts, fallbackContexts());
    if (!resumeMedia.length && useFallback) appendUnique(resumeMedia, snapshotMedia());
    if (!resumeContexts.length && !resumeMedia.length) return revision;
    var clockSamples = resumeContexts.map(function (record) { return {record: record, time: clockOf(record)}; });
    foregroundUntil = Date.now() + 8000;
    recover(revision);
    scheduleRecovery(revision, 250);
    scheduleRecovery(revision, 900);
    recoveryTimers.push(window.setTimeout(function () { restartOnlyIfClockStalled(revision, clockSamples); }, 2200));
    recoveryTimers.push(window.setTimeout(function () { restartAfterWebKitWakeup(revision); }, 5000));
    recoveryTimers.push(window.setTimeout(function () {
      if (revision === generation) foregroundUntil = 0;
    }, 8000));
    return revision;
  }

  function handleLocal(state) {
    return state === 'background' ? snapshot() : beginRecovery();
  }

  function packet(state, sequence) {
    var value = {state: state, sequence: sequence};
    value[messageKey] = 1;
    return value;
  }

  function postToDescendants(state, sequence, scheduleRetry) {
    function send() {
      if (sequence !== localLifecycleSequence) return;
      var value = packet(state, sequence);
      for (var index = 0; index < window.frames.length; index += 1) {
        try { window.frames[index].postMessage(value, '*'); } catch (_) {}
      }
    }
    send();
    if (state === 'foreground' && scheduleRetry) {
      [200, 800, 1600].forEach(function (delay) {
        forwardingTimers.push(window.setTimeout(send, delay));
      });
    }
  }

  function postToGameFrame(state, sequence, scheduleRetry) {
    function send() {
      if (sequence !== topLifecycleSequence) return;
      var frame;
      try { frame = document.getElementById(gameFrameID); } catch (_) { return; }
      if (!frame || !frame.contentWindow) return;
      try { frame.contentWindow.postMessage(packet(state, sequence), '*'); } catch (_) {}
    }
    send();
    // iframe 正在重载时，稍后再发一次，确保 document-start 脚本完成注册后仍能收到信号。
    if (state === 'foreground' && scheduleRetry) {
      [200, 800, 1600].forEach(function (delay) {
        forwardingTimers.push(window.setTimeout(send, delay));
      });
    }
  }

  function handleTopLifecycle(state) {
    var changed = topLifecycleState !== state;
    if (changed) {
      topLifecycleState = state;
      topLifecycleSequence += 1;
      forgetTimers(forwardingTimers);
    }
    postToGameFrame(state, topLifecycleSequence, changed);
  }

  window.addEventListener(lifecycleEvent, function (event) {
    var state = event && event.detail && event.detail.state;
    if (!isLifecycleState(state)) return;
    // 该事件只由原生投递到主 frame；防御性判断避免子 frame 误广播。
    if (isTopFrame()) handleTopLifecycle(state);
  });

  window.addEventListener('message', function (event) {
    var value = event && event.data;
    if (!value || value[messageKey] !== 1 || !isLifecycleState(value.state) || !isLifecycleSequence(value.sequence)) return;
    // 不信任其他 window 的同名消息；只接受自己的直接父 frame。
    if (event.source !== window.parent) return;
    controlledByParent = true;
    if (value.sequence !== localLifecycleSequence) {
      forgetTimers(forwardingTimers);
      localLifecycleSequence = value.sequence;
      handleLocal(value.state);
      postToDescendants(value.state, value.sequence, true);
    } else {
      // 同一轮重复消息只继续转发给可能刚加载的子 frame，不能重置本地恢复倒计时。
      postToDescendants(value.state, value.sequence, false);
    }
  });

  function handleVisibility() {
    var hidden = document.visibilityState === 'hidden';
    if (!isTopFrame() && hidden) {
      // 子 frame 的可见性事件是原生 evaluateJavaScript 未赶在冻结前执行时的关键备份。
      sawVisibilityBackground = true;
      snapshot();
      return;
    }
    if (isTopFrame()) handleTopLifecycle(hidden ? 'background' : 'foreground');
  }

  document.addEventListener('visibilitychange', handleVisibility);
  window.addEventListener('pagehide', function () {
    if (isTopFrame()) handleTopLifecycle('background');
  });
  window.addEventListener('pageshow', function () {
    if (isTopFrame()) handleTopLifecycle('foreground');
  });
  document.addEventListener('load', function (event) {
    if (!isTopFrame() || !topLifecycleState) return;
    var target = event && event.target;
    if (target && target.id === gameFrameID) postToGameFrame(topLifecycleState, topLifecycleSequence, false);
  }, true);

  function retryAfterGesture() {
    if (!controlledByParent || Date.now() > foregroundUntil || gestureGeneration === generation) return;
    gestureGeneration = generation;
    resumeContexts.slice().forEach(restart);
    resumeMedia.slice().forEach(play);
  }

  // 真实触摸发生在跨域 iframe 内时，只有注入到 iframe 自身的脚本才能可靠观察到。
  window.addEventListener('pointerdown', retryAfterGesture, true);
  window.addEventListener('touchend', retryAfterGesture, true);
})();
