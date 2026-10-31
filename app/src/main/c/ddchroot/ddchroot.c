// ddchroot: runs a guest command in the Linux rootfs with a real chroot instead of proot.
// It accepts the proot options DroidDeck passes (-r, -w, -b, -i, -0 and a few no-op flags),
// gains root through su, builds a private mount and PID namespace, then drops back to the
// caller's uid and groups before the guest command starts.
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <limits.h>
#include <sched.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#define MAX_BINDS 512
#define MAX_GROUPS 128

struct bind { const char *src; const char *dst; };

static const char *opt_root;
static const char *opt_cwd = "/";
static struct bind binds[MAX_BINDS];
static int nbinds;
static char **guest_argv;
static uid_t target_uid;
static gid_t target_gid;
static gid_t groups[MAX_GROUPS];
static int ngroups;
static int verbose;

static void die(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    fputs("ddchroot: ", stderr);
    vfprintf(stderr, fmt, ap);
    if (errno) fprintf(stderr, ": %s", strerror(errno));
    fputc('\n', stderr);
    va_end(ap);
    _exit(126);
}

static void note(const char *fmt, ...) {
    if (!verbose) return;
    va_list ap;
    va_start(ap, fmt);
    fputs("ddchroot: ", stderr);
    vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    va_end(ap);
}

static void parse(int argc, char **argv) {
    int i = 1;
    for (; i < argc; i++) {
        const char *a = argv[i];
        if (a[0] != '-') break;
        if (!strcmp(a, "--")) { i++; break; }
        if (!strcmp(a, "-r") && i + 1 < argc) opt_root = argv[++i];
        else if (!strncmp(a, "--rootfs=", 9)) opt_root = a + 9;
        else if (!strcmp(a, "-w") && i + 1 < argc) opt_cwd = argv[++i];
        else if (!strncmp(a, "--cwd=", 6)) opt_cwd = a + 6;
        else if ((!strcmp(a, "-b") || !strcmp(a, "-m")) && i + 1 < argc) {
            if (nbinds >= MAX_BINDS) die("too many binds");
            char *spec = argv[++i];
            char *colon = strchr(spec, ':');
            if (colon) { *colon = 0; binds[nbinds].dst = colon + 1; } else binds[nbinds].dst = spec;
            binds[nbinds++].src = spec;
        } else if (!strcmp(a, "-i") && i + 1 < argc) {
            char *spec = argv[++i];
            target_uid = (uid_t) strtoul(spec, &spec, 10);
            target_gid = *spec == ':' ? (gid_t) strtoul(spec + 1, NULL, 10) : target_gid;
        } else if (!strcmp(a, "-0")) {
            errno = 0;
            die("-0 (fake root) is not supported; DroidDeck keeps proot for that");
        } else if (!strcmp(a, "-v") && i + 1 < argc) verbose = atoi(argv[++i]) > 0;
        else if (!strcmp(a, "--kill-on-exit") || !strcmp(a, "--link2symlink") ||
                 !strncmp(a, "--kernel-release=", 17) || !strcmp(a, "-q")) continue;
        else { errno = 0; die("unknown option %s", a); }
    }
    if (!opt_root) { errno = 0; die("no -r rootfs"); }
    if (i >= argc) { errno = 0; die("no guest command"); }
    guest_argv = argv + i;
}

/* State handed from the unprivileged first stage to the root stage through a memfd. */
static void write_all(int fd, const void *buf, size_t len) {
    const char *p = buf;
    while (len) {
        ssize_t n = write(fd, p, len);
        if (n < 0) { if (errno == EINTR) continue; die("write state"); }
        p += n; len -= (size_t) n;
    }
}

static void put_str(int fd, const char *s) { write_all(fd, s, strlen(s) + 1); }

extern char **environ;

