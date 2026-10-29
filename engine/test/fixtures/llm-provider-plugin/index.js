// 最小样例：一个 provider 插件如何通过 ctx.llm 出模型。
//
// 三种用法，按需要选一种：
//   1) 只声明模型（models()）——让宿主知道这个 provider 能用哪些模型，出模型仍走 Kotlin 网关：
//        ctx.llm.registerProvider('demo', { models: () => ['demo-model'] })
//        await ctx.llm.chat({ messages: [...] })      // → 转发 Kotlin 的 llm.chat
//   2) 自带传输（chat()）——没有 Kotlin 网关时（桌面调试 / 单测）由插件自己出模型：
//        ctx.llm.registerProvider('local', { chat: async (req) => ({ content: '...' }) })
//   3) 只用宿主网关，不注册 provider：直接 ctx.llm.chat(...)，resolve() 问宿主有哪些模型。
//
// 关键点：插件拿不到密钥，也不该拿。密钥在 Kotlin 侧（ModelStore / PluginCredentialStore），
// ctx.credentials 只是让插件存自己那份第三方 key（如某个搜索 API）。

export const name = 'dsh-llm-provider-fixture';
export const inject = ['llm', 'credentials'];

export function apply(ctx) {
  ctx.llm.registerProvider('fixture', {
    models: () => ['fixture-model'],
    // 只有宿主没有注入 nativeCall 时才会走到这里（第 2 种用法）。
    chat: async (request) => ({
      content: 'fixture:' + (request.messages?.[0]?.content ?? ''),
    }),
  });

  // 插件自检入口：宿主可以用 rpc 调 /llm-fixture 拿到一次真实结果。
  const connection = ctx.get('connection');
  connection?.rpc.handle('/llm-fixture', async (endpoint) => {
    if (endpoint === 'providers') return { providers: ctx.llm.names() };
    if (endpoint === 'chat') {
      const result = await ctx.llm.chat({ messages: [{ role: 'user', content: 'ping' }] });
      return { result };
    }
    if (endpoint === 'secret') {
      await ctx.credentials.set('fixture.token', 'demo');
      return { value: await ctx.credentials.get('fixture.token') };
    }
    return { error: 'unknown endpoint' };
  });
}
