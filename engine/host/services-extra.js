// services-extra：补齐市场插件 inject 里常见、但移动端引擎此前没 provide 的服务。
//
// 诊断背景（.mock/tasks/B-host-services.md）：插件在 inject 里点名 fs / settings / systemPrompt /
// attachments / sessions / sessionPersistence / workspaceRegistry / jobs / timer / dshLoader，
// 宿主没 provide → cordis 认为依赖永不满足 → 插件不 apply，界面表现就是「加载失败 / 缺少服务」。
//
// 原则与 cordis.js 的 createHonestRegistry 一致：能真做的真做（fs/settings/systemPrompt/
// attachments/workspaceRegistry/jobs/timer 都有真实现），做不到的（会话数据在 Kotlin 侧）在调用时抛
// 带说明的 Error —— 绝不让 inject 挂起，也绝不伪造成功。只用 node 内置模块。
import { promises as fsp, readFileSync, existsSync } from 'node:fs';
import { dirname, isAbsolute, join, resolve as resolvePath, sep } from 'node:path';
import { randomBytes } from 'node:crypto';
// dshLoader 门面要转出的 dsh 模块级符号：全部来自 engine/node_modules，不自造实现。
import { defineTool, ToolArgsError } from '@deepseek-ai/dsh-tools';
import { deadline } from '@deepseek-ai/dsh-timeout';
import { credentialRef } from '@deepseek-ai/dsh-credentials';
import { BlockAssembler, createUserMessage, deepFreeze } from '@deepseek-ai/dsh-llm';

/** 运行期能力缺失 / 参数错误：stage=service 便于 logcat 与 stage=apply 区分。 */
function serviceError(service, message, extra = {}) {
  return Object.assign(new Error(service + ': ' + message), { stage: 'service', service, ...extra });
}

function notImplemented(service, method, hint) {
  return serviceError(service, method + ' 在移动端引擎没有实现：' + hint, { method, notImplemented: true });
}

/**
 * 未知方法的说明性错误包装：插件调用 label.不存在的名字() 时给出「支持哪些方法」，
 * 而不是裸 TypeError。then/toJSON 等探测属性与 symbol 一律放行，避免把服务变成 thenable。
 */
function guardUnknown(label, target) {
  return new Proxy(target, {
    get(t, key, receiver) {
      if (typeof key !== 'string' || key in t || key === 'then' || key === 'toJSON' || key === 'constructor') {
        return Reflect.get(t, key, receiver);
      }
      return function unknownCall() {
        throw serviceError(label, '不认识的调用 ' + label + '.' + key + '()；已支持：' + Object.keys(t).join('/'), { method: key });
      };
    },
  });
}

const homeOf = (options) => resolvePath(options.home ?? process.cwd());

// ── fs ────────────────────────────────────────────────────────────────────────
/**
 * node:fs/promises 的 home 沙箱包装 + dsh 的 FileSystem 接口（ctx.fs 的真实契约）。
 *
 * 两层都在一个对象上，因为插件市场两种用法都有：
 *  - node 风格：readFile/writeFile/readdir/stat/mkdir/rm/rename/exists/readJson…
 *  - dsh FileSystem：resolve(path) → target、listDir(target)、stat(target)、readText(target)、
 *    readBytes/writeText/mkdirp/remove/move（@deepseek-ai/dsh-skill-filesystem 就是这么用的：
 *    它 inject 只写 skills，但运行期 ctx.get('fs') 一旦非空就走 target 接口）。
 * 路径一律按 home 解析，越出 home 直接拒绝（code=FS_OUTSIDE_HOME）并说明。
 * 读文件默认 utf8；要 Buffer 传 { encoding: null } 或用 readBytes。
 */
