"""Run only extracted production folder logic with the cached Kotlin compiler."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[4]
SOURCE = ROOT / "feature/sessions/src/main/kotlin/dev/dsh/mobile/feature/sessions/SessionsViewModel.kt"
CACHE = Path(os.environ.get("GRADLE_USER_HOME") or (Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
JAVA = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java") if os.environ.get("JAVA_HOME") else shutil.which("java")
source = SOURCE.read_text(encoding="utf-8")
row = source[source.index("data class FolderRow("):source.index("data class SessionsUiState(")]
logic = source[source.index("internal fun folderGroupKey"): ]
delete = source[source.index("    fun deleteFolder("):source.index("    fun renameFolder(")]
delete = delete.replace("= viewModelScope.launch {", "{")
harness = r'''
object SessionsUiState { val RESERVED_GROUPS = setOf("全部", "置顶", "归档") }
data class Session(val id: String, val group: String, val pinned: Boolean = false, val archived: Boolean = false) {
    companion object { const val DEFAULT_GROUP = "最近" }
}
fun <T> T.first(): T = this
class Repo(var sessions: List<Session>) {
    var failure: Exception? = null
    fun observeSessions() = sessions
    fun setArchived(id: String, value: Boolean) {
        failure?.let { throw it }
        sessions = sessions.map { if (it.id == id) it.copy(archived = value) else it }
    }
}
data class Settings(val customFolders: List<String>)
class Store(var settings: Settings) {
    var fail = false
    fun setCustomFolders(paths: List<String>) { check(!fail) { "storage failure" }; settings = Settings(paths) }
}
data class UiState(val folders: List<FolderRow>)
data class Holder(val value: UiState)
class Subject(val repo: Repo, val settingsStore: Store) {
    val uiState = Holder(UiState(buildFolders(repo.sessions.filter { !it.pinned && !it.archived && it.id == "visible" }, settingsStore.settings.customFolders)))
DELETE
}
fun main() {
    for (path in listOf("work", "work·", "work··", "归档", "归档·", "a/work·")) {
        check(folderPathFromGroup(folderGroupKey(path)) == path)
    }
    check(folderGroupKey("归档") != folderGroupKey("归档·"))
    check(folderGroupKey("归档") != "归档")
    val literalRepo = Repo(listOf(Session("plain", "work"), Session("dot", "work·"), Session("child", "work·/urgent")))
    val literalStore = Store(Settings(listOf("work", "work·", "work·/urgent")))
    val literalPath = folderPathFromGroup(folderGroupKey("work·"))
    check(folderSessionCount(literalRepo.sessions.groupingBy { it.group }.eachCount(), literalPath) == 2)
    Subject(literalRepo, literalStore).deleteFolder(literalPath)
    check(literalRepo.sessions.filter { it.id != "plain" }.all { it.archived })
    check(!literalRepo.sessions.single { it.id == "plain" }.archived)
    check(literalStore.settings.customFolders == listOf("work"))
    println("PASS: folder identifiers preserve literal dots and reserved name identity")
    check(cleanPath(" / a // b / c / ") == "a/b/c")
    val rows = buildFolders(listOf(Session("one", "a/b/c"), Session("two", "x/b/c")), listOf("a", "a/b", "a/b/c", "empty/leaf"))
    check(rows.map { it.path }.toSet().size == rows.size)
    check(rows.filter { it.path.startsWith("a") }.map { it.path } == listOf("a", "a/b", "a/b/c"))
    check(rows.all { it.label == it.path.substringAfterLast('/') && it.depth == it.path.count { c -> c == '/' } })
    check(rows.single { it.path == "a/b/c" }.sessions.single().id == "one")
    check(rows.single { it.path == "x/b/c" }.sessions.single().id == "two")
    check(rows.single { it.path == "empty/leaf" }.sessions.isEmpty())
    check(rows.single { it.path == "a" }.sessions.isEmpty())
    val legacy = buildFolders(emptyList(), listOf("work", "work urgent", "work/urgent"))
    check(legacy.map { it.label } == listOf("work", "work urgent", "urgent"))
    println("PASS: hierarchy, leaf labels, distinct parents, empty folders, legacy flat name")
    val repo = Repo(listOf(Session("visible", "a"), Session("pinned", "a/b", pinned = true), Session("hidden", "a/b/c"), Session("sibling", "ab/c")))
    val store = Store(Settings(listOf("a", "a/b", "a/b/c", "ab/c", "empty/leaf")))
    check(folderSessionCount(repo.sessions.groupingBy { it.group }.eachCount(), "a") == 3)
    var completed = false
    Subject(repo, store).deleteFolder("a") { result ->
        check(result.isSuccess)
        check(repo.sessions.filter { it.group.startsWith("a/") }.all { it.archived })
        check("a" !in store.settings.customFolders)
        completed = true
    }
    check(completed)
    check(repo.sessions.filter { it.group == "a" || it.group.startsWith("a/") }.all { it.archived }) { "Folder deletion skipped pinned or search-hidden sessions" }
    check(!repo.sessions.single { it.id == "sibling" }.archived)
    check(store.settings.customFolders == listOf("ab/c", "empty/leaf"))
    Subject(repo, store).deleteFolder("empty")
    check(store.settings.customFolders == listOf("ab/c"))
    println("PASS: deleting subtree archives all sessions, preserves siblings, removes empty subtree")
    store.fail = true
    var failed = false
    Subject(repo, store).deleteFolder("ab") { result ->
        check(result.exceptionOrNull()?.message == "storage failure")
        failed = true
    }
    check(failed)
    repo.failure = IllegalStateException("archive failure")
    Subject(repo, store).deleteFolder("ab") { result -> check(result.exceptionOrNull()?.message == "archive failure") }
    repo.failure = CancellationException("cancelled")
    var cancelled = false
    try {
        Subject(repo, store).deleteFolder("ab") { error("Cancellation reported as completed") }
    } catch (error: CancellationException) { cancelled = true }
    check(cancelled)
    check(folderSessionCount(mapOf("a" to 1, "a/b" to 2, "ab" to 9), "a") == 3)
    println("PASS: full subtree count, completion after persistence, archive/storage failure callback, cancellation")
}
'''.replace("DELETE", delete)
compiler = list((CACHE / "org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21").rglob("*.jar"))[0]
stdlib = list((CACHE / "org.jetbrains.kotlin/kotlin-stdlib/2.0.21").rglob("*.jar"))[0]
annotations = list((CACHE / "org.jetbrains/annotations").rglob("*.jar"))[0]
runtime = [compiler, stdlib, annotations]
for rel in ["org.jetbrains.kotlin/kotlin-script-runtime", "org.jetbrains.kotlin/kotlin-reflect", "org.jetbrains.intellij.deps/trove4j", "org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm"]:
    jars = list((CACHE / rel).rglob("*.jar"))
    if jars:
        runtime.append(jars[-1])
scratch = ROOT / "feature/sessions/build/folder-self-check"
scratch.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix="sessions-folders-", dir=scratch) as temp:
    temp = Path(temp)
    (temp / "classes").mkdir()
    test = temp / "FolderCheck.kt"
    test.write_text("import java.util.concurrent.CancellationException\n" + row + logic + harness.replace("kotlinx.coroutines.CancellationException", "CancellationException"), encoding="utf-8")
    cp = os.pathsep.join(map(str, runtime))
    subprocess.run([JAVA, "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-classpath", os.pathsep.join(map(str, [stdlib, annotations])), "-d", str(temp / "classes"), str(test)], check=True)
    subprocess.run([JAVA, "-cp", os.pathsep.join(map(str, [temp / "classes", stdlib])), "FolderCheckKt"], check=True)
