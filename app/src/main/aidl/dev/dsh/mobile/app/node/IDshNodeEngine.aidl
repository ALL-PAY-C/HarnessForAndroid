// 主进程 → 引擎进程（:node）的跨进程接口。
//
// 存在意义：libnode 一个进程只能启动一次，stop() 杀不掉 node-main 线程，
// 引擎里的 process.exit 会带走整个宿主进程。把引擎关进 android:process=":node"
// 之后，这三件事都变成「主进程愿不愿意重开一个 :node 进程」的问题：
//   * 想换新的 Node 实例 → restart()（:node 自杀）→ binder death → 主进程重 bind；
//   * 引擎 process.exit → 只死 :node，主进程的 pid 不变；
//   * stop() 只断 socket，配合 restart() 才有「干净重启」。
//
// 线程模型（T9.14 修 4）：凡是会落到引擎上的方法（ensureStarted / call / rpc / stop）都是
// oneway —— binder 线程只做入队就返回，实际执行在服务内的专用单线程 executor 上，结果通过
// IDshNodeEngineResult 异步送回。同步方法只剩三个纯本地读/写的：restart / isRunning / setCallback。
//
// 失败的表达方式（全部在应答信封里，不再抛 RemoteException）：
//   * ensureStarted 失败 → {"ok":false,"error":{"stage":"start","message":"..."}}；
//   * call / rpc 失败 → {"ok":false,"error":{"stage":"...","message":"...","detail":{...}}}。
package dev.dsh.mobile.app.node;

import dev.dsh.mobile.app.node.IDshNodeEngineCallback;
import dev.dsh.mobile.app.node.IDshNodeEngineResult;

interface IDshNodeEngine {
    /** 幂等启动引擎；结果信封的 result.port 是实际监听端口（127.0.0.1）。 */
    oneway void ensureStarted(in String requestId, in String[] pluginDirs);

    /** 引擎通用的 call；应答 {"ok":true,"result":{...}} 或 {"ok":false,"error":{...}}。 */
    oneway void call(in String requestId, in String method, in String paramsJson);

    /** 转发到插件注册的 rpc 路径；应答格式同 call。 */
    oneway void rpc(in String requestId, in String path, in String endpoint, in String payloadJson);

    /** 只断 socket（与进程内语义一致），不杀 node-main 线程。 */
    oneway void stop(in String requestId);

    /** 让 :node 进程自杀（libnode 无法二次启动，换新实例只能换进程）。返回 0 后进程异步退出。 */
    int restart();

    boolean isRunning();

    /** 注册反向请求回调；null = 注销（引擎侧反向调用会立刻收到 stage=host 的错误）。 */
    void setCallback(in IDshNodeEngineCallback cb);

    /** 注册应答回调；主进程每次 oneway 调用前都带上新的 requestId。 */
    void setResultCallback(in IDshNodeEngineResult cb);
}
