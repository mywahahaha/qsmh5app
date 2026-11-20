const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../Resources/audio-recovery.js'), 'utf8');

class FakeAudioContext {
  constructor() {
    this.state = 'running';
    this.resumeCalls = 0;
    this.suspendCalls = 0;
    this.currentTime = 0;
  }
  resume() {
    this.resumeCalls += 1;
    this.state = 'running';
    return Promise.resolve();
  }
  suspend() {
    this.suspendCalls += 1;
    this.state = 'suspended';
    return Promise.resolve();
  }
}

function frame(parent, AudioContextClass = FakeAudioContext, options = {}) {
  const listeners = new Map();
  const documentListeners = new Map();
  const elements = new Map();
  const mediaElements = [];
  const timers = [];
  const window = {
    frames: [],
    parent: parent || null,
    top: parent ? parent.top : null,
    AudioContext: AudioContextClass,
    addEventListener(name, callback) {
      const values = listeners.get(name) || [];
      values.push(callback);
      listeners.set(name, values);
    },
    setTimeout(callback, delay) {
      const timer = {callback, delay, cancelled: false};
      timers.push(timer);
      return timer;
    },
    clearTimeout(timer) {
      timer.cancelled = true;
    }
  };
  if (!parent) {
    window.parent = window;
    window.top = window;
  }
  if (options.media) {
    class FrameMediaElement {
      constructor() {
        this.paused = true;
        this.ended = false;
        this.playCalls = 0;
        this.listeners = new Map();
      }
      addEventListener(name, callback) {
        const values = this.listeners.get(name) || [];
        values.push(callback);
        this.listeners.set(name, values);
      }
      emit(name) {
        for (const callback of this.listeners.get(name) || []) callback();
      }
      play() {
        this.playCalls += 1;
        this.paused = false;
        this.emit('play');
        this.emit('playing');
        return Promise.resolve();
      }
      pause() {
        this.paused = true;
        this.emit('pause');
      }
    }
    window.HTMLMediaElement = FrameMediaElement;
    window.__createMedia = () => {
      const element = new FrameMediaElement();
      mediaElements.push(element);
      return element;
    };
  }
  const document = {
    visibilityState: 'visible',
    addEventListener(name, callback) {
      const values = documentListeners.get(name) || [];
      values.push(callback);
      documentListeners.set(name, values);
    },
    getElementById(id) {
      return elements.get(id) || null;
    },
    querySelectorAll(selector) {
      return selector === 'audio,video' ? mediaElements : [];
    }
  };
  window.document = document;
  window.__emit = (name, event = {}) => {
    for (const callback of listeners.get(name) || []) callback(event);
  };
  window.__emitDocument = (name, event = {}) => {
    for (const callback of documentListeners.get(name) || []) callback(event);
  };
  window.__runTimers = (maximumDelay = Infinity) => {
    const due = timers.filter(timer => !timer.cancelled && timer.delay <= maximumDelay);
    for (const timer of due) {
      timer.cancelled = true;
      timer.callback();
    }
  };
  window.__attach = (id, child) => {
    window.frames.push(child);
    elements.set(id, {contentWindow: child});
    child.postMessage = (data) => child.__emit('message', {data, source: window});
  };
  const context = vm.createContext({window, document, Date, Promise, Proxy, Reflect});
  vm.runInContext(source, context);
  return window;
}

test('脚本不暴露 Bridge、方向通知或业务数据', () => {
  for (const forbidden of ['webkit.messageHandlers', 'QSMH5APP', 'screenChange', 'token']) {
    assert.equal(source.includes(forbidden), false, forbidden);
  }
  assert.match(source, /AudioContext/);
  assert.match(source, /game_if/);
});

test('只恢复后台前运行的 game_if 音频上下文，并忽略其他顶层 iframe', async () => {
  const top = frame();
  const game = frame(top);
  const nestedGame = frame(game);
  const other = frame(top);
  top.__attach('game_if', game);
  game.__attach('engine_if', nestedGame);
  top.__attach('banner_if', other);

  const outerAudio = new top.AudioContext();
  const running = new game.AudioContext();
  const paused = new game.AudioContext();
  paused.state = 'suspended';
  const nestedRunning = new nestedGame.AudioContext();
  const unrelated = new other.AudioContext();

  top.__emit('__qsmAudioLifecycle', {detail: {state: 'background'}});
  top.__emit('__qsmAudioLifecycle', {detail: {state: 'foreground'}});
  other.document.visibilityState = 'hidden';
  other.__emitDocument('visibilitychange');
  other.document.visibilityState = 'visible';
  other.__emitDocument('visibilitychange');
  running.currentTime = 1;
  game.__runTimers(2200);
  nestedGame.__runTimers(2200);
  await Promise.resolve();

  assert.equal(outerAudio.resumeCalls, 0);
  assert.ok(running.resumeCalls >= 3);
  assert.equal(paused.resumeCalls, 0);
  assert.ok(nestedRunning.resumeCalls >= 3);
  assert.equal(unrelated.resumeCalls, 0);
  assert.equal(running.suspendCalls, 0, 'game iframe 时钟已推进，不应重启');
});