export function createFsService(options = {}) {
  const root = homeOf(options);

  const inside = (method, input) => {
    if (input !== null && typeof input === 'object' && typeof input.path === 'string') return inside(method, input.path);
    if (typeof input !== 'string' || input === '') {
      throw serviceError('fs', method + ' 需要字符串路径或 resolve() 返回的 target', { method });
    }
    // ponytail: 只做词法越界判断，不 realpath —— home 下若有指向外部的符号链接仍可穿出去；
    // 需要硬隔离时升级为 realpath 前缀校验（代价是每次调用多一次 stat）。
    const abs = isAbsolute(input) ? resolvePath(input) : resolvePath(root, input);
    if (abs !== root && !abs.startsWith(root + sep)) {
      throw serviceError('fs', method + ' 拒绝访问 home 之外的路径 ' + JSON.stringify(input) + '（home=' + root + '）', {
        method,
        code: 'FS_OUTSIDE_HOME',
      });
    }
    return abs;
  };

  const targetOf = (abs) => ({
    key: abs,
    path: abs,
    displayPath: abs,
    uri: 'file://' + abs.replace(/\\/g, '/'),
    toString() { return abs; },
  });

  const typeOf = (stat) => (stat.isFile() ? 'file' : stat.isDirectory() ? 'directory' : stat.isSymbolicLink() ? 'symlink' : 'other');

  /** 统一 stat 形状：既有 node 的 isFile()/size，也有 dsh 要的 type 字段。 */
  const shapeStat = (stat) => ({
    type: typeOf(stat),
    size: stat.size,
    mode: stat.mode,
    mtimeMs: stat.mtimeMs,
    atimeMs: stat.atimeMs,
    ctimeMs: stat.ctimeMs,
    birthtimeMs: stat.birthtimeMs,
    mtime: stat.mtime,
    version: String(stat.mtimeMs),
    isFile: () => stat.isFile(),
    isDirectory: () => stat.isDirectory(),
    isSymbolicLink: () => stat.isSymbolicLink(),
  });

  const fs = {
    home: root,
    root,
    /** home 内的绝对路径字符串（越界即抛）。dsh 的 target 接口见 resolve()。 */
    toPath(input) { return inside('toPath', input); },
    abs(input) { return inside('abs', input); },
    /** dsh FileSystem：路径 → 不透明 target（target.path 是 home 内绝对路径）。 */
    async resolve(input) {
      return targetOf(inside('resolve', input));
    },
    async listDir(input) {
      const abs = inside('listDir', input);
      const entries = await fsp.readdir(abs, { withFileTypes: true });
      return entries.map((entry) => ({
        name: entry.name,
        type: entry.isDirectory() ? 'directory' : entry.isFile() ? 'file' : 'other',
        target: targetOf(join(abs, entry.name)),
      }));
    },
    async readFile(input, opts = 'utf8') {
      const encoding = typeof opts === 'string' ? opts : (opts?.encoding ?? 'utf8');
      return fsp.readFile(inside('readFile', input), encoding === null ? undefined : { encoding });
    },
    /** dsh FileSystem：只出 UTF-8 文本，二进制抛 code=FS_NOT_TEXT。 */
    async readText(input, signal) {
      const buffer = await fs.readBytes(input, signal);
      if (buffer.includes(0)) {
        throw serviceError('fs', 'readText 拒绝二进制文件（含 NUL 字节）：' + inside('readText', input), { method: 'readText', code: 'FS_NOT_TEXT' });
      }
      return buffer.toString('utf8');
    },
    async readBytes(input, signal) {
      if (signal?.aborted) signal.throwIfAborted?.();
      const buffer = await fsp.readFile(inside('readBytes', input));
      if (signal?.aborted) signal.throwIfAborted?.();
      return buffer;
    },
    async readJson(input) {
      return JSON.parse(await fs.readFile(input, 'utf8'));
    },
    async writeFile(input, data, opts = 'utf8') {
      const target = inside('writeFile', input);
      const encoding = typeof opts === 'string' ? opts : (opts?.encoding ?? 'utf8');
      // 插件常把对象直接丢进来：按 JSON 落盘（字符串/Buffer 原样写）。
      const payload = (data !== null && typeof data === 'object' && !Buffer.isBuffer(data)) ? JSON.stringify(data, null, 2) : data;
      await fsp.mkdir(dirname(target), { recursive: true });
      await fsp.writeFile(target, payload, encoding === null ? undefined : encoding);
      return targetOf(target);
    },
    writeText(input, text) { return fs.writeFile(input, text, 'utf8'); },
    writeJson(input, value) { return fs.writeFile(input, JSON.stringify(value, null, 2), 'utf8'); },
    async appendFile(input, data, opts = 'utf8') {
      const target = inside('appendFile', input);
      await fsp.mkdir(dirname(target), { recursive: true });
      await fsp.appendFile(target, data, typeof opts === 'string' ? opts : (opts ?? 'utf8'));
      return targetOf(target);
    },
    readdir(input = '.', opts) { return fsp.readdir(inside('readdir', input), opts); },
    async stat(input) { return shapeStat(await fsp.stat(inside('stat', input))); },
    async lstat(input) { return shapeStat(await fsp.lstat(inside('lstat', input))); },
    async mkdir(input, opts = {}) {
      const target = inside('mkdir', input);
      await fsp.mkdir(target, { recursive: true, ...(opts ?? {}) });
      return targetOf(target);
    },
    ensureDir(input) { return fs.mkdir(input); },
    mkdirp(input) { return fs.mkdir(input); },
    async rm(input, opts = {}) {
      const target = inside('rm', input);
      await fsp.rm(target, { recursive: true, force: true, ...(opts ?? {}) });
      return true;
    },
    rmdir(input) { return fs.rm(input); },
    unlink(input) { return fs.rm(input, { recursive: false }); },
    async remove(input) { return fs.rm(input); },
    async rename(from, to) {
      const src = inside('rename', from);
      const dst = inside('rename', to);
      await fsp.mkdir(dirname(dst), { recursive: true });
      await fsp.rename(src, dst);
      return targetOf(dst);
    },
    move(from, to) { return fs.rename(from, to); },
    async copyFile(from, to) {
      const src = inside('copyFile', from);
      const dst = inside('copyFile', to);
      await fsp.mkdir(dirname(dst), { recursive: true });
      await fsp.copyFile(src, dst);
      return targetOf(dst);
    },
    async exists(input) {
      try {
        await fsp.stat(inside('exists', input));
        return true;
      } catch {
        return false;
      }
    },
    async access(input) {
      try {
        await fsp.access(inside('access', input));
        return true;
      } catch {
        return false;
      }
    },
  };
  return fs;
}

// ── settings ─────────────────────────────────────────────────────────────────
/**
 * home/settings.json：get/set/all/delete，写入 tmp + rename 保证原子。
 * key 支持点号嵌套（'a.b'）；文件损坏时备份成 settings.json.corrupt-<ts> 再当空表用，不静默吞掉。
 */
