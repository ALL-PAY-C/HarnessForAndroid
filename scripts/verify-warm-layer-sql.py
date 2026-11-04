#!/usr/bin/env python3
"""T9.8 S1 校验：把 Db.kt / WarmLayerDao.kt 里的 SQL 字面量抠出来，在真 SQLite 上跑。

本模块没有 Robolectric，JVM 单测里的 SQLite 是桩，所以 11->12 迁移与两条回捞查询
只能在这里验：用 Room 导出的 schemas/11.json 建一个 v11 库，灌入 sessions/messages
行，再执行 MIGRATION_11_12 的原始 DDL，断言老表行数不变、warm_layers 可写可读；
随后原样执行 bySessionRange / searchInSession 的 SQL，验闭区间与 LIKE 命中。

用法：python scripts/verify-warm-layer-sql.py
"""
import json
import os
import re
import sqlite3
import sys

APP = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DB_KT = os.path.join(APP, "core/data/src/main/kotlin/dev/dsh/mobile/core/data/db/Db.kt")
DAO_KT = os.path.join(APP, "core/data/src/main/kotlin/dev/dsh/mobile/core/data/db/WarmLayerDao.kt")
SCHEMA_11 = os.path.join(APP, "core/data/schemas/dev.dsh.mobile.core.data.db.DshDatabase/11.json")

failures = []


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + (" | " + detail if detail else ""))
    if not ok:
        failures.append(name)


def unescape_kotlin(s):
    out, i = [], 0
    table = {"n": "\n", "t": "\t", '"': '"', "\\": "\\", "`": "`", "$": "$"}
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            out.append(table.get(s[i + 1], s[i + 1]))
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def sql_calls(path, marker, stop):
    """抠出 marker..stop 区间里每一个 db.execSQL(...) 的 SQL（含 + 拼接的多段字面量）。"""
    src = open(path, encoding="utf-8").read()
    start = src.index(marker)
    end = src.index(stop, start) if stop else len(src)
    block = src[start:end]
    calls, pos = [], 0
    while True:
        pos = block.find("db.execSQL(", pos)
        if pos < 0:
            break
        i = pos + len("db.execSQL(")
        depth, buf = 1, []
        while i < len(block) and depth > 0:
            ch = block[i]
            if ch == '"':
                i += 1
                while i < len(block) and block[i] != '"':
                    if block[i] == "\\":
                        buf.append(block[i:i + 2])
                        i += 2
                        continue
                    buf.append(block[i])
                    i += 1
            elif ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        calls.append(unescape_kotlin("".join(buf)))
        pos = i
    return calls


def query_annotations(path):
    src = open(path, encoding="utf-8").read()
    return [unescape_kotlin(m.group(1)) for m in re.finditer(r'@Query\("((?:[^"\\]|\\.)*)"\)', src)]


def message_columns():
    src = open(DB_KT, encoding="utf-8").read()
    m = re.search(r"internal const val MESSAGE_TEXT_READ_CAP = (\d+) \* (\d+)", src)
    cap = str(int(m.group(1)) * int(m.group(2)))
    m = re.search(r'internal const val MESSAGE_COLUMNS = "((?:[^"\\\\]|\\\\.)*)"', src)
    return unescape_kotlin(m.group(1)).replace("$MESSAGE_TEXT_READ_CAP", cap)


def positional(sql):
    names = []
    def sub(m):
        if m.group(1) not in names:
            names.append(m.group(1))
        return "?"
    return re.sub(r":([A-Za-z_][A-Za-z0-9_]*)", sub, sql), names


# ---------------------------------------------------------------- v11 库
schema = json.load(open(SCHEMA_11, encoding="utf-8"))
check("schema/11.json 是 v11", schema["database"]["version"] == 11, "version=" + str(schema["database"]["version"]))

db = sqlite3.connect(":memory:")
for entity in schema["database"]["entities"]:
    db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    for idx in entity.get("indices", []):
        db.execute(idx["createSql"].replace("${TABLE_NAME}", entity["tableName"]))

db.executemany(
    "INSERT INTO sessions (id, title, cwd, updatedAt, createdAt, status, preview, messageCount, inputTokens, outputTokens) VALUES (?,?,?,?,?,?,?,?,?,?)",
    [("s1", "老会话", "", 1, 1, "Idle", "p", 5, 0, 0), ("s2", "另一个", "", 2, 2, "Idle", "p", 2, 0, 0)],
)
rows = [("s1-m1", "s1", "USER", "hi", 1), ("s1-m2", "s1", "ASSISTANT", "target line", 2),
        ("s1-m3", "s1", "USER", "discount 100% off", 3), ("s1-m4", "s1", "ASSISTANT", "discount 100x off", 4),
        ("s1-m5", "s1", "USER", "tail", 5), ("s2-m1", "s2", "USER", "other session target", 6),
        ("s2-m2", "s2", "USER", "other tail", 7)]
