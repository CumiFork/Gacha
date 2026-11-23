#include <jni.h>
#include <android/log.h>

#include <sys/system_properties.h>
#include <sys/utsname.h>

#include <algorithm>
#include <cinttypes>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <unistd.h>

#define LOG_TAG "NativeCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

struct CpuSample {
    uint64_t idle = 0;
    uint64_t total = 0;
};

std::mutex g_cpuMutex;
CpuSample g_prevCpu{0, 0};

std::string readFile(const char *path) {
    std::ifstream in(path);
    if (!in.is_open()) {
        return "";
    }
    std::ostringstream ss;
    ss << in.rdbuf();
    return ss.str();
}

std::string trim(const std::string &s) {
    const auto begin = s.find_first_not_of(" \t\r\n");
    if (begin == std::string::npos) return "";
    const auto end = s.find_last_not_of(" \t\r\n");
    return s.substr(begin, end - begin + 1);
}

std::string getProp(const char *key, const char *fallback = "unknown") {
    char value[PROP_VALUE_MAX] = {0};
    int len = __system_property_get(key, value);
    if (len > 0) {
        return std::string(value);
    }
    return std::string(fallback);
}

std::string parseMemInfo() {
    std::ifstream in("/proc/meminfo");
    if (!in.is_open()) {
        return "Mem: unavailable";
    }
    std::string key;
    uint64_t value = 0;
    std::string unit;
    uint64_t memTotal = 0;
    uint64_t memAvailable = 0;
    while (in >> key >> value >> unit) {
        if (key == "MemTotal:") memTotal = value;
        if (key == "MemAvailable:") memAvailable = value;
    }
    if (memTotal == 0) return "Mem: unavailable";
    uint64_t memUsed = memTotal - memAvailable;
    char buf[128];
    snprintf(buf, sizeof(buf), "Mem: %llu / %llu MB",
             static_cast<unsigned long long>(memUsed / 1024),
             static_cast<unsigned long long>(memTotal / 1024));
    return std::string(buf);
}

std::string parseKernelVersion() {
    std::string raw = readFile("/proc/version");
    if (raw.empty()) {
        struct utsname uts{};
        if (uname(&uts) == 0) {
            return std::string(uts.release);
        }
        return "unknown";
    }
    // Keep first line/token-ish summary to avoid huge output.
    auto nl = raw.find('\n');
    if (nl != std::string::npos) raw = raw.substr(0, nl);
    if (raw.size() > 120) raw = raw.substr(0, 120) + "...";
    return trim(raw);
}

CpuSample readCpuSample() {
    std::ifstream in("/proc/stat");
    std::string cpu;
    uint64_t user = 0, nice = 0, system = 0, idle = 0, iowait = 0, irq = 0, softirq = 0, steal = 0;
    if (!(in >> cpu >> user >> nice >> system >> idle >> iowait >> irq >> softirq >> steal)) {
        return {};
    }
    uint64_t idleAll = idle + iowait;
    uint64_t total = user + nice + system + idle + iowait + irq + softirq + steal;
    return {idleAll, total};
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_arkana_cppoverlay_NativeBridge_getNativeSystemInfo(JNIEnv *env, jobject /*thiz*/) {
    const std::string abi = getProp("ro.product.cpu.abi", "unknown");
    const std::string sdk = getProp("ro.build.version.sdk", "?");
    const std::string device = getProp("ro.product.model", "Android");
    const std::string kernel = parseKernelVersion();
    const std::string mem = parseMemInfo();

    char buf[1024];
    snprintf(buf, sizeof(buf),
             "Device: %s\nABI: %s (SDK %s)\nKernel: %s\n%s",
             device.c_str(), abi.c_str(), sdk.c_str(), kernel.c_str(), mem.c_str());
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_arkana_cppoverlay_NativeBridge_getNativeCpuLoad(JNIEnv *env, jobject /*thiz*/) {
    CpuSample cur = readCpuSample();
    double cpuPercent = 0.0;
    {
        std::lock_guard<std::mutex> lock(g_cpuMutex);
        if (g_prevCpu.total != 0 && cur.total > g_prevCpu.total) {
            uint64_t totalDelta = cur.total - g_prevCpu.total;
            uint64_t idleDelta = cur.idle - g_prevCpu.idle;
            cpuPercent = (double)(totalDelta - idleDelta) * 100.0 / (double)totalDelta;
            if (cpuPercent < 0.0) cpuPercent = 0.0;
            if (cpuPercent > 100.0) cpuPercent = 100.0;
        }
        g_prevCpu = cur;
    }

    double uptimeSec = 0.0;
    std::ifstream up("/proc/uptime");
    up >> uptimeSec;

    std::string mem = parseMemInfo();
    char buf[512];
    snprintf(buf, sizeof(buf), "CPU: %.1f%%\nUptime: %.0fs\n%s", cpuPercent, uptimeSec, mem.c_str());
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_arkana_cppoverlay_NativeBridge_formatCommandOutput(JNIEnv *env, jobject /*thiz*/, jstring raw) {
    if (raw == nullptr) {
        return env->NewStringUTF("");
    }
    const char *rawChars = env->GetStringUTFChars(raw, nullptr);
    std::string input = rawChars ? rawChars : "";
    env->ReleaseStringUTFChars(raw, rawChars);

    std::istringstream iss(input);
    std::string line;
    std::ostringstream out;
    int lines = 0;
    while (std::getline(iss, line)) {
        std::string t = trim(line);
        if (t.empty()) continue;
        if (lines > 0) out << "\n";
        out << "> " << t;
        ++lines;
        if (lines >= 80) {
            out << "\n> ... (truncated)";
            break;
        }
    }
    if (lines == 0) {
        out << "> (no output)";
    }
    return env->NewStringUTF(out.str().c_str());
}