export function createSettingsService(options = {}) {
  const home = homeOf(options);
  const file = options.file ?? join(home, 'settings.json');
  const logger = options.logger ?? null;
  let cache = null;

  const ensureLoaded = async () => {
    if (cache !== null) return cache;
    let text;
    try {
      text = await fsp.readFile(file, 'utf8');
    } catch (error) {
      if (error?.code === 'ENOENT') {
        cache = {};
        return cache;
      }
      throw serviceError('settings', '读取 ' + file + ' 失败：' + (error?.message ?? String(error)), { file, cause: error });
    }
    try {
      const parsed = JSON.parse(text);
      cache = parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
    } catch (error) {
      const backup = file + '.corrupt-' + Date.now();
      try {
        await fsp.rename(file, backup);
        logger?.warn?.('settings.json 解析失败，已备份到 ' + backup);
      } catch { /* 备份失败也不阻断：内容已在内存里丢弃 */ }
      cache = {};
    }
    return cache;
  };

  const segments = (key) => String(key).split('.').filter((part) => part !== '');

  const flush = async () => {
    const data = JSON.stringify(cache ?? {}, null, 2);
    const tmp = file + '.tmp-' + process.pid + '-' + randomBytes(4).toString('hex');
    await fsp.mkdir(dirname(file), { recursive: true });
    await fsp.writeFile(tmp, data, 'utf8');
    await fsp.rename(tmp, file);
    return file;
  };

  const settings = {
    file,
    path: file,
    async get(key) {
      const data = await ensureLoaded();
      if (key === undefined || key === null) return { ...data };
      const parts = segments(key);
      let cursor = data;
      for (const part of parts) {
        if (cursor === null || typeof cursor !== 'object') return undefined;
        cursor = cursor[part];
      }
      return cursor;
    },
    async has(key) { return (await settings.get(key)) !== undefined; },
    async all() { return { ...(await ensureLoaded()) }; },
    async set(key, value) {
      if (typeof key !== 'string' || key === '') throw serviceError('settings', 'settings.set 需要非空字符串 key');
      const data = await ensureLoaded();
      const parts = segments(key);
      let cursor = data;
      for (const part of parts.slice(0, -1)) {
        if (cursor[part] === null || typeof cursor[part] !== 'object') cursor[part] = {};
        cursor = cursor[part];
      }
      cursor[parts[parts.length - 1]] = value;
      await flush();
      return value;
    },
    async delete(key) {
      const data = await ensureLoaded();
      const parts = segments(key);
      let cursor = data;
      for (const part of parts.slice(0, -1)) {
        if (cursor === null || typeof cursor !== 'object') return false;
        cursor = cursor[part];
      }
      const last = parts[parts.length - 1];
      if (cursor === null || typeof cursor !== 'object' || !(last in cursor)) return false;
      delete cursor[last];
      await flush();
      return true;
    },
    async reload() {
      cache = null;
      return settings.all();
    },
  };
  return settings;
}

// ── systemPrompt ─────────────────────────────────────────────────────────────
/**
 * 文本贡献者注册表。插件写 systemPrompt 的三种常见形状都收：
 *   add('文本') / add(fn) / add({ name, text|content|value|prompt|contribute|render })
 * collected() 按注册顺序拼接，贡献者可以返回字符串或字符串数组（可 async）。
 */
export function createSystemPromptService(options = {}) {
  const entries = [];
  const nameOf = (entry, index) => entry.name ?? ('contributor-' + (index + 1));
  const add = (input, meta = {}) => {
    if (typeof input === 'string') {
      const entry = { name: meta.name ?? nameOf({}, entries.length), kind: 'text', value: input, order: entries.length };
      entries.push(entry);
      return () => { const i = entries.indexOf(entry); if (i >= 0) entries.splice(i, 1); };
    }
    if (typeof input === 'function') {
      const declared = meta.name || input.name || '';
      const entry = { name: declared !== '' ? declared : nameOf({}, entries.length), kind: 'fn', value: input, order: entries.length };
      entries.push(entry);
      return () => { const i = entries.indexOf(entry); if (i >= 0) entries.splice(i, 1); };
    }
    if (input !== null && typeof input === 'object') {
      const text = [input.text, input.content, input.value, input.prompt].find((v) => typeof v === 'string');
      const fn = [input.contribute, input.render, input.fn, input.get].find((v) => typeof v === 'function');
      if (text !== undefined || fn !== undefined) {
        const entry = {
          name: typeof input.name === 'string' ? input.name : nameOf({}, entries.length),
          kind: fn !== undefined ? 'fn' : 'text',
          value: fn !== undefined ? fn : text,
          order: entries.length,
        };
        entries.push(entry);
        return () => { const i = entries.indexOf(entry); if (i >= 0) entries.splice(i, 1); };
      }
    }
    throw serviceError('systemPrompt', 'add/register/contribute 需要字符串、函数或带 text/content/contribute 的对象', {
      method: 'add',
      received: typeof input,
    });
  };

  const collected = async () => {
    const pieces = [];
    for (const entry of entries) {
      let value;
      try {
        value = entry.kind === 'text' ? entry.value : await entry.value({});
      } catch (error) {
        throw serviceError('systemPrompt', '贡献者 ' + entry.name + ' 抛错：' + (error?.message ?? String(error)), {
          method: 'collected',
          cause: error,
        });
      }
      for (const piece of Array.isArray(value) ? value : [value]) {
        if (typeof piece === 'string' && piece.trim() !== '') pieces.push(piece);
      }
    }
    return pieces.join('\n\n');
  };

  const service = {
    entries,
    add,
    register: add,
    contribute: add,
    addText(text, meta = {}) {
      if (typeof text !== 'string') throw serviceError('systemPrompt', 'addText 需要字符串', { method: 'addText' });
      return add(text, meta);
    },
    addFunction(fn, meta = {}) {
      if (typeof fn !== 'function') throw serviceError('systemPrompt', 'addFunction 需要函数', { method: 'addFunction' });
      return add(fn, meta);
    },
    async collected() { return collected(); },
    async collect() { return collected(); },
    async text() { return collected(); },
    /**
     * section({ name, order, text | contribute })：桌面 @deepseek-ai/dsh-agent 的 systemPrompt 分段接口，
     * 市场插件（dsh-auxiliary 的 imagegen/vision 工具、@deepseek-ai/dsh-plan-mode）都用它登记提示词，
     * 返回 disposer。移动端没有分段模型，等价成「带 order 的 add + 按 order 稳定排序」。
     */
    section(input) {
      const declaredName = input !== null && typeof input === 'object' ? input.name : undefined;
      const before = entries.length;
      const dispose = add(input, { name: declaredName });
      if (entries.length === before + 1) {
        const entry = entries[entries.length - 1];
        entry.order = typeof input?.order === 'number' && Number.isFinite(input.order) ? input.order : entry.order;
        const sorted = [...entries].sort((left, right) => (left.order ?? 0) - (right.order ?? 0));
        entries.length = 0;
        entries.push(...sorted);
      }
      return dispose;
    },
    list() { return entries.map((entry) => ({ name: entry.name, kind: entry.kind })); },
    names() { return entries.map((entry) => entry.name); },
    clear() { entries.length = 0; },
  };
  return guardUnknown('systemPrompt', service);
}

