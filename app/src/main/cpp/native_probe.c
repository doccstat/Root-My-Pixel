#include <jni.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/system_properties.h>
#include <sys/utsname.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <android/log.h>
#include <stdint.h>

#define TAG "PixelNativeProbe"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define KSU_MAGIC_1 0xDEADBEEF
#define KSU_MAGIC_2 0xCAFEBABE
#define KSU_IOCTL_GET_INFO _IOR('K', 2, struct ksu_get_info_cmd)
#define KSU_IOCTL_GET_INFO_LEGACY _IOC(_IOC_READ, 'K', 2, 0)
// This legacy UAPI command deliberately encodes a zero-sized payload.  Using
// _IOWR would encode sizeof(struct ksu_uid_granted_root_cmd) in the command
// number, making the kernel reject it as a different ioctl.
#define KSU_IOCTL_UID_GRANTED_ROOT _IOC(_IOC_READ | _IOC_WRITE, 'K', 8, 0)

struct ksu_get_info_cmd {
    uint32_t version;
    uint32_t flags;
    uint32_t features;
    uint32_t uapi_version;
};

struct ksu_uid_granted_root_cmd {
    uint32_t uid;
    uint8_t granted;
};

struct ksu_probe_result {
    uint8_t driver_present;
    uint8_t driver_responsive;
    uint8_t app_root_granted;
    uint32_t version;
    uint32_t flags;
    uint32_t features;
    uint32_t uapi_version;
};

static int get_kernelsu_info(struct ksu_probe_result *result) {
    int pipe_fds[2];
    memset(result, 0, sizeof(*result));
    if (pipe(pipe_fds) != 0) {
        return 0;
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(pipe_fds[0]);
        close(pipe_fds[1]);
        return 0;
    }
    if (pid == 0) {
        // A stock kernel can reject the supercall with SIGSYS. Keep that
        // failure isolated so probing never terminates the Android app.
        close(pipe_fds[0]);
        struct ksu_probe_result child_result;
        memset(&child_result, 0, sizeof(child_result));
        int fd = -1;
        if (syscall(SYS_reboot, KSU_MAGIC_1, KSU_MAGIC_2, 0, &fd) == 0 && fd >= 0) {
            child_result.driver_present = 1;
            struct ksu_get_info_cmd info;
            memset(&info, 0, sizeof(info));
            if (ioctl(fd, KSU_IOCTL_GET_INFO, &info) == 0 ||
                    ioctl(fd, KSU_IOCTL_GET_INFO_LEGACY, &info) == 0) {
                child_result.driver_responsive = info.version > 0;
                child_result.version = info.version;
                child_result.flags = info.flags;
                child_result.features = info.features;
                child_result.uapi_version = info.uapi_version;
                if (child_result.driver_responsive) {
                    struct ksu_uid_granted_root_cmd root_command;
                    memset(&root_command, 0, sizeof(root_command));
                    root_command.uid = (uint32_t) getuid();
                    if (ioctl(fd, KSU_IOCTL_UID_GRANTED_ROOT, &root_command) == 0) {
                        child_result.app_root_granted = root_command.granted != 0;
                    }
                }
            }
            close(fd);
        }
        (void) write(pipe_fds[1], &child_result, sizeof(child_result));
        close(pipe_fds[1]);
        _exit(0);
    }

    close(pipe_fds[1]);
    ssize_t bytes_read = read(pipe_fds[0], result, sizeof(*result));
    close(pipe_fds[0]);
    int status = 0;
    return waitpid(pid, &status, 0) == pid &&
            WIFEXITED(status) && WEXITSTATUS(status) == 0 &&
            bytes_read == (ssize_t) sizeof(*result);
}

static int check_kernelsu_active(void) {
    struct ksu_probe_result result;
    return get_kernelsu_info(&result) && result.driver_responsive;
}

static int read_file(const char *path, char *buf, size_t size) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    ssize_t n = read(fd, buf, size - 1);
    close(fd);
    if (n < 0) return -1;
    buf[n] = '\0';
    return 0;
}

static void get_prop(const char *key, char *buf, size_t size) {
    buf[0] = '\0';
    int len = __system_property_get(key, buf);
    if (len <= 0 || buf[0] == '\0') {
        snprintf(buf, size, "unknown");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_alex193a_rootmypixel_utils_NativeProbe_isKernelSuActiveNative(
        JNIEnv *env __attribute__((unused)), jobject thiz __attribute__((unused))) {
    return check_kernelsu_active() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_alex193a_rootmypixel_utils_NativeProbe_getKernelSuInfoNative(
        JNIEnv *env, jobject thiz __attribute__((unused))) {
    struct ksu_probe_result result;
    int probe_ok = get_kernelsu_info(&result);
    char output[192];
    snprintf(output, sizeof(output),
            "probe=%d;present=%d;responsive=%d;granted=%d;version=%u;flags=%u;features=%u;uapi=%u",
            probe_ok, result.driver_present, result.driver_responsive, result.app_root_granted,
            result.version, result.flags, result.features, result.uapi_version);
    return (*env)->NewStringUTF(env, output);
}

JNIEXPORT jstring JNICALL
Java_com_alex193a_rootmypixel_utils_NativeProbe_run(
    JNIEnv *env, jobject thiz __attribute__((unused))) {

    char output[4096];
    int off = 0;

    struct utsname uts;
    if (uname(&uts) == 0) {
        off += snprintf(output + off, sizeof(output) - off,
                        "sysname: %s\n", uts.sysname);
        off += snprintf(output + off, sizeof(output) - off,
                        "nodename: %s\n", uts.nodename);
        off += snprintf(output + off, sizeof(output) - off,
                        "release: %s\n", uts.release);
        off += snprintf(output + off, sizeof(output) - off,
                        "version: %s\n", uts.version);
        off += snprintf(output + off, sizeof(output) - off,
                        "machine: %s\n", uts.machine);
    }

    char version[512] = {0};
    if (read_file("/proc/version", version, sizeof(version)) == 0) {
        off += snprintf(output + off, sizeof(output) - off,
                        "proc_version: %s", version);
    }

    char model[PROP_VALUE_MAX];
    char device[PROP_VALUE_MAX];
    char build[PROP_VALUE_MAX];
    char sdk[PROP_VALUE_MAX];
    char abi[PROP_VALUE_MAX];
    char fingerprint[PROP_VALUE_MAX];

    get_prop("ro.product.model", model, sizeof(model));
    get_prop("ro.product.device", device, sizeof(device));
    get_prop("ro.build.display.id", build, sizeof(build));
    get_prop("ro.build.version.sdk", sdk, sizeof(sdk));
    get_prop("ro.product.cpu.abi", abi, sizeof(abi));
    get_prop("ro.build.fingerprint", fingerprint, sizeof(fingerprint));

    off += snprintf(output + off, sizeof(output) - off,
                    "\nmodel: %s\ndevice: %s\nbuild: %s\n"
                    "sdk: %s\nabi: %s\nfingerprint: %s\n"
                    "pid: %d uid: %d\n",
                    model, device, build, sdk, abi, fingerprint,
                    getpid(), getuid());

    struct ksu_probe_result ksu_result;
    if (get_kernelsu_info(&ksu_result) && ksu_result.driver_responsive) {
        off += snprintf(output + off, sizeof(output) - off,
                "kernelsu: active version=%u flags=0x%x uapi=%u\n",
                ksu_result.version, ksu_result.flags, ksu_result.uapi_version);
    }

    return (*env)->NewStringUTF(env, output);
}
