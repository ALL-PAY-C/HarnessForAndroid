// 退出码探针：同时验证 stdout/stderr 捕获与 process.exit 只终止本次执行（不会拖死宿主引擎）。
const code = Number(process.argv[2] ?? '0');
process.stdout.write('out:' + code);
process.stderr.write('err:' + code);
process.exit(code);