// ── attachments ──────────────────────────────────────────────────────────────
/** home/attachments 下的附件目录视图：list()/get(id)/path(id)/read(id)。读不到就抛说明性错误。 */
export function createAttachmentsService(options = {}) {
  const home = homeOf(options);
  const dir = options.dir ?? join(home, 'attachments');

  const safeId = (method, id) => {
    if (typeof id !== 'string' || id === '' || id === '.' || id === '..' || id.includes('/') || id.includes('\\')) {
      throw serviceError('attachments', method + ' 需要 attachments 目录内的文件名，收到 ' + JSON.stringify(id), { method });
    }
    return id;
  };
  const targetOf = (method, id) => join(dir, safeId(method, id));

  const service = {
    dir,
    /** 附件目录不存在时返回空数组（未装任何附件是正常状态），其它 IO 错误照抛。 */
    async list(filter = {}) {
      let names;
      try {
        names = await fsp.readdir(dir);
      } catch (error) {
        if (error?.code === 'ENOENT') return [];
        throw serviceError('attachments', '读取附件目录 ' + dir + ' 失败：' + (error?.message ?? String(error)), { dir, cause: error });
      }
      const items = [];
      for (const name of names) {
        const stat = await fsp.stat(join(dir, name)).catch(() => null);
        if (stat === null || !stat.isFile()) continue;
        if (filter?.ext && !name.endsWith(filter.ext)) continue;
        items.push({ id: name, name, path: join(dir, name), size: stat.size, mtime: stat.mtimeMs });
      }
      return items;
    },
    async get(id) {
      const target = targetOf('get', id);
      const stat = await fsp.stat(target).catch(() => null);
      if (stat === null || !stat.isFile()) {
        throw serviceError('attachments', 'get: 附件 ' + JSON.stringify(id) + ' 不存在（目录 ' + dir + '）', { method: 'get', id });
      }
      return { id, name: id, path: target, size: stat.size, mtime: stat.mtimeMs };
    },
    path(id) { return targetOf('path', id); },
    async read(id, opts = 'utf8') {
      const target = targetOf('read', id);
      try {
        return await fsp.readFile(target, typeof opts === 'string' ? opts : (opts?.encoding ?? 'utf8'));
      } catch (error) {
        if (error?.code === 'ENOENT') {
          throw serviceError('attachments', 'read: 附件 ' + JSON.stringify(id) + ' 不存在（目录 ' + dir + '）', { method: 'read', id });
        }
        throw serviceError('attachments', 'read: 读取附件 ' + JSON.stringify(id) + ' 失败：' + (error?.message ?? String(error)), { method: 'read', id, cause: error });
      }
    },
    async exists(id) {
      try {
        return (await fsp.stat(targetOf('exists', id))).isFile();
      } catch {
        return false;
      }
    },
  };
  return service;
}

// ── sessions / sessionPersistence ────────────────────────────────────────────
/**
 * 会话数据在 Kotlin 侧（NativeEngineHost 的会话库），Node 只能通过 nativeCall 反向查。
 * 三个前置条件缺一就抛说明性错误：反向通道未注入 / 原生端没实现该方法 / 返回不是预期形状。
 */
function createNativeReader(service, host, hint) {
  return async (method, payload) => {
    if (!host || typeof host.nativeCall !== 'function') {
      throw serviceError(service, method + ' 需要宿主反向通道（nativeCall），当前引擎没有接入：' + hint, { method, stage: 'transport' });
    }
    try {
      return await host.nativeCall(method, payload ?? {});
    } catch (error) {
      throw serviceError(service, '原生端 ' + method + ' 失败：' + (error?.message ?? String(error)) + '（' + hint + '）', {
        method,
        cause: error,
        stage: 'transport',
      });
    }
  };
}

const asArray = (value) => (Array.isArray(value) ? value : (Array.isArray(value?.items) ? value.items : []));

export function createSessionsService(options = {}) {
  const host = options.host ?? null;
  const call = createNativeReader('sessions', host, 'Kotlin 端未实现 dsh.sessions.* 时就是这个错');
  return {
    async list(query = {}) { return asArray(await call('dsh.sessions.list', query)); },
    async get(id) {
      if (typeof id !== 'string' || id === '') throw serviceError('sessions', 'sessions.get 需要非空会话 id', { method: 'get' });
      return call('dsh.sessions.get', { id });
    },
    async messages(id, query = {}) {
      if (typeof id !== 'string' || id === '') throw serviceError('sessions', 'sessions.messages 需要非空会话 id', { method: 'messages' });
      return asArray(await call('dsh.sessions.messages', { id, ...query }));
    },
    async current() { return call('dsh.sessions.current', {}); },
    // 写操作明确不在 Node 侧：会话库归 Kotlin，避免插件以为改了内存就等于落库。
    save() { throw notImplemented('sessions', 'save', '会话写入归 Kotlin 会话库，Node 侧只做只读查询'); },
    create() { throw notImplemented('sessions', 'create', '新建会话要走原生端 UI / dsh.sessions.create，引擎侧未开放'); },
    delete() { throw notImplemented('sessions', 'delete', '删除会话归 Kotlin 会话库'); },
  };
}

