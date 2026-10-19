// 1.7.4: resource-tier thread policy shared by libiris_llama.so (GGUF, jni_bridge.cpp) and
// libiris_mnn.so (MNN). Header-only so both libraries compile the same code without a new link
// dependency between them.
//
// Linux/Android semantics this relies on: CPU affinity (sched_setaffinity(0)) and the nice value
// (setpriority(PRIO_PROCESS, tid)) are per *thread*, and a thread created with pthread_create /
// std::thread inherits both from its creator. Native thread pools (ggml's per-graph pool, MNN's
// ThreadPool, XNNPACK) therefore run with whatever the creating thread had at creation time.
#pragma once

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iterator>
#include <string>
#include <vector>

#include <dirent.h>
#include <sched.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace iris {
namespace tp {

inline int current_tid() { return static_cast<int>(syscall(SYS_gettid)); }

/** "0-5" / "0,2,4-7" style list of the cores in [set]. */
inline std::string cpuset_string(const cpu_set_t& set) {
    std::string out;
    const int n = std::min<int>(CPU_SETSIZE, 1024);
    for (int c = 0; c < n; ++c) {
        if (!CPU_ISSET(c, &set)) continue;
        int e = c;
        while (e + 1 < n && CPU_ISSET(e + 1, &set)) ++e;
        if (!out.empty()) out += ',';
        out += std::to_string(c);
        if (e > c) { out += '-'; out += std::to_string(e); }
        c = e;
    }
    return out.empty() ? "?" : out;
}

/** Affinity + nice of the calling thread, so it can be restored after a load / generate call. */
struct Snapshot {
    bool haveAffinity = false;
    cpu_set_t affinity;
    bool haveNice = false;
    int nice = 0;
};

inline Snapshot capture() {
    Snapshot s;
    CPU_ZERO(&s.affinity);
    s.haveAffinity = sched_getaffinity(0, sizeof(s.affinity), &s.affinity) == 0;
    errno = 0;
    const int n = getpriority(PRIO_PROCESS, static_cast<id_t>(current_tid()));
    if (!(n == -1 && errno != 0)) { s.haveNice = true; s.nice = n; }
    return s;
}

/** False when the kernel refused (lowering nice needs RLIMIT_NICE / CAP_SYS_NICE; Android apps have it). */
inline bool restore(const Snapshot& s) {
    bool ok = true;
    if (s.haveAffinity) ok &= sched_setaffinity(0, sizeof(s.affinity), &s.affinity) == 0;
    if (s.haveNice) ok &= setpriority(PRIO_PROCESS, static_cast<id_t>(current_tid()), s.nice) == 0;
    return ok;
}

/**
 * Pins the calling thread to [cpus] (empty = every configured core) and sets its nice value
 * (clamped to -10…19, as in 1.7.1/1.7.2). Returns a bit mask: 1 = affinity applied, 2 = nice applied.
 */
inline int apply(const std::vector<int>& cpus, int nice) {
    int result = 0;
    cpu_set_t set;
    CPU_ZERO(&set);
    if (!cpus.empty()) {
        for (int c : cpus) if (c >= 0 && c < CPU_SETSIZE) CPU_SET(c, &set);
    } else {
        const long ncpu = sysconf(_SC_NPROCESSORS_CONF);
        for (long c = 0; c < ncpu && c < CPU_SETSIZE; ++c) CPU_SET(static_cast<int>(c), &set);
    }
    if (sched_setaffinity(0, sizeof(set), &set) == 0) result |= 1;
    if (setpriority(PRIO_PROCESS, static_cast<id_t>(current_tid()), std::clamp(nice, -10, 19)) == 0) result |= 2;
    return result;
}

/** Kernel read-back of one thread (any tid of this process): "cpus=0-5 nice=10". */
inline std::string describe(int tid) {
    cpu_set_t set;
    CPU_ZERO(&set);
    const std::string cores = sched_getaffinity(tid, sizeof(set), &set) == 0 ? cpuset_string(set) : "?";
    errno = 0;
    const int n = getpriority(PRIO_PROCESS, static_cast<id_t>(tid));
    char buf[160];
    if (n == -1 && errno != 0) snprintf(buf, sizeof buf, "cpus=%s nice=?", cores.c_str());
    else snprintf(buf, sizeof buf, "cpus=%s nice=%d", cores.c_str(), n);
    return buf;
}

/** Every thread id of this process (/proc/self/task), sorted. */
inline std::vector<int> task_ids() {
    std::vector<int> out;
    DIR* d = opendir("/proc/self/task");
    if (d == nullptr) return out;
    while (dirent* e = readdir(d)) {
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        out.push_back(atoi(e->d_name));
    }
    closedir(d);
    std::sort(out.begin(), out.end());
    return out;
}

/** Threads present in [after] but not in [before] (both sorted). */
inline std::vector<int> new_tasks(const std::vector<int>& before, const std::vector<int>& after) {
    std::vector<int> out;
    std::set_difference(after.begin(), after.end(), before.begin(), before.end(), std::back_inserter(out));
    return out;
}

/** "N threads, cpus=0-5 nice=10 ×N" — groups identical read-backs of [tids]. */
inline std::string summarize(const std::vector<int>& tids) {
    if (tids.empty()) return "0 threads";
    std::vector<std::pair<std::string, int>> groups;
    for (int t : tids) {
        const std::string d = describe(t);
        auto it = std::find_if(groups.begin(), groups.end(), [&](const auto& g) { return g.first == d; });
        if (it == groups.end()) groups.emplace_back(d, 1); else it->second++;
    }
    std::string out = std::to_string(tids.size()) + " threads:";
    for (const auto& g : groups) { out += " ["; out += g.first; out += " x"; out += std::to_string(g.second); out += ']'; }
    return out;
}

// ---- 1.7.5: act on *other* threads of this process (MNN's existing pool workers) ----------------

/** Result of [apply_to]: -1 = thread no longer exists (ESRCH), otherwise bit 1 = affinity, bit 2 = nice. */
inline int apply_to(int tid, const std::vector<int>& cpus, int nice) {
    if (tid <= 0) return -1;
    cpu_set_t set;
    CPU_ZERO(&set);
    if (!cpus.empty()) {
        for (int c : cpus) if (c >= 0 && c < CPU_SETSIZE) CPU_SET(c, &set);
    } else {
        const long ncpu = sysconf(_SC_NPROCESSORS_CONF);
        for (long c = 0; c < ncpu && c < CPU_SETSIZE; ++c) CPU_SET(static_cast<int>(c), &set);
    }
    int result = 0;
    if (sched_setaffinity(tid, sizeof(set), &set) == 0) result |= 1;
    else if (errno == ESRCH) return -1;
    if (setpriority(PRIO_PROCESS, static_cast<id_t>(tid), std::clamp(nice, -10, 19)) == 0) result |= 2;
    else if (errno == ESRCH) return -1;
    return result;
}

/** /proc/self/task/<tid>/comm without the newline ("" if the thread is gone). */
inline std::string thread_comm(int tid) {
    char path[64];
    snprintf(path, sizeof path, "/proc/self/task/%d/comm", tid);
    FILE* f = fopen(path, "r");
    if (f == nullptr) return "";
    char buf[64] = {0};
    if (fgets(buf, sizeof buf, f) == nullptr) buf[0] = 0;
    fclose(f);
    std::string s(buf);
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
    return s;
}

/** Fields of /proc/self/task/<tid>/stat: state (3), utime (14), stime (15), nice (19), processor (39). */
struct ThreadStat {
    bool ok = false;
    char state = '?';
    long long utime = 0;   // clock ticks
    long long stime = 0;
    int nice = 0;
    int processor = -1;
};

inline ThreadStat read_stat(int tid) {
    ThreadStat st;
    char path[64];
    snprintf(path, sizeof path, "/proc/self/task/%d/stat", tid);
    FILE* f = fopen(path, "r");
    if (f == nullptr) return st;
    char buf[1024];
    const size_t n = fread(buf, 1, sizeof buf - 1, f);
    fclose(f);
    buf[n] = 0;
    // comm (field 2) may contain spaces: fields after it start behind the last ')'.
    const char* p = strrchr(buf, ')');
    if (p == nullptr) return st;
    p += 2;   // now at field 3
    int field = 3;
    char* end = nullptr;
    while (*p && field <= 39) {
        if (field == 3) st.state = *p;
        else if (field == 14) st.utime = strtoll(p, &end, 10);
        else if (field == 15) st.stime = strtoll(p, &end, 10);
        else if (field == 19) st.nice = static_cast<int>(strtol(p, &end, 10));
        else if (field == 39) { st.processor = static_cast<int>(strtol(p, &end, 10)); st.ok = true; break; }
        const char* sp = strchr(p, ' ');
        if (sp == nullptr) break;
        p = sp + 1;
        ++field;
    }
    return st;
}

/**
 * Cores to pin [threads] busy threads to. 1.7.6: the Kotlin side (LiveResourcePolicy.coresFor)
 * already widened the list onto neighbouring non-prime cores when the tier has fewer cores than
 * threads; releasing the pinning here would let the threads reach the prime cores, so the list is
 * used as given (threads may share cores until the idle reload brings the target thread count).
 */
inline std::vector<int> cores_for(const std::vector<int>& cpus, int /*threads*/) {
    return cpus;
}

} // namespace tp
} // namespace iris