static int save_state(int argc, char **argv) {
    int fd = (int) syscall(__NR_memfd_create, "ddchroot", 0);
    if (fd < 0) die("memfd_create");
    char num[32];
    snprintf(num, sizeof num, "%u", (unsigned) getuid()); put_str(fd, num);
    snprintf(num, sizeof num, "%u", (unsigned) getgid()); put_str(fd, num);
    gid_t g[MAX_GROUPS];
    int n = getgroups(MAX_GROUPS, g);
    if (n < 0) n = 0;
    snprintf(num, sizeof num, "%d", n); put_str(fd, num);
    for (int k = 0; k < n; k++) { snprintf(num, sizeof num, "%u", (unsigned) g[k]); put_str(fd, num); }
    snprintf(num, sizeof num, "%d", argc); put_str(fd, num);
    for (int k = 0; k < argc; k++) put_str(fd, argv[k]);
    for (char **e = environ; *e; e++) put_str(fd, *e);
    lseek(fd, 0, SEEK_SET);
    return fd;
}

static char *state_buf;
static size_t state_len, state_pos;

static const char *next_str(void) {
    if (state_pos >= state_len) { errno = 0; die("truncated state"); }
    const char *s = state_buf + state_pos;
    state_pos += strlen(s) + 1;
    return s;
}

static char **load_state(int fd, int *argc_out) {
    struct stat st;
    if (fstat(fd, &st) < 0) die("fstat state");
    state_len = (size_t) st.st_size;
    state_buf = malloc(state_len + 1);
    if (!state_buf) die("malloc");
    size_t got = 0;
    while (got < state_len) {
        ssize_t n = pread(fd, state_buf + got, state_len - got, (off_t) got);
        if (n <= 0) die("read state");
        got += (size_t) n;
    }
    state_buf[state_len] = 0;
    close(fd);
    target_uid = (uid_t) strtoul(next_str(), NULL, 10);
    target_gid = (gid_t) strtoul(next_str(), NULL, 10);
    ngroups = atoi(next_str());
    if (ngroups > MAX_GROUPS) ngroups = MAX_GROUPS;
    for (int k = 0; k < ngroups; k++) groups[k] = (gid_t) strtoul(next_str(), NULL, 10);
    int argc = atoi(next_str());
    char **argv = calloc((size_t) argc + 1, sizeof(char *));
    for (int k = 0; k < argc; k++) argv[k] = (char *) next_str();
    clearenv();
    while (state_pos < state_len) putenv((char *) next_str());
    *argc_out = argc;
    return argv;
}

