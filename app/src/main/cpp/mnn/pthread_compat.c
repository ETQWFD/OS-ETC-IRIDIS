/*
 * pthread_compat.c — ETC fork compatibility shims.
 *
 * MNN (static libMNN.a) references pthread_cond_clockwait(), which only
 * exists in Android API 30+. When targeting older API levels (full flavor
 * minSdk 24, lite flavor minSdk 21) the linker cannot resolve it. Provide a
 * fallback that ignores the clockid and uses pthread_cond_timedwait().
 */
#include <pthread.h>
#include <time.h>

#if defined(__ANDROID__) && __ANDROID_API__ < 30
int pthread_cond_clockwait(pthread_cond_t *cond, pthread_mutex_t *mutex,
                            clockid_t clock, const struct timespec *abstime) {
    (void)clock;
    return pthread_cond_timedwait(cond, mutex, abstime);
}
#endif

/*
 * On 32-bit ARM Android, MNN's static library references __tls_get_addr,
 * which is provided at runtime by the dynamic linker (not exported from
 * libc.so). The weak stub below is overridden by the linker's real symbol
 * when the .so is loaded; it only exists to satisfy --no-undefined at
 * link time.
 */
#if defined(__ANDROID__) && defined(__arm__)
__attribute__((weak)) void *__tls_get_addr(void *index) {
    (void)index;
    return 0;
}
#endif
