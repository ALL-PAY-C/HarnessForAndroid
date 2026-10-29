// 子进程语义探针：垫片必须让脚本在 Worker 里看到与真实 node 子进程一致的 argv / cwd / env。
process.stdout.write(JSON.stringify({
  argv: process.argv,
  cwd: process.cwd(),
  noColor: process.env.NO_COLOR ?? null,
  onlyInChild: process.env.DSH_ONLY_IN_CHILD ?? null,
  execPath: process.execPath,
}));