static void become_root(int argc, char **argv) {
    int fd = save_state(argc, argv);
    char self[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", self, sizeof self - 1);
    if (n < 0) die("readlink self");
    self[n] = 0;
    char cmd[PATH_MAX + 64];
    snprintf(cmd, sizeof cmd, "exec '%s' --ddchroot-state %d", self, fd);
    static const char *su[] = {"/system/bin/su", "/system/xbin/su", "/debug_ramdisk/su", "/sbin/su", NULL};
    for (int k = 0; su[k]; k++) execl(su[k], "su", "-c", cmd, (char *) NULL);
    die("cannot run su");
}

/* Resolves path inside root the way the guest would see it, creating what is missing. */
static int resolve(const char *root, const char *guest, int want_dir, char *out) {
    char work[PATH_MAX], rest[PATH_MAX];
    snprintf(rest, sizeof rest, "%s", guest);
    snprintf(work, sizeof work, "%s", root);
    size_t base = strlen(root);
    int hops = 0;
    char *p = rest;
    while (*p) {
        while (*p == '/') p++;
        if (!*p) break;
        char *slash = strchr(p, '/');
        int last = !slash;
        if (slash) *slash = 0;
        if (!strcmp(p, ".")) { p = last ? p + strlen(p) : slash + 1; continue; }
        if (!strcmp(p, "..")) {
            char *cut = strrchr(work + base, '/');
            if (cut) *cut = 0;
            p = last ? p + strlen(p) : slash + 1;
            continue;
        }
        size_t len = strlen(work);
        if (len + 1 + strlen(p) >= PATH_MAX) return -1;
        work[len] = '/';
        strcpy(work + len + 1, p);
        struct stat st;
        if (lstat(work, &st) == 0 && S_ISLNK(st.st_mode)) {
            if (++hops > 40) return -1;
            char target[PATH_MAX];
            ssize_t n = readlink(work, target, sizeof target - 1);
            if (n < 0) return -1;
            target[n] = 0;
            char tail[PATH_MAX];
            snprintf(tail, sizeof tail, "%s", last ? "" : slash + 1);
            if (target[0] == '/') work[base] = 0; else work[len] = 0;
            snprintf(rest, sizeof rest, "%s/%s", target, tail);
            p = rest;
            continue;
        }
        if (lstat(work, &st) != 0) {
            if (!last || want_dir) {
                if (mkdir(work, 0755) != 0 && errno != EEXIST) return -1;
            } else {
                int fd = open(work, O_CREAT | O_WRONLY | O_CLOEXEC, 0644);
                if (fd < 0) return -1;
                close(fd);
            }
            // Mount points land in the app's own rootfs, so the app must still be able to delete them.
            (void) !lchown(work, target_uid, target_gid);
        }
        p = last ? p + strlen(p) : slash + 1;
    }
    snprintf(out, PATH_MAX, "%s", work);
    return 0;
}

static void bind_path(const char *src, const char *guest_dst) {
    struct stat st;
    if (stat(src, &st) != 0) { note("skip missing %s", src); return; }
    char dst[PATH_MAX];
    if (resolve(opt_root, guest_dst, S_ISDIR(st.st_mode), dst) != 0) { note("cannot place %s", guest_dst); return; }
    unsigned long flags = MS_BIND | (S_ISDIR(st.st_mode) ? MS_REC : 0);
    if (mount(src, dst, NULL, flags, NULL) != 0) note("bind %s -> %s failed: %s", src, dst, strerror(errno));
}

static void populate_dev(const char *dev) {
    if (mount("tmpfs", dev, "tmpfs", MS_NOSUID, "mode=755") != 0) die("tmpfs on %s", dev);
    DIR *d = opendir("/dev");
    if (!d) die("opendir /dev");
    struct dirent *e;
    char src[PATH_MAX], dst[PATH_MAX];
    while ((e = readdir(d))) {
        const char *n = e->d_name;
        if (!strcmp(n, ".") || !strcmp(n, "..") || !strcmp(n, "fd") || !strcmp(n, "stdin") ||
            !strcmp(n, "stdout") || !strcmp(n, "stderr")) continue;
        snprintf(src, sizeof src, "/dev/%s", n);
        snprintf(dst, sizeof dst, "%s/%s", dev, n);
        struct stat st;
        if (lstat(src, &st) != 0) continue;
        if (S_ISLNK(st.st_mode)) {
            char t[PATH_MAX];
            ssize_t len = readlink(src, t, sizeof t - 1);
            if (len < 0) continue;
            t[len] = 0;
            (void) !symlink(t, dst);
        } else if (S_ISDIR(st.st_mode)) {
            mkdir(dst, st.st_mode & 07777);
            mount(src, dst, NULL, MS_BIND | MS_REC, NULL);
        } else {
            int fd = open(dst, O_CREAT | O_WRONLY | O_CLOEXEC, 0600);
            if (fd < 0) continue;
            close(fd);
            mount(src, dst, NULL, MS_BIND, NULL);
        }
    }
    closedir(d);
    snprintf(dst, sizeof dst, "%s/fd", dev); (void) !symlink("/proc/self/fd", dst);
    snprintf(dst, sizeof dst, "%s/stdin", dev); (void) !symlink("/proc/self/fd/0", dst);
    snprintf(dst, sizeof dst, "%s/stdout", dev); (void) !symlink("/proc/self/fd/1", dst);
    snprintf(dst, sizeof dst, "%s/stderr", dev); (void) !symlink("/proc/self/fd/2", dst);
}

static void setup_mounts(void) {
    if (mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL) != 0) die("make mounts private");
    for (int k = 0; k < nbinds; k++) {
        const char *src = binds[k].src, *dst = binds[k].dst;
        if (!strncmp(src, "/proc/self/fd", 13)) continue;
        if (!strcmp(src, "/dev") && !strcmp(dst, "/dev")) {
            char dev[PATH_MAX];
            if (resolve(opt_root, "/dev", 1, dev) != 0) die("place /dev");
            populate_dev(dev);
        } else if (!strcmp(src, "/proc") && !strcmp(dst, "/proc")) {
            char proc[PATH_MAX];
            if (resolve(opt_root, "/proc", 1, proc) != 0) die("place /proc");
            if (mount("proc", proc, "proc", MS_NOSUID | MS_NODEV | MS_NOEXEC, NULL) != 0) die("mount proc");
        } else {
            bind_path(src, dst);
        }
    }
}