export function createSessionPersistenceService(options = {}) {
  const host = options.host ?? null;
  const call = createNativeReader('sessionPersistence', host, 'Kotlin 端未实现 dsh.sessions.load 时就是这个错');
  return {
    async list(query = {}) { return asArray(await call('dsh.sessions.list', query)); },
    async load(id) {
      if (typeof id !== 'string' || id === '') throw serviceError('sessionPersistence', 'load 需要非空会话 id', { method: 'load' });
      return call('dsh.sessions.load', { id });
    },
    read(id) { return this.load(id); },
    async messages(id, query = {}) {
      if (typeof id !== 'string' || id === '') throw serviceError('sessionPersistence', 'messages 需要非空会话 id', { method: 'messages' });
      return asArray(await call('dsh.sessions.messages', { id, ...query }));
    },
    save() { throw notImplemented('sessionPersistence', 'save', '持久化由 Kotlin 会话库负责，Node 侧只读'); },
    append() { throw notImplemented('sessionPersistence', 'append', '持久化由 Kotlin 会话库负责，Node 侧只读'); },
  };
}

// ── workspaceRegistry ────────────────────────────────────────────────────────
/**
 * 工作目录登记表：纯内存 + 落 settings.json 的 workspaces 数组。
 * list() 返回路径字符串数组（插件最常这么用），要详情用 listDetails()/get(path)。
 */
export function createWorkspaceRegistryService(options = {}) {
  const home = homeOf(options);
  const settings = options.settings ?? null;
  const logger = options.logger ?? null;
  const entries = new Map();

  const normalize = (input) => {
    const raw = input && typeof input === 'object' ? (input.path ?? input.dir ?? input.root) : input;
    if (typeof raw !== 'string' || raw === '') {
      throw serviceError('workspaceRegistry', 'register 需要目录路径字符串或 { path } 对象', { method: 'register' });
    }
    return isAbsolute(raw) ? resolvePath(raw) : resolvePath(home, raw);
  };

  // settings 服务是 async 的，但插件会在同步代码里 list()：启动时同步读一次做种子。
  const seed = () => {
    if (!settings?.file) return;
    try {
      if (!existsSync(settings.file)) return;
      const parsed = JSON.parse(readFileSync(settings.file, 'utf8'));
      for (const item of Array.isArray(parsed?.workspaces) ? parsed.workspaces : []) {
        const path = typeof item === 'string' ? item : item?.path;
        if (typeof path === 'string' && path !== '' && !entries.has(path)) entries.set(path, { path, addedAt: item?.addedAt ?? null });
      }
    } catch { /* 种子读失败不影响运行，register 会重新落盘 */ }
  };
  seed();

  const persist = () => {
    if (settings === null) return;
    Promise.resolve()
      .then(() => settings.set('workspaces', [...entries.values()].map((entry) => entry.path)))
      .catch((error) => logger?.warn?.('workspaceRegistry 落盘失败：' + (error?.message ?? String(error))));
  };

  return {
    register(input) {
      const path = normalize(input);
      const existed = entries.has(path);
      if (!existed) entries.set(path, { path, addedAt: Date.now() });
      persist();
      return () => { if (!existed) { entries.delete(path); persist(); } };
    },
    unregister(input) {
      const removed = entries.delete(normalize(input));
      if (removed) persist();
      return removed;
    },
    has(input) { return entries.has(normalize(input)); },
    get(input) { return entries.get(normalize(input)); },
    list() { return [...entries.keys()]; },
    listDetails() { return [...entries.values()].map((entry) => ({ ...entry })); },
    clear() { entries.clear(); persist(); },
  };
}

// ── jobs ─────────────────────────────────────────────────────────────────────
/**
 * 内存任务表（run_bg 语义）：register(name, fn) / run(name, args) / cancel(id) / list()。
 * run() 返回 thenable，await 得到结果，同时带 .id / .record / .cancel()；
 * 失败时抛的 Error 带 jobId 与原因，任务表里留 failed 记录。
 */
