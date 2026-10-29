// Android app_process 的 argv 解析模拟器（host 夹具；按 AOSP app_process.cpp 的规则解析，只把
// "起 JVM + JNI FindClass" 换成可观测的崩溃签名，不是伪造成功）。
//
// 规则（frameworks/base/cmds/app_process/app_process.cpp main()）:
//   argv[0]                          程序名（app_process64），解析前先 argc--/argv++ 丢掉
//   以 '-' 开头的参数                VM 选项（-Djava.class.path=... 等），交给 JVM
//   ++i; // Skip unused "parent dir" 选项扫描结束处那个参数被无条件丢弃（历史遗留的 "parent dir"）
//   --zygote / --start-system-server / --application / --nice-name=X / --setuid= / --setgid=
//   第一个其它参数                    启动类名 className；其后全部是 main(String[]) 的实参
//   className 为空                     "Error: no class name or --zygote supplied."（exit != 0）
//   className 非真实 JVM 类           JNI FindClass 抛 ClassNotFoundException → AndroidRuntime abort(SIGABRT)
//
// ponytail: 只实现与本 bug 相关的分支（选项扫描 + parent dir 跳过 + 类名取第一个未知参数）；
// -cp/-classpath 的 "spaced command" 一并处理，其余 AddOption 细节不影响 argv 语义。
//
// 用法: node app-process-sim.mjs <完整 argv>   (argv[0] = app_process 程序名)
// 输出: 一行 JSON {vmOptions, parentDir, className, mainArgs, exitCode, crash}

import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

export function parseAppProcessArgv(argv) {
  const result = { vmOptions: [], parentDir: null, className: null, mainArgs: [], exitCode: 0, crash: null };
  const args = argv.slice(1); // ignore argv[0]
  let i = 0;
  // 1) 前导 '-' 参数 = VM 选项；-cp/-classpath 后面那个参数也算选项块
  while (i < args.length) {
    const arg = String(args[i]);
    if (arg === '-cp' || arg === '-classpath') {
      result.vmOptions.push(arg, String(args[i + 1] ?? ''));
      i += 2;
      continue;
    }
    if (!arg.startsWith('-')) break;
    result.vmOptions.push(arg);
    i += 1;
  }
  if (i >= args.length) {
    result.exitCode = 1;
    result.crash = 'Error: no class name or --zygote supplied.';
    return result;
  }
  // 2) 第一个非 '-' 参数被当成 parent dir 丢弃
  result.parentDir = String(args[i]);
  i += 1;
  // 3) 尾部选项；第一个未知参数 = 启动类名
  const known = /^--(zygote$|start-system-server$|application$|nice-name=|setuid=|setgid=)/;
  for (; i < args.length; i += 1) {
    const arg = String(args[i]);
    if (known.test(arg)) continue;
    result.className = arg;
    result.mainArgs = args.slice(i + 1);
    break;
  }
  if (result.className === null) {
    result.exitCode = 1;
    result.crash = 'Error: no class name or --zygote supplied.';
    return result;
  }
  // 4) 类名不做任何文件/JS 校验就进 FindClass；这个进程里不存在该 Java 类
  result.exitCode = 134; // SIGABRT
  result.crash = 'java.lang.ClassNotFoundException: ' + result.className;
  return result;
}

const entry = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : null;
if (entry && entry === import.meta.url) {
  process.stdout.write(JSON.stringify(parseAppProcessArgv(process.argv.slice(2))) + '\n');
}
