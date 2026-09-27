#include <jni.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <time.h>
#include <hev-task.h>
#include <hev-task-system.h>
#include "hev-main.h"

static _Atomic int64_t last_heartbeat;
static atomic_int simulate_stall;

static void heartbeat_task(void *unused)
{
    for (;;) {
        struct timespec now;
        if (atomic_load(&simulate_stall)) {
            /* Only requested by the debug APK's private instrumentation interface. */
            for (;;) atomic_signal_fence(memory_order_seq_cst);
        }
        clock_gettime(CLOCK_MONOTONIC, &now);
        atomic_store(&last_heartbeat, (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000);
        hev_task_sleep(5000);
    }
}

void __real_hev_task_system_run(void);

/* Runs on HEV's cooperative scheduler, so a stuck lwIP timer stops this heartbeat too.
 * Linker wrapping keeps the pinned vendor submodule unmodified. The process is
 * disposable; shutdown never enters the vendor's unbounded pthread_join. */
void __wrap_hev_task_system_run(void)
{
    HevTask *task = hev_task_new(-1);
    if (!task) abort();
    hev_task_set_priority(task, 1);
    hev_task_run(task, heartbeat_task, NULL);
    __real_hev_task_system_run();
}

JNIEXPORT void JNICALL
Java_net_tref_sshtunnel_NativeEngine_run(JNIEnv *env, jclass clazz, jstring path, jint fd)
{
    const char *config = (*env)->GetStringUTFChars(env, path, NULL);
    if (!config) return;
    hev_socks5_tunnel_main(config, fd);
    (*env)->ReleaseStringUTFChars(env, path, config);
}

JNIEXPORT jlong JNICALL
Java_net_tref_sshtunnel_NativeEngine_heartbeat(JNIEnv *env, jclass clazz)
{
    return atomic_load(&last_heartbeat);
}

JNIEXPORT void JNICALL
Java_net_tref_sshtunnel_NativeEngine_simulateStall(JNIEnv *env, jclass clazz)
{
    atomic_store(&simulate_stall, 1);
}