test('子 iframe 的可见性快照可弥补退后台原生脚本未执行', async () => {
  const top = frame();
  const game = frame(top);
  top.__attach('game_if', game);
  const context = new game.AudioContext();

  game.document.visibilityState = 'hidden';
  game.__emitDocument('visibilitychange');
  context.state = 'suspended';
  // 只收到前台信号，模拟 sceneWillResignActive 的 evaluateJavaScript 已来不及执行。
  top.__emit('__qsmAudioLifecycle', {detail: {state: 'foreground'}});
  game.__runTimers(900);
  await Promise.resolve();

  assert.ok(context.resumeCalls >= 3);
});

test('约五秒后即使时钟推进也会进行一次受控 Web Audio 重启', async () => {
  const top = frame();
  const game = frame(top);
  top.__attach('game_if', game);
  const context = new game.AudioContext();

  top.__emit('__qsmAudioLifecycle', {detail: {state: 'background'}});
  top.__emit('__qsmAudioLifecycle', {detail: {state: 'foreground'}});
  context.currentTime = 1;
  top.__runTimers(1600);
  game.__runTimers(5000);
  await Promise.resolve();
  await Promise.resolve();

  assert.equal(context.suspendCalls, 1);
});

test('只重试后台前正在播放的 HTML 媒体', async () => {
  const top = frame();
  const game = frame(top, FakeAudioContext, {media: true});
  top.__attach('game_if', game);
  const playing = game.__createMedia();
  const neverPlayed = game.__createMedia();
  await playing.play();

  top.__emit('__qsmAudioLifecycle', {detail: {state: 'background'}});
  playing.pause();
  top.__emit('__qsmAudioLifecycle', {detail: {state: 'foreground'}});
  game.__runTimers(900);
  await Promise.resolve();

  assert.ok(playing.playCalls >= 4, '首次播放之外，前台应进行三次快速重试');
  assert.equal(neverPlayed.playCalls, 0);
});

test('仅在 Web Audio 时钟仍停滞时执行一次 suspend/resume 兜底', async () => {
  const top = frame();
  const game = frame(top);
  top.__attach('game_if', game);
  const context = new game.AudioContext();

  top.__emit('__qsmAudioLifecycle', {detail: {state: 'background'}});
  top.__emit('__qsmAudioLifecycle', {detail: {state: 'foreground'}});
  game.__runTimers(2200);
  await Promise.resolve();
  await Promise.resolve();

  assert.equal(context.suspendCalls, 1);
  assert.ok(context.resumeCalls >= 2);
});

test('AudioContext 仍为 suspended 时，最终兜底继续尝试 resume', async () => {
  class StuckAudioContext extends FakeAudioContext {
    resume() {
      this.resumeCalls += 1;
      return Promise.resolve();
    }
  }
  const top = frame();
  const game = frame(top, StuckAudioContext);
  top.__attach('game_if', game);
  const context = new game.AudioContext();

  top.__emit('__qsmAudioLifecycle', {detail: {state: 'background'}});
  context.state = 'suspended';
  top.__emit('__qsmAudioLifecycle', {detail: {state: 'foreground'}});
  game.__runTimers(2200);
  await Promise.resolve();

  assert.ok(context.resumeCalls >= 4);
  assert.equal(context.suspendCalls, 0);
});

test('子 frame 拒绝非父窗口的生命周期消息', async () => {
  const top = frame();
  const game = frame(top);
  top.__attach('game_if', game);
  const context = new game.AudioContext();

  top.__emit('__qsmAudioLifecycle', {detail: {state: 'background'}});
  const callsBefore = context.resumeCalls;
  const attacker = frame();
  game.__emit('message', {
    data: {__qsmAudioLifecycleMessage: 1, state: 'foreground'},
    source: attacker
  });
  await Promise.resolve();

  assert.equal(context.resumeCalls, callsBefore);
});