static void drop_privileges(void) {
    if (setgroups((size_t) ngroups, groups) != 0) die("setgroups");
    if (setresgid(target_gid, target_gid, target_gid) != 0) die("setresgid");
    if (setresuid(target_uid, target_uid, target_uid) != 0) die("setresuid");
}

static volatile pid_t forward_to;

static void forward(int sig) { if (forward_to > 0) kill(forward_to, sig); }

static void forward_signals(void) {
    static const int sigs[] = {SIGTERM, SIGINT, SIGHUP, SIGQUIT, SIGUSR1, SIGUSR2, SIGCONT, SIGWINCH};
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = forward;
    sa.sa_flags = SA_RESTART;
    for (size_t k = 0; k < sizeof sigs / sizeof sigs[0]; k++) sigaction(sigs[k], &sa, NULL);
}

static int exit_code(int status) {
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 1;
}

/* PID 1 of the guest namespace: starts the command, reaps orphans, exits with the command. */
static int guest_init(void) {
    prctl(PR_SET_PDEATHSIG, SIGKILL);
    setup_mounts();
    if (chdir(opt_root) != 0 || chroot(".") != 0) die("chroot %s", opt_root);
    drop_privileges();
    prctl(PR_SET_PDEATHSIG, SIGKILL);
    if (chdir(opt_cwd) != 0 && chdir("/") != 0) die("chdir");
    pid_t child = fork();
    if (child < 0) die("fork");
    if (child == 0) {
        sigset_t none;
        sigemptyset(&none);
        sigprocmask(SIG_SETMASK, &none, NULL);
        execvp(guest_argv[0], guest_argv);
        die("exec %s", guest_argv[0]);
    }
    forward_to = child;
    forward_signals();
    for (;;) {
        int status;
        pid_t pid = waitpid(-1, &status, 0);
        if (pid == child) return exit_code(status);
        if (pid < 0 && errno == ECHILD) return 1;
    }
}

int main(int argc, char **argv) {
    if (argc >= 3 && !strcmp(argv[1], "--ddchroot-state")) {
        argv = load_state(atoi(argv[2]), &argc);
    } else if (getuid() != 0) {
        become_root(argc, argv);
    }
    if (geteuid() != 0) { errno = 0; die("su did not grant root"); }
    if (!opt_root) parse(argc, argv);
    if (!target_uid && !target_gid) { errno = 0; die("refusing to run the guest as root"); }

    if (unshare(CLONE_NEWNS | CLONE_NEWPID) != 0) die("unshare");
    pid_t init = fork();
    if (init < 0) die("fork");
    if (init == 0) _exit(guest_init());

    // The outer process stays the one DroidDeck holds; it gives up root so the app can signal it.
    if (setgroups((size_t) ngroups, groups) != 0 || setresgid(target_gid, target_gid, target_gid) != 0 ||
        setresuid(target_uid, target_uid, target_uid) != 0) {
        kill(init, SIGKILL);
        die("drop privileges");
    }
    forward_to = init;
    forward_signals();
    for (;;) {
        int status;
        pid_t pid = waitpid(init, &status, 0);
        if (pid == init) return exit_code(status);
        if (pid < 0 && errno != EINTR) return 1;
    }
}