export function createJobsService(options = {}) {
  const tasks = new Map();
  const jobs = new Map();
  let seq = 0;

  const cancel = (id) => {
    const record = jobs.get(id);
    if (record === undefined) throw serviceError('jobs', 'cancel 找不到任务 id ' + JSON.stringify(id), { method: 'cancel', id });
    if (record.status !== 'running') return false;
    record.status = 'cancelled';
    record.endedAt = Date.now();
    try { record.controller.abort(); } catch { /* 已 abort 过 */ }
    if (typeof record.cancelHook === 'function') {
      try { record.cancelHook(); } catch (error) { record.cancelError = error; }
    }
    return true;
  };

  const run = (name, args) => {
    if (typeof name !== 'string' || name === '') throw serviceError('jobs', 'run 需要任务名', { method: 'run' });
    const task = tasks.get(name);
    if (task === undefined) {
      throw serviceError('jobs', 'run 找不到已注册任务 ' + JSON.stringify(name) + '；已注册：' + ([...tasks.keys()].join(', ') || '(空)'), { method: 'run', name });
    }
    const id = 'job-' + (++seq);
    const controller = new AbortController();
    const list = args === undefined ? [] : (Array.isArray(args) ? args : [args]);
    const record = {
      id,
      name,
      status: 'running',
      startedAt: Date.now(),
      endedAt: null,
      args: list,
      signal: controller.signal,
      controller,
      result: undefined,
      error: null,
      cancel: () => cancel(id),
    };
    jobs.set(id, record);
    const promise = (async () => {
      try {
        const value = await task.fn(...list);
        if (record.status === 'running') {
          record.status = 'done';
          record.result = value;
          record.endedAt = Date.now();
        }
        return value;
      } catch (error) {
        record.endedAt = Date.now();
        if (record.status === 'running') record.status = 'failed';
        record.error = error;
        throw serviceError('jobs', '任务 ' + JSON.stringify(name) + ' 运行失败：' + (error?.message ?? String(error)), {
          method: 'run',
          jobId: id,
          cause: error,
        });
      }
    })();
    promise.id = id;
    promise.record = record;
    promise.cancel = () => cancel(id);
    return promise;
  };

  return {
    register(name, fn) {
      let taskName = name;
      let taskFn = fn;
      if (name !== null && typeof name === 'object') {
        taskName = name.name ?? name.id;
        taskFn = name.run ?? name.fn ?? name.handler ?? name.execute;
      }
      if (typeof taskName !== 'string' || taskName === '' || typeof taskFn !== 'function') {
        throw serviceError('jobs', 'register 需要 (name, fn) 或 { name, run }', { method: 'register' });
      }
      tasks.set(taskName, { name: taskName, fn: taskFn });
      return () => { if (tasks.get(taskName)?.fn === taskFn) tasks.delete(taskName); };
    },
    run,
    start: run,
    cancel,
    list() {
      return [...jobs.values()].map((record) => ({
        id: record.id,
        name: record.name,
        status: record.status,
        startedAt: record.startedAt,
        endedAt: record.endedAt,
        error: record.error ? String(record.error.message ?? record.error) : null,
      }));
    },
    get(id) { return jobs.get(id); },
    names() { return [...tasks.keys()]; },
    has(name) { return tasks.has(name); },
    remove(name) {
      const removed = tasks.delete(name);
      return removed;
    },
    cancelAll() {
      let count = 0;
      for (const record of jobs.values()) {
        if (record.status === 'running') { cancel(record.id); count += 1; }
      }
      return count;
    },
    clear() { jobs.clear(); },
  };
}

// ── timer ────────────────────────────────────────────────────────────────────
/**
 * setTimeout/setInterval 的封装：返回 { id, kind, cancel(), ref(), unref() } 句柄，
 * 回调里的异常只记日志不炸进程；dispose()（由 ctx.dispose 调用）与进程 exit 都会清干净。
 */
export function createTimerService(options = {}) {
  const logger = options.logger ?? null;
  const timers = new Map();
  let seq = 0;

  const invoke = (fn, args) => {
    try {
      const value = fn(...args);
      if (value && typeof value.catch === 'function') value.catch((error) => logger?.warn?.('timer 回调异步失败：' + (error?.message ?? String(error))));
    } catch (error) {
      logger?.warn?.('timer 回调失败：' + (error?.message ?? String(error)));
    }
  };

  const makeHandle = (kind, handle, id) => {
    const entry = {
      id,
      kind,
      cancel() {
        if (!timers.has(id)) return false;
        timers.delete(id);
        if (kind === 'interval') clearInterval(handle); else clearTimeout(handle);
        return true;
      },
      clear() { return entry.cancel(); },
      ref() { handle.ref?.(); return entry; },
      unref() { handle.unref?.(); return entry; },
      get active() { return timers.has(id); },
    };
    return entry;
  };

  const service = {
    setTimeout(fn, ms = 0, ...args) {
      if (typeof fn !== 'function') throw serviceError('timer', 'setTimeout 需要函数', { method: 'setTimeout' });
      const id = 'timer-' + (++seq);
      const handle = setTimeout(() => { timers.delete(id); invoke(fn, args); }, ms);
      const entry = makeHandle('timeout', handle, id);
      timers.set(id, entry);
      return entry;
    },
    setInterval(fn, ms = 0, ...args) {
      if (typeof fn !== 'function') throw serviceError('timer', 'setInterval 需要函数', { method: 'setInterval' });
      const id = 'timer-' + (++seq);
      const handle = setInterval(() => invoke(fn, args), ms);
      const entry = makeHandle('interval', handle, id);
      timers.set(id, entry);
      return entry;
    },
    /** 接受句柄或 id 字符串；未知 id 返回 false（清理路径不该抛）。 */
    clear(value) {
      const entry = value !== null && typeof value === 'object' ? value : timers.get(String(value));
      if (entry === undefined || typeof entry?.cancel !== 'function') return false;
      return entry.cancel();
    },
    clearTimeout(value) { return service.clear(value); },
    clearInterval(value) { return service.clear(value); },
    sleep(ms = 0) {
      return new Promise((resolve) => { service.setTimeout(resolve, ms); });
    },
    list() { return [...timers.keys()]; },
    size() { return timers.size; },
    dispose() {
      const count = timers.size;
      for (const entry of [...timers.values()]) entry.cancel();
      timers.clear();
      process.removeListener('exit', onExit);
      return count;
    },
  };

  const onExit = () => {
    for (const entry of [...timers.values()]) entry.cancel();
    timers.clear();
  };
  process.once('exit', onExit);
  return service;
}

// ── dshLoader 门面（.dsh / .llm / .settings） ────────────────────────────────
//
// 市场插件（dsh-auxiliary 0.6.4 等）拿 ctx.dshLoader 后解引用 dsh().tools.defineTool、
// dsh().compaction.BasicCompactionEngine 这类桌面内部面。移动端引擎没有桌面那套包，
// 能转出的直接转出（@deepseek-ai/dsh-tools|timeout|credentials|llm 都已在 dependencies），
// 转不出的给「带说明的成员」：调用即抛 code=DSH_UNSUPPORTED_MEMBER，绝不返回 undefined
// （undefined 会让插件在 `new undefined` 或解构时崩在没有信息量的 TypeError 上）。

