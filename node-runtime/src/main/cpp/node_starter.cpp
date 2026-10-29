// node::Start 的 JNI 入口。
// 两个关键点：
// 1) node::Start 是阻塞的（跑完整个事件循环才返回退出码），Kotlin 侧放在独立线程里调。
// 2) Android 进程没有可用的 stdout，node 的 console.log 会写进黑洞；这里把 fd 1/2
//    重定向到一条管道，由读线程逐行转成 logcat（tag=DshMobile，与 AppLog 一致），
//    否则自检输出和以后的插件日志在 adb 上完全看不见。
#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "node.h"

#define LOG_TAG "DshMobile"

namespace {

void logLine(const char* line, size_t len) {
    if (len == 0) return;
    __android_log_write(ANDROID_LOG_INFO, LOG_TAG, std::string(line, len).c_str());
}

// 把管道里的字节按行切出来交给 logcat；行超长时截断成多段，避免丢日志。
void* readerLoop(void* arg) {
    int fd = static_cast<int>(reinterpret_cast<intptr_t>(arg));
    char buf[1024];
    std::string pending;
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) {
        for (ssize_t i = 0; i < n; i++) {
            if (buf[i] == '\n') {
                logLine(pending.data(), pending.size());
                pending.clear();
            } else {
                pending.push_back(buf[i]);
                if (pending.size() >= 900) {
                    logLine(pending.data(), pending.size());
                    pending.clear();
                }
            }
        }
    }
    if (!pending.empty()) logLine(pending.data(), pending.size());
    close(fd);
    return nullptr;
}

void redirectStdioToLogcat() {
    int fds[2];
    if (pipe(fds) != 0) return;
    dup2(fds[1], STDOUT_FILENO);
    dup2(fds[1], STDERR_FILENO);
    close(fds[1]);
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    pthread_t t;
    if (pthread_create(&t, nullptr, readerLoop, reinterpret_cast<void*>(static_cast<intptr_t>(fds[0]))) == 0) {
        pthread_detach(t);
    } else {
        close(fds[0]);
    }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_dev_dsh_mobile_noderuntime_NodeRuntime_nativeStart(JNIEnv* env, jobject /*thiz*/, jobjectArray args) {
    const jsize count = args == nullptr ? 0 : env->GetArrayLength(args);
    std::vector<std::string> storage;
    storage.reserve(static_cast<size_t>(count) + 1);
    for (jsize i = 0; i < count; i++) {
        auto js = static_cast<jstring>(env->GetObjectArrayElement(args, i));
        if (js == nullptr) {
            storage.emplace_back();
            continue;
        }
        const char* utf = env->GetStringUTFChars(js, nullptr);
        storage.emplace_back(utf == nullptr ? "" : utf);
        if (utf != nullptr) env->ReleaseStringUTFChars(js, utf);
        env->DeleteLocalRef(js);
    }
    // node::Start 要求 argv[0] 是程序名。
    if (storage.empty()) storage.emplace_back("node");

    std::vector<char*> argv;
    argv.reserve(storage.size() + 1);
    for (auto& s : storage) argv.push_back(const_cast<char*>(s.c_str()));
    argv.push_back(nullptr);

    redirectStdioToLogcat();
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "node::Start argc=%d argv0=%s", static_cast<int>(storage.size()), argv[0]);
    const int rc = node::Start(static_cast<int>(storage.size()), argv.data());
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "node::Start returned %d", rc);
    return rc;
}
