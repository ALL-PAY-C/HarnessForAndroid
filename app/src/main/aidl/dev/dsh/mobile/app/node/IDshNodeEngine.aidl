// 主进程 → 引擎进程（:node）的跨进程接口。
//
// 存在意义：libnode 一个进程只能启动一次，stop() 杀不掉 node-main 线程，
// 引擎里的 process.exit 会带走整个宿主进程。把引擎关进 android:process=":node"
// 之后，这三件事都变成「主进程愿不愿意重开一个 :node 进程」的问题：
//   * 想换新的 Node 实例 → restart()（:node 自杀）→ binder death → 主进程重 bind；
//   * 引擎 process.exit → 只死 :node，主进程的 pid 不变；
//   * stop() 只断 socket，配合 restart() 才有「干净重启」。
//
// 失败的表达方式：
//   * ensureStarted 启动失败 → 抛 RemoteException，message 就是给用户看的原因；
//   * call / rpc 失败 → 返回信封 JSON，保留 stage / message / detail（见 IDshNodeEngineCallback）。
package dev.dsh.mobile.app.node;

import dev.dsh.mobile.app.node.IDshNodeEngineCallback;

interface IDshNodeEngine {
    /** 幂等启动引擎，返回实际监听端口（127.0.0.1）。失败抛 RemoteException。 */
    int ensureStarted(in String[] pluginDirs);

    /** 引擎通用的 call；返回 {"ok":true,"result":{...}} 或 {"ok":false,"error":{...}}。 */
    String call(in String method, in String paramsJson);

    /** 转发到插件注册的 rpc 路径；返回信封格式同 call。 */
    String rpc(in String path, in String endpoint, in String payloadJson);

    /** 只断 socket（与进程内语义一致），不杀 node-main 线程。 */
    void stop();

    /** 让 :node 进程自杀（libnode 无法二次启动，换新实例只能换进程）。返回 0 后进程异步退出。 */
    int restart();

    boolean isRunning();

    /** 注册反向请求回调；null = 注销（引擎侧反向调用会立刻收到 stage=host 的错误）。 */
    void setCallback(in IDshNodeEngineCallback cb);
}
