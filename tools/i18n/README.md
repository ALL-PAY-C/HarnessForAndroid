# i18n 资源生成与检查

- `catalog.json` — 唯一字符串目录，条目 `{key, en, zh, where}`；`zh` 必须与抽取前的字面量逐字一致。
- `node tools/i18n/gen.mjs` — 由目录生成 `design/src/main/res/values/strings.xml`（英文默认）+ `values-zh/strings.xml`。
- `python tools/i18n-lint.py` — 门禁：检查 `tools/i18n-surface.txt` 列出的文件没有未登记的 CJK 字面量（exit 0/1/2）。
- `python tools/i18n-lint.py --census` — 全仓统计，不判失败。
- `python tools/i18n-lint.py --file <路径>` — 单文件明细（行号/字面量/是否已登记）。
- `i18n-allowlist.txt` — 豁免：`路径<TAB>字面量<TAB>理由`（仅限数据比对、引擎下发文案等非 UI 文案）。