/** 桌面内部面在移动端缺失时的说明性错误：带 code 与 member 名。 */
export function unsupportedMember(member, hint) {
  return Object.assign(new Error('dsh.' + member + ' 在移动端引擎没有实现：' + hint), {
    code: 'DSH_UNSUPPORTED_MEMBER',
    member,
  });
}

/**
 * delegationDepthOf(agent)：桌面按 fiber 链算出委派深度，移动端宿主没有 fiber 深度，
 * 因此优先读 agent 自带的 depth，再退到 parent 链逐级累加（防环、上限 64）。
 */
export function delegationDepthOf(agent) {
  if (agent === null || typeof agent !== 'object') return 0;
  const direct = agent.depth ?? agent.delegationDepth ?? agent.fiber?.depth;
  if (typeof direct === 'number' && Number.isFinite(direct)) return direct;
  const parentOf = (node) => {
    if (node === null || typeof node !== 'object') return null;
    return node.parent ?? node.parentAgent ?? node.session?.parent ?? node.session?.parentAgent ?? null;
  };
  let depth = 0;
  const seen = new Set([agent]);
  let cursor = parentOf(agent);
  while (cursor !== null && typeof cursor === 'object' && !seen.has(cursor) && depth < 64) {
    seen.add(cursor);
    depth += 1;
    cursor = parentOf(cursor);
  }
  return depth;
}

/**
 * BasicCompactionEngine：桌面 compaction 基类的构造壳。
 * dsh-auxiliary 会 `class CompressEngineImpl extends dsh().compaction.BasicCompactionEngine`
 * 并在 constructor 里 `super(ctx, config)` —— 所以本类**必须可构造且不抛**，
 * 真正做不到的只剩基类那两个方法（子类都覆写了；没覆写就抛说明性错误，不静默成功）。
 */
export class BasicCompactionEngine {
  constructor(ctx, config) {
    this.ctx = ctx;
    this.config = config;
    this.logger = ctx?.log ?? null;
  }

  compactIfNeeded() {
    throw unsupportedMember('compaction.BasicCompactionEngine.compactIfNeeded', '移动端引擎没有会话压缩后端，请在子类覆写该方法');
  }

  summarize() {
    throw unsupportedMember('compaction.BasicCompactionEngine.summarize', '移动端引擎没有会话压缩后端，请在子类覆写该方法');
  }
}

/**
 * .dsh 门面：扁平 8 个符号（任务书要求）＋ 命名空间形状（插件真实调用 dsh().tools.* 等）。
 * 两种形状指向同一批对象，谁按哪种拿都对。
 */
export function createDshFacade(ctx, options = {}) {
  const logger = options.logger ?? null;
  if (!ctx) throw serviceError('dshLoader', '创建 dsh 门面需要 context', { stage: 'apply' });
  const credentialsFacade = {
    credentialRef,
    /** 走 ctx.credentials.resolve：插件用 dsh().credentials 直接解析引用。 */
    resolve: (ref) => {
      const service = typeof ctx.get === 'function' ? ctx.get('credentials') : undefined;
      if (service === undefined || service === null || typeof service.resolve !== 'function') {
        throw serviceError('credentials', 'resolve 需要宿主提供 credentials 服务', { method: 'resolve' });
      }
      return service.resolve(ref);
    },
  };
  const toolsFacade = {
    defineTool,
    ToolArgsError,
  };
  const timeoutFacade = { deadline };
  const llmNs = { createUserMessage, deepFreeze, BlockAssembler };
  const subagentFacade = { delegationDepthOf };
  const compactionFacade = { BasicCompactionEngine };
  return {
    // 扁平形状（任务书列出的 8 个）
    defineTool,
    ToolArgsError,
    deadline,
    credentialRef,
    delegationDepthOf,
    BasicCompactionEngine,
    BlockAssembler,
    createUserMessage,
    // 命名空间形状（插件真实调用点）
    tools: toolsFacade,
    llm: llmNs,
    timeout: timeoutFacade,
    credentials: credentialsFacade,
    subagent: subagentFacade,
    compaction: compactionFacade,
    logger,
  };
}

/** loader.llm：桌面 dsh-auxiliary 的 dsh.js 会优先用它做 deepFreeze。 */
export function createDshLlmFacade() {
  return { createUserMessage, deepFreeze, BlockAssembler };
}

const parseSectionValue = (raw) => {
  if (typeof raw !== 'string') return raw;
  const text = raw.trim();
  if (text === '') return undefined;
  try { return JSON.parse(text); } catch { return undefined; }
};

const mergeSection = (entry, stored) => {
  const isPlain = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
  return isPlain(entry) && isPlain(stored) ? { ...entry, ...stored } : stored;
};

/**
 * loader.settings.installSection(ctx, ns, Config, entry, { setSource, onChange, validate })
 *
 * 桌面语义（见 @deepseek-ai/dsh-settings 的 installSettingsSection）：
 *  - 以组合入口 entry 为 base 层注册；setSource 交出的 source 是**同步 thunk**，
 *    插件会 current() 直接调用（不 await），所以这里返回闭包而不是 Promise；
 *  - settings 服务在 → source 指向持久化作用域；服务消失/fiber 卸载 → 回退 entry 并 onChange；
 *  - 挂 scoped fiber（ctx.effect），fiber dispose 时自动清理；
 *  - 返回 disposer（幂等），不返回 undefined。
 * 移动端 settings 只有 key-value + async get，没有 register/watch，所以用「读一次 + 闭包状态」等价实现。
 */
