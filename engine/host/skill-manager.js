// 内置技能管理器：没装外部 dsh-skills-manager 插件时，「技能中心」也能开箱用。
//
// 契约照抄桌面插件（.mock/npmpkg/dsh-skills-manager/package/lib/index.js），所以宿主
// SkillCatalog.kt 的解析、SkillCenterScreen 的 set-enabled 调用都不用改：
//  - 私有技能库 $DSH_HOME/skill-manager/.agents/skills，来源锁 skills-lock.json 由外部
//    插件写、这里不碰；
//  - 状态 $DSH_HOME/skill-manager/state.json = {version:1, globalEnabled:[name],
//    projectEnabled:{root:[name]}, modelInvocation:{name:bool}}；
//  - rpc /skill-manager 的 endpoint 名与 {ok:true, value:{entries, projectRoots}} 回包同形。
//
// 只用 node 自带能力（不调 skills CLI、不加 npm 依赖）：技能只接受 <name>/SKILL.md 目录形态，
// 复制、frontmatter 解析、启用状态全部在这里做完。
//
// ponytail: 与外部插件的差异只有三处，都有意为之——
//  1. 不做 git/remote 来源：install 只吃本地目录（宿主已经先把 zip 解压到 staging 再传进来）；
//  2. 不写 skills-lock.json、不提供 check-updates，entries[].updateSupported 恒 false；
//  3. 不在 ctx.skills 上注册 provider（技能中心只读 rpc），模型侧技能表仍由外部插件负责。
//     要接模型侧：装上 dsh-skills-manager 插件后它会覆盖同名 rpc 路径，行为整个回到插件。
import { randomUUID } from 'node:crypto';
import { copyFile, mkdir, readdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import { join, resolve, sep } from 'node:path';

const EMPTY_STATE = { version: 1, globalEnabled: [], projectEnabled: {}, modelInvocation: {} };

/** bundlesIn 的发现边界：4 层够覆盖 skills/<name> 与 .agents/skills/<name> 这类真实布局。 */
const MAX_BUNDLE_DEPTH = 4;
/** 判死线而不是性能优化：不设上限时误传仓库根目录会把整个 node_modules 走一遍。 */
const MAX_SCANNED_DIRS = 4000;
const MAX_BUNDLES = 200;
/** 这些目录里不会有人放技能，且体量最大（.git / node_modules / 构建产物）。 */
const SKIPPED_DIRS = new Set(['node_modules', '.git', 'dist', 'build']);
/** SKILL.md 回给模型的长度上限：一个技能动辄几十 KB，整篇塞进上下文会挤掉本轮真正要用的东西。 */
const MAX_SKILL_CHARS = 20000;

/** state.json 里只认这三个字段；写坏/字段缺失都不该让技能中心白屏。 */
function normalizeState(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('state.json 不是对象');
  if (value.version !== 1) throw new Error('state.json 版本不是 1');
  if (!Array.isArray(value.globalEnabled) || typeof value.projectEnabled !== 'object' || value.projectEnabled === null) {
    throw new Error('state.json 缺 globalEnabled / projectEnabled');
  }
  const cleanNames = (names) => [...new Set(names.filter((item) => typeof item === 'string' && item !== ''))];
  const projectEnabled = {};
  for (const [root, names] of Object.entries(value.projectEnabled)) {
    if (Array.isArray(names)) projectEnabled[root] = cleanNames(names);
  }
  const modelInvocation = {};
  for (const [name, flag] of Object.entries(value.modelInvocation ?? {})) {
    if (typeof flag === 'boolean') modelInvocation[name] = flag;
  }
  return { version: 1, globalEnabled: cleanNames(value.globalEnabled), projectEnabled, modelInvocation };
}

/** 全局启用对所有 cwd 生效；项目启用对项目根及其子目录生效。 */
function isEnabledFor(state, name, projectRoot) {
  if (state.globalEnabled.includes(name)) return true;
  if (projectRoot === undefined) return false;
  return Object.entries(state.projectEnabled).some(([root, names]) => (
    names.includes(name) && (projectRoot === root || projectRoot.startsWith(root + sep))
  ));
}

/** 名字只用来拼路径：拦掉分隔符、控制字符和 . / ..，其余（含中文）照收。 */
function assertSafeName(raw) {
  if (typeof raw !== 'string' || raw.trim() === '') {
    throw Object.assign(new Error('缺少技能名 name'), { stage: 'rpc' });
  }
  const name = raw.trim();
  if (name === '.' || name === '..' || /[\\/\u0000-\u001f]/.test(name)) {
    throw Object.assign(new Error('技能名不合法: ' + JSON.stringify(raw)), { stage: 'rpc' });
  }
  return name;
}

/** SKILL.md 的 frontmatter：只取平铺的 key: value，嵌套字段（allowed-tools 之类）忽略。 */
function parseFrontmatter(text) {
  const match = /^\uFEFF?---\r?\n([\s\S]*?)\r?\n---\r?\n?/.exec(text);
  if (match === null) return { fields: {}, body: text };
  const fields = {};
  for (const line of match[1].split(/\r?\n/)) {
    const entry = /^([A-Za-z0-9_.-]+)\s*:\s*(.*)$/.exec(line);
    if (entry === null) continue;
    let value = entry[2].trim();
    if (value.length >= 2 && ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'")))) {
      value = value.slice(1, -1);
    }
    if (value !== '') fields[entry[1].toLowerCase()] = value;
  }
  return { fields, body: text.slice(match[0].length) };
}

/** 没有 description 时退到正文第一行非标题文字，技能中心才有内容显示。 */
function bodySummary(body) {
  for (const line of body.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (trimmed === '' || trimmed.startsWith('#') || trimmed.startsWith('<!--')) continue;
    return trimmed.replace(/^[-*+]\s+/, '').slice(0, 240);
  }
  return '';
}

async function isDirectory(path) {
  try { return (await stat(path)).isDirectory(); } catch { return false; }
}

async function findSkillFile(dir) {
  let entries;
  try { entries = await readdir(dir, { withFileTypes: true }); } catch { return null; }
  const hit = entries.find((entry) => entry.isFile() && entry.name.toLowerCase() === 'skill.md');
  return hit === undefined ? null : join(dir, hit.name);
}

/** 技能包只复制真实文件：符号链接可能指向库外，直接跳过。 */
async function copyTree(from, to) {
  await mkdir(to, { recursive: true });
  for (const entry of await readdir(from, { withFileTypes: true })) {
    if (entry.name === '.git' || entry.name === 'node_modules') continue;
    const source = join(from, entry.name);
    const target = join(to, entry.name);
    if (entry.isSymbolicLink()) continue;
    if (entry.isDirectory()) await copyTree(source, target);
    else if (entry.isFile()) await copyFile(source, target);
  }
}

class SkillManager {
  constructor(options) {
    this.home = options.home;
    this.logger = options.logger ?? null;
    this.root = options.storageDir?.trim() ? resolve(options.storageDir.trim()) : join(this.home, 'skill-manager');
    this.library = join(this.root, '.agents', 'skills');
    this.stateFile = join(this.root, 'state.json');
    // 独立标记文件而不是塞进 state.json：外部插件的 normalizeState 会把多余字段洗掉，
    // 混用时会导致「每次都当首启」把用户关掉的技能重新点亮。
    this.bootstrapMarker = join(this.root, '.builtin-bootstrapped');
    this.state = EMPTY_STATE;
    this.loaded = null;
  }

  /** 首启扫描 + 建目录；失败只记日志，不阻塞引擎启动（list 时会再试一次）。 */
  warm() {
    this.load().catch((error) => this.warn('初始化失败: ' + (error?.message ?? error)));
  }

  load() {
    if (this.loaded !== null) return this.loaded;
    this.loaded = this.loadOnce().catch((error) => {
      this.loaded = null;
      throw error;
    });
    return this.loaded;
  }

  async loadOnce() {
    await mkdir(this.library, { recursive: true });
    let bootstrapped = false;
    try {
      await stat(this.bootstrapMarker);
      bootstrapped = true;
    } catch {
      bootstrapped = false;
    }
    try {
      this.state = normalizeState(JSON.parse(await readFile(this.stateFile, 'utf8')));
    } catch (error) {
      if (error?.code !== 'ENOENT') this.warn('state.json 读不了，按空状态继续: ' + (error?.message ?? error));
      this.state = EMPTY_STATE;
    }
    if (bootstrapped) return;
    // 首启：目录不存在已建好，已有技能自动点亮全局启用（之后新增/导入的默认仍是关闭）。
    // 这里必须用 scan()：candidates() 会 await this.load()，正在跑的 load 等自己 = 死锁。
    const found = await this.scan();
    if (found.length > 0) {
      this.state = { ...this.state, globalEnabled: [...new Set([...this.state.globalEnabled, ...found.map((item) => item.name)])].sort() };
      await this.persist();
    }
    await writeFile(this.bootstrapMarker, new Date().toISOString() + '\n', 'utf8');
  }

  warn(message) {
    if (this.logger !== null) this.logger.warn(message);
  }

  async persist() {
    await mkdir(this.root, { recursive: true });
    const temp = this.stateFile + '.tmp-' + randomUUID();
    await writeFile(temp, JSON.stringify(this.state, null, 2) + '\n', 'utf8');
    await rename(temp, this.stateFile);
  }

  /** 库里的技能 = <library>/<name>/SKILL.md（大小写不敏感，与插件一致）。 */
  async candidates() {
    await this.load();
    return this.scan();
  }

  /** 纯扫描，不碰 load 状态：loadOnce 自己也用。 */
  async scan() {
    let entries;
    try {
      entries = await readdir(this.library, { withFileTypes: true });
    } catch {
      return [];
    }
    const found = [];
    for (const entry of entries) {
      if (!entry.isDirectory()) continue;
      const dir = join(this.library, entry.name);
      const skillFile = await findSkillFile(dir);
      if (skillFile === null) continue;
      const parsed = parseFrontmatter(await readFile(skillFile, 'utf8'));
      const name = typeof parsed.fields.name === 'string' ? parsed.fields.name : entry.name;
      const description = typeof parsed.fields.description === 'string' ? parsed.fields.description : bodySummary(parsed.body);
      const declared = parsed.fields['disable-model-invocation'];
      found.push({
        name,
        dir,
        description,
        invocation: { modelInvocable: !(declared === 'true' || declared === 'yes') },
      });
    }
    return found;
  }

  invocationFor(skill) {
    const override = this.state.modelInvocation[skill.name];
    return override === undefined ? skill.invocation : { ...skill.invocation, modelInvocable: override };
  }

  async catalog(projectRoot) {
    const entries = (await this.candidates()).sort((a, b) => a.name.localeCompare(b.name)).map((skill) => ({
      name: skill.name,
      description: skill.description,
      globalEnabled: this.state.globalEnabled.includes(skill.name),
      projectEnabled: projectRoot !== undefined && (this.state.projectEnabled[projectRoot] ?? []).includes(skill.name),
      effectiveEnabled: isEnabledFor(this.state, skill.name, projectRoot),
      updateSupported: false,
      modelInvocable: this.invocationFor(skill).modelInvocable,
    }));
    return { entries, projectRoots: Object.keys(this.state.projectEnabled).sort() };
  }

  /**
   * 按名字读 SKILL.md 原文，给模型侧的 skill 工具用。
   *
   * name 只拿去和 scan() 的名单比对，绝不参与拼路径：否则 name 里塞 ../ 就能把任意文件
   * 读进模型上下文。真实路径一律取自扫描结果。
   */
  async readSkill(rawName) {
    const name = typeof rawName === 'string' ? rawName.trim() : '';
    if (name === '') throw Object.assign(new Error('缺少技能名 name'), { stage: 'rpc' });
    const skill = (await this.candidates()).find((item) => item.name === name);
    if (skill === undefined) throw UNKNOWN_SKILL(name);
    const file = await findSkillFile(skill.dir);
    if (file === null) {
      throw Object.assign(new Error('技能缺少 SKILL.md: ' + JSON.stringify(name)), { stage: 'rpc' });
    }
    let bytes;
    try {
      bytes = await readFile(file);
    } catch (error) {
      throw Object.assign(
        new Error('技能说明读取失败: ' + JSON.stringify(name) + '：' + (error?.message ?? error)),
        { stage: 'rpc' },
      );
    }
    let content;
    try {
      // fatal 而不是默认替换：二进制/GBK 文件被替换成一堆问号塞给模型，比直接报错更误导。
      content = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    } catch {
      throw Object.assign(new Error('技能说明不是 UTF-8 文本: ' + JSON.stringify(name)), { stage: 'rpc' });
    }
    // 有的技能写上几万字：整篇进上下文会挤掉本轮真正要用的东西，截断并在末尾明确告知。
    const clipped = content.length > MAX_SKILL_CHARS
      ? content.slice(0, MAX_SKILL_CHARS) + '\n…（已截断）'
      : content;
    return { name: skill.name, description: skill.description, content: clipped, path: file };
  }

  /**
   * staging 目录 → 技能包目录列表，有界递归发现。
   *
   * 现实里的技能仓库布局五花八门：<name>/SKILL.md（obra/superpowers 是 skills/<name>/SKILL.md，
   * anthropics/skills 与 vercel-labs/agent-skills 也是两层嵌套，已解压的 DSH_HOME 又是
   * .agents/skills/<name>/SKILL.md）。只认固定几层会把这些全漏掉，所以改成往下走最多
   * MAX_BUNDLE_DEPTH 层：谁身上有 SKILL.md 谁就是一个技能包，且不再深入它内部
   * （技能包里可能还带示例 SKILL.md，进去会多出幽灵技能）。
   * 上限是为了防呆：zip 炸弹、node_modules 深坑、误传仓库根目录都能给出明确错误而不是跑死。
   */
  async bundlesIn(source) {
    if (!await isDirectory(source)) {
      throw Object.assign(new Error('技能来源必须是含 SKILL.md 的目录，收到: ' + source), { stage: 'rpc' });
    }
    const found = [];
    let visited = 0;
    const walk = async (dir, depth) => {
      if (found.length >= MAX_BUNDLES) {
        throw Object.assign(
          new Error('技能来源里的技能包超过 ' + MAX_BUNDLES + ' 个，拒绝继续: ' + source),
          { stage: 'rpc' },
        );
      }
      visited += 1;
      if (visited > MAX_SCANNED_DIRS) {
        throw Object.assign(
          new Error('技能来源目录数超过 ' + MAX_SCANNED_DIRS + '，拒绝继续: ' + source),
          { stage: 'rpc' },
        );
      }
      if (await findSkillFile(dir) !== null) {
        found.push(dir);
        return;
      }
      if (depth >= MAX_BUNDLE_DEPTH) return;
      let entries;
      try {
        entries = await readdir(dir, { withFileTypes: true });
      } catch {
        return;
      }
      for (const entry of [...entries].sort((a, b) => a.name.localeCompare(b.name))) {
        if (!entry.isDirectory() || SKIPPED_DIRS.has(entry.name)) continue;
        await walk(join(dir, entry.name), depth + 1);
      }
    };
    await walk(source, 0);
    if (found.length === 0) {
      throw Object.assign(new Error('技能来源里没有 <name>/SKILL.md: ' + source), { stage: 'rpc' });
    }
    return found;
  }

  /**
   * staging 目录 → 技能库，一次可以装多个。
   *
   * 返回值里 installed / skipped 是同批次的明细：
   *  - installed：真落盘的技能名，顺序即复制顺序；
   *  - skipped：同一批里重名的后来者（两个目录 frontmatter 都写 name: x 时先到先得）；
   *  - name：单技能安装的兼容字段（多技能时取第一个），旧调用方只认这个。
   * 跨批次重装同名技能仍然是覆盖（先 rm 再 copy），与外部插件一致。
   * entries / projectRoots 继续带上，技能中心装完可以直接刷列表、不算两趟 rpc。
   */
  async install(sourcePath, projectRoot) {
    if (typeof sourcePath !== 'string' || sourcePath.trim() === '') {
      throw Object.assign(new Error('缺少技能来源 path'), { stage: 'rpc' });
    }
    await this.load();
    const bundles = await this.bundlesIn(resolve(sourcePath.trim()));
    const installed = [];
    const skipped = [];
    for (const bundle of bundles) {
      const skillFile = await findSkillFile(bundle);
      const parsed = parseFrontmatter(await readFile(skillFile, 'utf8'));
      const dirName = bundle.split(sep).filter(Boolean).at(-1) ?? '';
      const name = assertSafeName(typeof parsed.fields.name === 'string' ? parsed.fields.name : dirName);
      if (installed.includes(name)) {
        skipped.push(name);
        continue;
      }
      const target = join(this.library, name);
      await rm(target, { recursive: true, force: true });
      await copyTree(bundle, target);
      installed.push(name);
    }
    // 新导入的技能默认关闭（与外部插件一致），装完由用户自己打开开关。
    const catalog = await this.catalog(projectRoot);
    return {
      installed,
      skipped,
      name: installed[0] ?? '',
      entries: catalog.entries,
      projectRoots: catalog.projectRoots,
    };
  }

  /** 返回技能目录：frontmatter 的 name 可能和目录名不一致，删的必须是真目录。 */
  async assertInstalled(name) {
    const skill = (await this.candidates()).find((item) => item.name === name);
    if (skill === undefined) throw Object.assign(new Error('技能未安装: ' + name), { stage: 'rpc' });
    return skill.dir;
  }

  async setEnabled(rawName, scope, projectRoot, enabled) {
    const name = assertSafeName(rawName);
    if (scope !== 'global' && scope !== 'project') {
      throw Object.assign(new Error('scope 必须是 global 或 project'), { stage: 'rpc' });
    }
    if (typeof enabled !== 'boolean') {
      throw Object.assign(new Error('enabled 必须是布尔值'), { stage: 'rpc' });
    }
    if (scope === 'project' && (typeof projectRoot !== 'string' || projectRoot.trim() === '')) {
      throw Object.assign(new Error('scope=project 时必须有 projectRoot'), { stage: 'rpc' });
    }
    await this.load();
    await this.assertInstalled(name);
    const toggle = (names) => (enabled
      ? [...new Set([...names, name])]
      : names.filter((item) => item !== name));
    if (scope === 'global') {
      this.state = { ...this.state, globalEnabled: toggle(this.state.globalEnabled) };
    } else {
      const root = resolve(projectRoot);
      const projectEnabled = { ...this.state.projectEnabled };
      const next = toggle(projectEnabled[root] ?? []);
      if (next.length === 0) delete projectEnabled[root];
      else projectEnabled[root] = next;
      this.state = { ...this.state, projectEnabled };
    }
    await this.persist();
    return this.catalog(projectRoot === undefined ? undefined : resolve(projectRoot));
  }

  async remove(rawName, projectRoot) {
    const name = assertSafeName(rawName);
    await this.load();
    const dir = await this.assertInstalled(name);
    await rm(dir, { recursive: true, force: true });
    const projectEnabled = {};
    for (const [root, names] of Object.entries(this.state.projectEnabled)) {
      const kept = names.filter((item) => item !== name);
      if (kept.length > 0) projectEnabled[root] = kept;
    }
    const modelInvocation = { ...this.state.modelInvocation };
    delete modelInvocation[name];
    this.state = {
      version: 1,
      globalEnabled: this.state.globalEnabled.filter((item) => item !== name),
      projectEnabled,
      modelInvocation,
    };
    await this.persist();
    return this.catalog(projectRoot === undefined ? undefined : resolve(projectRoot));
  }
}

function optionalString(value) {
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : undefined;
}

const UNKNOWN_ENDPOINT = (endpoint) => Object.assign(
  new Error('未知 skill-manager endpoint: ' + JSON.stringify(endpoint)),
  { stage: 'rpc' },
);

/** 名字不在扫描名单里：报错而不是按名字去磁盘上找，避免入参变成文件读取能力。 */
const UNKNOWN_SKILL = (name) => Object.assign(
  new Error('未知技能: ' + JSON.stringify(name)),
  { stage: 'rpc' },
);

/** endpoint 表与桌面插件一致；enable/disable 是 set-enabled 的简写（internal 任务要求）。 */
async function handleRpc(manager, endpoint, payload) {
  const args = payload !== null && typeof payload === 'object' && !Array.isArray(payload) ? payload : {};
  switch (endpoint) {
    case 'list':
      return { ok: true, value: await manager.catalog(optionalString(args.projectRoot)) };
    case 'install':
      return { ok: true, value: await manager.install(args.path, optionalString(args.projectRoot)) };
    case 'set-enabled':
      return {
        ok: true,
        value: await manager.setEnabled(args.name, args.scope, optionalString(args.projectRoot), args.enabled),
      };
    case 'enable':
    case 'disable':
      return {
        ok: true,
        value: await manager.setEnabled(args.name, 'global', undefined, endpoint === 'enable'),
      };
    case 'read':
      return { ok: true, value: await manager.readSkill(args.name) };
    case 'remove':
      return { ok: true, value: await manager.remove(args.name, optionalString(args.projectRoot)) };
    default:
      throw UNKNOWN_ENDPOINT(endpoint);
  }
}

/**
 * 在宿主 context 上挂 /skill-manager。必须在插件加载之前调用：
 * 插件（真 dsh-skills-manager）后注册同名路径会覆盖这里，外部插件优先。
 */
export function installSkillManager(ctx, options = {}) {
  const connection = ctx.get('connection');
  if (connection?.rpc?.handle === undefined) {
    throw Object.assign(new Error('context 上没有 connection.rpc 服务'), { stage: 'apply' });
  }
  const home = options.home ?? ctx.get('home') ?? process.cwd();
  const manager = new SkillManager({ home, logger: options.logger ?? null, storageDir: options.storageDir });
  const dispose = connection.rpc.handle('/skill-manager', (endpoint, payload) => handleRpc(manager, endpoint, payload));
  manager.warm();
  return { manager, dispose };
}

export { SkillManager, parseFrontmatter, normalizeState, isEnabledFor };
