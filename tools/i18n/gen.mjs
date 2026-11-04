import fs from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

// 用法：node tools/i18n/gen.mjs   （cwd 任意，输出到 design/src/main/res）
const here = path.dirname(fileURLToPath(import.meta.url));
const app = path.resolve(here, "../..");
const catPath = path.join(here, "catalog.json");
const cat = JSON.parse(fs.readFileSync(catPath, "utf8"));
const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/'/g, "\\'").replace(/"/g, '\\"');
// aapt2 会静默吃掉未加引号的字符串首尾空白：分隔符 " · "、前置空格 " (tap to request)" 这类
// 值必须用 "..." 包起来，否则运行时拼接处直接黏在一起（实测环境页出现 "Tap,Swipe"）。
const value = (s) => { const v = esc(s); return /^\s|\s$/.test(s) ? '"' + v + '"' : v; };
const xml = (lang) => {
  const body = cat.map((c) => '    <string name="' + c.key + '">' + value(lang === "en" ? c.en : c.zh) + "</string>").join("\n");
  const head = lang === "en"
    ? "英文（默认回退语言）：英文资源缺失时按此回退，中文走 values-zh。"
    : "中文：与原中文字面量逐字一致，改文案必须两边同时改。";
  return '<?xml version="1.0" encoding="utf-8"?>\n<!-- T9.11 i18n ' + head + ' -->\n<resources>\n' + body + "\n</resources>\n";
};
fs.writeFileSync(path.join(app, "design/src/main/res/values/strings.xml"), xml("en"), "utf8");
fs.writeFileSync(path.join(app, "design/src/main/res/values-zh/strings.xml"), xml("zh"), "utf8");
console.log("generated " + cat.length + " strings");
