/*
 * ETC fork (OS-ETC-IRIDIS): API-level compatibility shims for 32-bit / old Android.
 *
 * Some third-party native libraries (sherpa-onnx, llama.cpp) reference libc
 * functions that only exist on newer Android API levels. On minSdk 21 devices
 * the dynamic linker fails to load those libraries with "cannot locate symbol".
 *
 * This library provides fallback implementations and is loaded FIRST in
 * IrisApp.onCreate(), so later libraries resolve these symbols against it.
 *
 * Only compiled in; the runtime decides whether the symbol is already present
 * (we use weak symbols so the system libc wins when available).
 */

#include <stdio.h>
#include <sys/types.h>
#include <unistd.h>

/* ftello() / fseeko() — Android API 24+. Fall back to ftell()/fseek().
 * On 32-bit ABIs long is 32-bit, which is enough for the metadata offsets
 * these callers use (model files are mmap'd, not seeked through). */
__attribute__((weak)) off_t ftello(FILE *stream) {
    return (off_t) ftell(stream);
}

__attribute__((weak)) int fseeko(FILE *stream, off_t offset, int whence) {
    return fseek(stream, (long) offset, whence);
}

/* posix_fadvise() — Android API 23+. Purely a performance hint; no-op fallback. */
__attribute__((weak)) int posix_fadvise(int fd, off_t offset, off_t len, int advice) {
    (void) fd; (void) offset; (void) len; (void) advice;
    return 0;
}

/* posix_madvise() — Android API 23+. Fall back to madvise() (same advice values). */
#include <sys/mman.h>
__attribute__((weak)) int posix_madvise(void *addr, size_t len, int advice) {
    return madvise(addr, len, advice);
}
