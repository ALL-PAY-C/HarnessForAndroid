// 引擎进程（:node）→ 主进程 的反向请求通道。
//
// 为什么要有这个方向：模型网关、密钥、工具桥都在主进程的 Kotlin 侧，插件在 :node 里跑
// ctx.llm.chat / ctx.credentials.* 时必须回到主进程。单向调用、同步返回，与进程内
// HostCallHandler 的语义一一对应。
//
// 返回值是一个信封 JSON：{"ok":true,"result":{...}} 或
// {"ok":false,"error":{"stage":"host","message":"..."}}。
// 这样 stage 与 detail 这些 NodeEngineException 的现场信息不会在 AIDL 边界上丢失。
package dev.dsh.mobile.app.node;

interface IDshNodeEngineCallback {
    String handleHostCall(String method, String paramsJson);
}