export function createDshSettingsFacade(options = {}) {
  const logger = options.logger ?? null;

  const installSection = (ctx, ns, Config, entry, hooks = {}) => {
    if (typeof ns !== 'string' || ns === '') {
      throw Object.assign(new TypeError('settings.installSection 需要非空 namespace'), { stage: 'apply' });
    }
    const setSource = typeof hooks.setSource === 'function' ? hooks.setSource : () => {};
    const onChange = typeof hooks.onChange === 'function' ? hooks.onChange : () => {};
    const validate = typeof hooks.validate === 'function' ? hooks.validate : null;
    const state = { value: entry, disposed: false };

    const notify = () => {
      if (state.disposed) return;
      try { onChange(); } catch (error) {
        logger?.warn?.('settings.installSection(' + ns + ') onChange 抛错：' + (error?.message ?? String(error)));
      }
    };
    const runValidate = (value) => {
      if (validate === null) return;
      try { validate(value); } catch (error) {
        // 桌面在注册时校验失败会挡住加载；移动端先记录，仍然把服务装好（否则界面整块不可用）。
        logger?.warn?.('settings.installSection(' + ns + ') validate 抛错：' + (error?.message ?? String(error)));
      }
    };

    runValidate(entry);
    setSource(() => state.value);
    notify();

    const settings = typeof ctx?.get === 'function' ? ctx.get('settings') : undefined;
    if (settings !== undefined && settings !== null && typeof settings.get === 'function') {
      Promise.resolve()
        .then(() => settings.get(ns))
        .then((raw) => {
          if (state.disposed) return undefined;
          const stored = parseSectionValue(raw);
          if (stored === undefined || stored === null) return undefined;
          const next = mergeSection(entry, stored);
          runValidate(next);
          state.value = next;
          notify();
          return undefined;
        })
        .catch((error) => {
          logger?.warn?.('settings.installSection(' + ns + ') 读取持久化配置失败，沿用组合入口：' + (error?.message ?? String(error)));
        });
    }

    const dispose = () => {
      if (state.disposed) return false;
      state.disposed = true;
      state.value = entry;
      try { setSource(() => entry); } catch (error) {
        logger?.warn?.('settings.installSection(' + ns + ') dispose setSource 抛错：' + (error?.message ?? String(error)));
      }
      try { onChange(); } catch { /* dispose 路径不再二次抛 */ }
      return true;
    };

    // scoped fiber：cordis 的 effect 立即执行回调并登记其返回值作为清理函数。
    if (typeof ctx?.effect === 'function') ctx.effect(() => dispose);
    return dispose;
  };

  return { installSection };
}

/** 把 .dsh / .llm / .settings 挂到 loader 服务上（原地挂，保住 ctx.loader === ctx.dshLoader）。 */
export function installDshLoaderFacade(loaderService, options = {}) {
  if (loaderService === null || typeof loaderService !== 'object') {
    throw serviceError('loader', 'installDshLoaderFacade 需要 loader 服务对象', { stage: 'apply' });
  }
  // 装门面需要 context 才能取到 credentials；装配期还拿不到就把 resolve 留成惰性（等 install 后再补）。
  loaderService.dsh = createDshFacade(options.ctx ?? { get: () => undefined }, { logger: options.logger });
  loaderService.llm = createDshLlmFacade();
  loaderService.settings = createDshSettingsFacade({ logger: options.logger });
  let boundFacade = loaderService.dsh;
  // context 晚于服务提供时重建一次门面，让 dsh().credentials.resolve 走真 credentials。
  loaderService.bindContext = (ctx) => {
    boundFacade = createDshFacade(ctx, { logger: options.logger });
    loaderService.dsh = boundFacade;
    loaderService.llm = createDshLlmFacade();
    return loaderService;
  };
  return loaderService;
}

const credentialKeyOf = (ref) => {
  if (typeof ref === 'string') return ref;
  if (ref === null || typeof ref !== 'object') return null;
  for (const key of ['key', 'name', 'id', 'ref', 'value']) {
    if (typeof ref[key] === 'string' && ref[key] !== '') return ref[key];
  }
  return null;
};

/**
 * credentials.resolve / describe / unset：在 llm.js 的 credentials 服务上**原地**补方法。
 * 不能对象展开复制 —— 那个对象的 degraded 是 getter，复制会把它冻成快照，
 * test/host-llm-bridge.test.mjs 依赖它随降级变化。
 *
 * resolve 契约对齐 @deepseek-ai/dsh-credentials：ResolvedCredential { value, source }；
 * 取不到返回 undefined（不抛）；空串等于未配置（seam 规则）。
 */
export function installCredentialResolve(service, options = {}) {
  const logger = options.logger ?? null;
  const env = options.env ?? process.env;
  if (service === null || typeof service !== 'object') return service;
  const get = typeof service.get === 'function' ? service.get.bind(service) : async () => null;

  const resolve = async (ref) => {
    const key = credentialKeyOf(ref);
    if (key === null || key === '') return undefined;
    let stored = null;
    try {
      stored = await get(key);
    } catch (error) {
      logger?.warn?.('credentials.resolve(' + key + ') 读存储失败：' + (error?.message ?? String(error)));
      stored = null;
    }
    if (typeof stored === 'string' && stored !== '') return { value: stored, source: 'store' };
    const fromEnv = env?.[key];
    if (typeof fromEnv === 'string' && fromEnv !== '') return { value: fromEnv, source: 'env' };
    return undefined;
  };

  service.resolve = resolve;
  service.describe = async (ref) => {
    const hit = await resolve(ref);
    return hit === undefined ? { configured: false, writable: true } : { configured: true, source: hit.source, writable: true };
  };
  if (typeof service.unset !== 'function' && typeof service.delete === 'function') {
    service.unset = (ref) => {
      const key = credentialKeyOf(ref);
      if (key === null || key === '') return Promise.resolve(false);
      return service.delete(key);
    };
  }
  return service;
}
