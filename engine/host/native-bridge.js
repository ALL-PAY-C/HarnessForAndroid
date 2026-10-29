// native-bridge：引擎 → Kotlin 的反向请求通道（复用同一条 JSON 行 socket）。
//
// 行协议里 Kotlin 一直是客户端：{"id","method","params"} → {"id","ok","result"}。
// 但 llm/credentials 必须回到 Kotlin（模型网关、SecretStore 都在那边），所以补一个反向方向：
//   引擎 -> 宿主  {"id":"h1","method":"host.call","params":{"method":"llm.chat","payload":{...}}}
//   宿主 -> 引擎  {"id":"h1","ok":true,"result":{...}}  /  {"id":"h1","ok":false,"error":{"stage","message"}}
// 两个方向的 id 空间不重叠（引擎请求用 h 前缀），所以同一条 socket 上不会串。
// 读线程仍然只有 bin.js 的 handleLine：带 ok 字段的行交给 bridge.consume()，剩下的才是宿主请求。

const DEFAULT_TIMEOUT_MS = 120_000;

export function createNativeBridge(logger) {
  let socket = null;
  let seq = 0;
  const pending = new Map();

  const failAll = (reason) => {
    for (const [id, waiter] of pending) {
      pending.delete(id);
      waiter.reject(reason);
    }
  };

  return {
    get connected() {
      return socket !== null && !socket.destroyed && !socket.writableEnded;
    },
    attach(s) {
      socket = s;
      if (logger) logger.debug('宿主反向通道已连接');
    },
    detach(s) {
      if (socket !== s) return;
      socket = null;
      failAll(Object.assign(new Error('宿主反向通道已断开'), { stage: 'transport' }));
    },
    /**
     * 消费一行宿主回复。
     * @returns true 表示这行属于反向通道（已被处理），false 表示是宿主发给引擎的请求。
     */
    consume(message) {
      if (!message || typeof message !== 'object') return false;
      if (message.ok === undefined) return false;
      const waiter = pending.get(message.id);
      if (waiter === undefined) return false;
      pending.delete(message.id);
      if (message.ok) {
        waiter.resolve(message.result ?? {});
      } else {
        const stage = message.error?.stage ?? 'rpc';
        const text = message.error?.message ?? '宿主调用失败';
        waiter.reject(Object.assign(new Error(text), { stage }));
      }
      return true;
    },
    /** 反向调用 Kotlin。没有 socket 时立刻失败，让插件拿到明确原因而不是挂着。 */
    call(method, payload, timeoutMs = DEFAULT_TIMEOUT_MS) {
      const s = socket;
      if (s === null || s.destroyed || s.writableEnded) {
        return Promise.reject(Object.assign(new Error('宿主反向通道未连接（' + method + '）'), { stage: 'transport' }));
      }
      const id = 'h' + (++seq);
      const line = JSON.stringify({ id, method: 'host.call', params: { method, payload: payload ?? {} } });
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          pending.delete(id);
          reject(Object.assign(new Error('宿主调用超时 ' + method + '（' + timeoutMs + 'ms）'), { stage: 'transport' }));
        }, timeoutMs);
        if (typeof timer.unref === 'function') timer.unref();
        pending.set(id, {
          resolve: (value) => { clearTimeout(timer); resolve(value); },
          reject: (error) => { clearTimeout(timer); reject(error); },
        });
        try {
          s.write(line + '\n');
        } catch (error) {
          pending.delete(id);
          clearTimeout(timer);
          reject(Object.assign(new Error('写反向通道失败: ' + error.message), { stage: 'transport' }));
        }
      });
    },
  };
}
