// 引擎进程（:node）→ 主进程 的**应答**通道（T9.14 修 4）。
//
// 为什么要有这个方向：:node 里的引擎调用（ensureStarted / call / rpc / stop）动辄几百毫秒到
// 几分钟，以前 AIDL 方法是同步的，一条调用就占住一条 binder 线程；binder 池只有 16 条，
// 并发一多就互相饿死（连反向的 handleHostCall 都排不上）。
//
// 现在主进程的调用全部是 oneway（立刻返回），真正的结果从这条通道异步送回：
// requestId 由主进程生成，envelopeJson 与旧同步返回值逐字符同构 ——
// {"ok":true,"result":{...}} 或 {"ok":false,"error":{"stage":"...","message":"..."}}。
package dev.dsh.mobile.app.node;

interface IDshNodeEngineResult {
    oneway void onResult(in String requestId, in String envelopeJson);
}