db.executemany(
    "INSERT INTO messages (id, sessionId, role, text, thought, toolCallsJson, at, streaming, attachmentsJson, durationMs, turnId, inputTokens, outputTokens) VALUES (?,?,?,?,'','[]',?,0,'[]',0,'',0,0)",
    rows,
)
sessions_before = db.execute("SELECT COUNT(*) FROM sessions").fetchone()[0]
messages_before = db.execute("SELECT COUNT(*) FROM messages").fetchone()[0]
titles_before = [r[0] for r in db.execute("SELECT title FROM sessions ORDER BY id")]

# ------------------------------------------------- 执行 MIGRATION_11_12 的原始 DDL
ddl = sql_calls(DB_KT, "val MIGRATION_11_12", "val MIGRATION_9_10")
print("-- MIGRATION_11_12 发出的 SQL --")
for sql in ddl:
    print("   " + sql)
for sql in ddl:
    db.execute(sql)

sessions_after = db.execute("SELECT COUNT(*) FROM sessions").fetchone()[0]
messages_after = db.execute("SELECT COUNT(*) FROM messages").fetchone()[0]
titles_after = [r[0] for r in db.execute("SELECT title FROM sessions ORDER BY id")]
check("11->12 后 sessions 行数不变", sessions_before == sessions_after == 2, "%d -> %d" % (sessions_before, sessions_after))
check("11->12 后 messages 行数不变", messages_before == messages_after == 7, "%d -> %d" % (messages_before, messages_after))
check("11->12 后会话标题逐字不变", titles_before == titles_after, str(titles_after))

cols = [r[1] for r in db.execute("PRAGMA table_info(warm_layers)")]
check("warm_layers 六列齐全", cols == ["sessionId", "payload", "updatedAt", "coveredUntilMessageId", "warmVersion", "source"], str(cols))
idx = [r[1] for r in db.execute("PRAGMA index_list(warm_layers)")]
check("warm_layers 索引存在", "index_warm_layers_updatedAt" in idx, str(idx))

db.execute(
    "INSERT OR REPLACE INTO warm_layers (sessionId, payload, updatedAt, coveredUntilMessageId, warmVersion, source) VALUES (?,?,?,?,?,?)",
    ("s1", '{"primaryGoal":"v1"}', 100, 3, 1, "LOCAL"),
)
db.execute(
    "INSERT OR REPLACE INTO warm_layers (sessionId, payload, updatedAt, coveredUntilMessageId, warmVersion, source) VALUES (?,?,?,?,?,?)",
    ("s1", '{"primaryGoal":"v2"}', 200, 5, 2, "PLUGIN"),
)
row = db.execute("SELECT payload, coveredUntilMessageId, warmVersion, source FROM warm_layers WHERE sessionId='s1'").fetchone()
check("warm_layers 可写可读且同 session 覆盖", row == ('{"primaryGoal":"v2"}', 5, 2, "PLUGIN"), str(row))
check("warm_layers 只有一行", db.execute("SELECT COUNT(*) FROM warm_layers").fetchone()[0] == 1)

# ------------------------------------------------------------ 两条回捞查询
queries = query_annotations(DAO_KT)
range_sql = next(q for q in queries if "rowid >=" in q)
search_sql = next(q for q in queries if "LIKE" in q)
range_sql = range_sql.replace("$MESSAGE_COLUMNS", message_columns())
search_sql = search_sql.replace("$MESSAGE_COLUMNS", message_columns())
print("-- bySessionRange --\n   " + range_sql)
print("-- searchInSession --\n   " + search_sql)

rsql, rnames = positional(range_sql)
ids = dict(db.execute("SELECT id, rowid FROM messages WHERE sessionId='s1'").fetchall())
args = {"sessionId": "s1", "fromId": ids["s1-m2"], "toId": ids["s1-m4"]}
got = [r[0] for r in db.execute(rsql, tuple(args[n] for n in rnames))]
check("bySessionRange 闭区间含两端", got == ["s1-m2", "s1-m3", "s1-m4"], str(got))
got_empty = [r[0] for r in db.execute(rsql, ("s1", ids["s1-m5"] + 100, ids["s1-m5"] + 200))]
check("bySessionRange 区间外为空", got_empty == [], str(got_empty))
got_before = [r[0] for r in db.execute(rsql, ("s1", 0, ids["s1-m1"]))]
check("bySessionRange 起点含 rowid 最小行", got_before == ["s1-m1"], str(got_before))

ssql, snames = positional(search_sql)


def run_search(session, like, limit=50):
    params = {"sessionId": session, "like": like, "limit": limit}
    return [r[0] for r in db.execute(ssql, tuple(params[n] for n in snames))]


check("searchInSession 命中", run_search("s1", "target") == ["s1-m2"], str(run_search("s1", "target")))
check("searchInSession 不命中", run_search("s1", "no-such-text") == [], str(run_search("s1", "no-such-text")))
check("searchInSession 只在本会话内", run_search("s2", "target") == ["s2-m1"], str(run_search("s2", "target")))
check("searchInSession 转义 % 后按字面命中", run_search("s1", "100\\%") == ["s1-m3"], str(run_search("s1", "100\\%")))

print("")
print("FAILURES: " + (", ".join(failures) if failures else "none"))
sys.exit(1 if failures else 0)