/*
 * Copyright (C) 2026 The andr36oid Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// andr36oid: the boot log on screen. Holding Select (BTN_SELECT) while the boot animation
// runs, or persist.andr36oid.bootlog=1, swaps the animation for the kernel log and logcat
// as scrolling text: errors red, warnings yellow. The home screen comes as usual when the
// start is done; if it hangs, the last lines stay up. Both logs are read from the
// beginning, so nothing before the switch is lost.
//
// bootanim.rc gives the service what it needs: the input group (the key), the log group
// (logcat) and CAP_SYSLOG (the kernel log, dmesg_restrict is on).

#define LOG_NDEBUG 0
#define LOG_TAG "BootAnimation"

#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/klog.h>
#include <sys/wait.h>
#include <unistd.h>
#include <math.h>

#include <algorithm>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <cutils/properties.h>
#include <utils/Log.h>
#include <utils/SystemClock.h>
#include <utils/Timers.h>

#include <SkBitmap.h>
#include <SkCanvas.h>
#include <SkFont.h>
#include <SkFontMetrics.h>
#include <SkPaint.h>
#include <SkTypeface.h>

#include <GLES/gl.h>
#include <GLES/glext.h>

#include "BootAnimation.h"

namespace android {

namespace {

constexpr const char* kLogProp = "persist.andr36oid.bootlog";
constexpr const char* kFontFile = "/system/fonts/DroidSansMono.ttf";
constexpr nsecs_t kKeyCheckInterval = ms2ns(100);
constexpr nsecs_t kKernelPollInterval = ms2ns(500);
constexpr nsecs_t kFrameInterval = ms2ns(200);
// Lines kept (after wrapping): a few screens back is plenty
constexpr size_t kMaxLines = 1000;

constexpr SkColor kTextColor = SkColorSetRGB(0xc8, 0xc8, 0xc8);
constexpr SkColor kWarnColor = SkColorSetRGB(0xff, 0xd0, 0x40);
constexpr SkColor kErrorColor = SkColorSetRGB(0xff, 0x60, 0x60);
constexpr SkColor kHeaderColor = SkColorSetRGB(0x00, 0x30, 0x30);
constexpr SkColor kHeaderTextColor = SkColorSetRGB(0x60, 0xe0, 0xd0);

constexpr const char* kResizeProp = "sys.andr36oid.resizing";
constexpr const char* kResizeText = "Resizing SD card ...";      // sys.andr36oid.resizing=1
constexpr const char* kReclaimText = "Cleaning up first boot partitions ..."; // =2
constexpr const char* kResizeFontFile = "/system/fonts/Roboto-Regular.ttf";
constexpr SkColor kResizeColor = SkColorSetRGB(0x8c, 0x8c, 0x8c);

// The boot stages for the progress bar and the details line (drawBootNotes). A stage starts
// when its property is set (to value, or to a number of at least minimum; else to anything).
// seconds: how long it usually takes on an R36S, from a measured start; the bar shares
// follow these. Stage 0 starts with the animation, at late-fs.
constexpr const char* kStageBarMarker = "/cache/andr36oid/bootprogress";
constexpr const char* kStageTextMarker = "/cache/andr36oid/bootdetails";
constexpr nsecs_t kStageCheckInterval = ms2ns(250);

struct BootStage {
    const char* prop;
    const char* value;
    int minimum;
    const char* text;
    float seconds;
};

constexpr BootStage kStages[] = {
    // fsck and mount of userdata (init.rk30board.rc, late-fs)
    {nullptr, nullptr, 0, "Mounting partitions ...", 1.0f},
    // init sets it right after mounting userdata; post-fs-data follows
    {"ro.crypto.state", nullptr, 0, "Preparing data ...", 2.0f},
    // zygote loads the framework classes
    {"init.svc.zygote", "running", 0, "Starting Android ...", 12.0f},
    // SystemServer.run()
    {"sys.system_server.start_count", nullptr, 0, "Starting system services ...", 1.5f},
    // SystemServiceManager, PHASE_WAIT_FOR_DEFAULT_DISPLAY: the package manager scans next
    {"sys.andr36oid.boot_phase", nullptr, 100, "Loading apps ...", 9.0f},
    // WindowManager's display is ready (ProcessList.updateOomLevels)
    {"sys.lmk.minfree_levels", nullptr, 0, "Starting the display and services ...", 5.0f},
    // PHASE_ACTIVITY_MANAGER_READY: SystemUI and the home screen
    {"sys.andr36oid.boot_phase", nullptr, 550, "Starting apps ...", 8.0f},
};
constexpr int kStageCount = sizeof(kStages) / sizeof(kStages[0]);

bool stageReached(const BootStage& stage) {
    char value[PROPERTY_VALUE_MAX];
    if (property_get(stage.prop, value, "") <= 0) return false;
    if (stage.value != nullptr) return strcmp(value, stage.value) == 0;
    if (stage.minimum > 0) return atoi(value) >= stage.minimum;
    return true;
}

enum class Level { kInfo, kWarn, kError };

struct LogLine {
    std::string text;
    Level level;
};

bool testBit(const unsigned char* bits, int bit) {
    return bits[bit / 8] & (1 << (bit % 8));
}

// The logcat child: its lines come through a pipe, read by a thread
class LogcatReader {
public:
    bool start() {
        int fds[2];
        if (pipe2(fds, O_CLOEXEC) != 0) return false;
        mPid = fork();
        if (mPid < 0) {
            close(fds[0]);
            close(fds[1]);
            return false;
        }
        if (mPid == 0) {
            dup2(fds[1], STDOUT_FILENO);
            int null = open("/dev/null", O_WRONLY);
            if (null >= 0) dup2(null, STDERR_FILENO);
            execl("/system/bin/logcat", "logcat", "-b", "main,system,crash", "-v", "threadtime",
                  "-v", "monotonic", nullptr);
            _exit(127);
        }
        close(fds[1]);
        mFile = fdopen(fds[0], "r");
        if (mFile == nullptr) {
            close(fds[0]);
            return false;
        }
        mThread = std::thread([this] { readLoop(); });
        return true;
    }

    void stop() {
        if (mPid > 0) {
            kill(mPid, SIGTERM);
            waitpid(mPid, nullptr, 0);
            mPid = -1;
        }
        if (mThread.joinable()) mThread.join();
        if (mFile != nullptr) {
            fclose(mFile);
            mFile = nullptr;
        }
    }

    void take(std::vector<LogLine>* out) {
        std::lock_guard<std::mutex> lock(mLock);
        for (auto& l : mPending) out->push_back(std::move(l));
        mPending.clear();
    }

private:
    // "    5.123  123  456 I tag     : message" -> "   5.123 I tag: message"
    static bool format(const char* raw, LogLine* line) {
        double secs;
        int pid, tid, n = 0;
        char prio;
        if (sscanf(raw, "%lf %d %d %c %n", &secs, &pid, &tid, &prio, &n) != 4 || n == 0) {
            return false;
        }
        std::string rest(raw + n);
        // the tag is padded to a column: "tag     : message"
        size_t colon = rest.find(": ");
        if (colon != std::string::npos) {
            size_t end = colon;
            while (end > 0 && rest[end - 1] == ' ') end--;
            rest = rest.substr(0, end) + rest.substr(colon);
        }
        char head[32];
        snprintf(head, sizeof(head), "%8.3f %c ", secs, prio);
        line->text = head + rest;
        line->level = (prio == 'E' || prio == 'F') ? Level::kError
                : prio == 'W' ? Level::kWarn : Level::kInfo;
        return true;
    }

    void readLoop() {
        char* buf = nullptr;
        size_t cap = 0;
        ssize_t len;
        while ((len = getline(&buf, &cap, mFile)) > 0) {
            while (len > 0 && (buf[len - 1] == '\n' || buf[len - 1] == '\r')) buf[--len] = '\0';
            LogLine line;
            if (strncmp(buf, "---------", 9) == 0 || !format(buf, &line)) continue;
            std::lock_guard<std::mutex> lock(mLock);
            mPending.push_back(std::move(line));
        }
        free(buf);
    }

    pid_t mPid = -1;
    FILE* mFile = nullptr;
    std::thread mThread;
    std::mutex mLock;
    std::vector<LogLine> mPending;
};

// The kernel log: the whole ring buffer is read each time, the lines after the last one
// seen are new
class KernelReader {
public:
    void poll(std::vector<LogLine>* out) {
        int size = klogctl(10 /* SYSLOG_ACTION_SIZE_BUFFER */, nullptr, 0);
        if (size <= 0) return;
        std::string buf(size, '\0');
        int n = klogctl(3 /* SYSLOG_ACTION_READ_ALL */, &buf[0], size);
        if (n <= 0) {
            if (!mWarned) {
                ALOGW("boot log: can't read the kernel log: %s", strerror(errno));
                mWarned = true;
            }
            return;
        }
        buf.resize(n);
        std::vector<std::string> lines;
        size_t pos = 0;
        while (pos < buf.size()) {
            size_t nl = buf.find('\n', pos);
            if (nl == std::string::npos) break;  // a line still being written
            lines.push_back(buf.substr(pos, nl - pos));
            pos = nl + 1;
        }
        size_t first = 0;
        if (!mLast.empty()) {
            for (size_t i = lines.size(); i > 0; i--) {
                if (lines[i - 1] == mLast) {
                    first = i;
                    break;
                }
            }
        }
        for (size_t i = first; i < lines.size(); i++) out->push_back(format(lines[i]));
        if (!lines.empty()) mLast = lines.back();
    }

private:
    // "<4>[    5.123456] message" -> "   5.123 K message"
    static LogLine format(const std::string& raw) {
        LogLine line{raw, Level::kInfo};
        const char* p = raw.c_str();
        int level = 6;
        if (p[0] == '<' && p[1] >= '0' && p[1] <= '7' && p[2] == '>') {
            level = p[1] - '0';
            p += 3;
        }
        double secs;
        int n = 0;
        if (sscanf(p, "[%lf] %n", &secs, &n) == 1 && n > 0) {
            char head[32];
            snprintf(head, sizeof(head), "%8.3f K ", secs);
            line.text = std::string(head) + (p + n);
        } else {
            line.text = p;
        }
        line.level = level <= 3 ? Level::kError : level == 4 ? Level::kWarn : Level::kInfo;
        return line;
    }

    std::string mLast;
    bool mWarned = false;
};

// Splits a line to the screen width, without cutting a UTF-8 character in two
void wrap(const LogLine& line, size_t cols, std::deque<LogLine>* out) {
    const std::string& t = line.text;
    size_t pos = 0;
    bool first = true;
    do {
        size_t width = first ? cols : cols - 2;
        size_t end = std::min(t.size(), pos + width);
        while (end < t.size() && end > pos + 1 && (t[end] & 0xC0) == 0x80) end--;
        out->push_back({(first ? "" : "  ") + t.substr(pos, end - pos), line.level});
        first = false;
        pos = end;
    } while (pos < t.size());
    while (out->size() > kMaxLines) out->pop_front();
}

}  // namespace

bool BootAnimation::logRequested() {
    if (mLogMode) return true;
    if (mShuttingDown) return false;
    const nsecs_t now = systemTime();
    if (now - mLastKeyCheck < kKeyCheckInterval) return false;
    mLastKeyCheck = now;

    if (property_get_bool(kLogProp, false)) {
        ALOGI("boot log: on (%s)", kLogProp);
        mLogMode = true;
        mLogReason = kLogProp;
        return true;
    }
    if (!mKeysOpened) {
        // The pads that have a Select key
        mKeysOpened = true;
        DIR* dir = opendir("/dev/input");
        if (dir != nullptr) {
            while (struct dirent* e = readdir(dir)) {
                if (strncmp(e->d_name, "event", 5) != 0) continue;
                std::string path = std::string("/dev/input/") + e->d_name;
                int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NONBLOCK);
                if (fd < 0) continue;
                unsigned char bits[KEY_MAX / 8 + 1] = {};
                if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(bits)), bits) >= 0
                        && testBit(bits, BTN_SELECT)) {
                    mKeyFds.push_back(fd);
                } else {
                    close(fd);
                }
            }
            closedir(dir);
        }
        if (mKeyFds.empty()) ALOGW("boot log: no input device with a Select key");
    }
    for (int fd : mKeyFds) {
        unsigned char keys[KEY_MAX / 8 + 1] = {};
        if (ioctl(fd, EVIOCGKEY(sizeof(keys)), keys) >= 0 && testBit(keys, BTN_SELECT)) {
            ALOGI("boot log: on (Select held)");
            mLogMode = true;
            mLogReason = "Select";
            return true;
        }
    }
    return false;
}

void BootAnimation::closeLogKeys() {
    for (int fd : mKeyFds) close(fd);
    mKeyFds.clear();
}

bool BootAnimation::logScreen() {
    closeLogKeys();
    const int w = mWidth;
    const int h = mHeight;

    sk_sp<SkTypeface> face = SkTypeface::MakeFromFile(kFontFile);
    if (face == nullptr) {
        ALOGE("boot log: no font %s", kFontFile);
        face = SkTypeface::MakeDefault();
    }
    // About 80 columns whatever the screen
    SkFont font(face, h >= 720 ? 18 : 12);
    font.setEdging(SkFont::Edging::kAntiAlias);
    SkFontMetrics metrics;
    font.getMetrics(&metrics);
    const int lineHeight = std::max(1, (int)ceilf(metrics.fDescent - metrics.fAscent));
    const SkScalar charWidth = font.measureText("M", 1, SkTextEncoding::kUTF8);
    const size_t cols = std::max<size_t>(20, (size_t)(w / std::max<SkScalar>(1, charWidth)) - 1);
    const int rows = std::max(1, h / lineHeight - 1);

    char version[PROPERTY_VALUE_MAX];
    property_get("ro.andr36oid.version", version, "");
    char header[256];
    snprintf(header, sizeof(header), "andr36oid %s - boot log (%s)", version, mLogReason.c_str());

    SkBitmap bitmap;
    bitmap.allocPixels(SkImageInfo::Make(w, h, kRGBA_8888_SkColorType, kOpaque_SkAlphaType));
    SkCanvas canvas(bitmap);
    SkPaint paint;
    paint.setAntiAlias(true);

    // The same texture setup as the animation's frames (see initTexture)
    bool npot = false;
    const char* exts = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
    if (exts != nullptr) {
        npot = strstr(exts, "GL_ARB_texture_non_power_of_two") != nullptr
                || strstr(exts, "GL_OES_texture_npot") != nullptr;
    }
    int tw = 1 << (31 - __builtin_clz(w));
    int th = 1 << (31 - __builtin_clz(h));
    if (tw < w) tw <<= 1;
    if (th < h) th <<= 1;
    if (npot) {
        tw = w;
        th = h;
    }

    glShadeModel(GL_FLAT);
    glDisable(GL_DITHER);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);
    glEnable(GL_TEXTURE_2D);
    glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
    GLuint texture;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, tw, th, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    GLint crop[4] = { 0, h, w, -h };
    glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, crop);

    LogcatReader logcat;
    if (!logcat.start()) ALOGE("boot log: can't start logcat");
    KernelReader kernel;
    std::deque<LogLine> screen;
    std::vector<LogLine> incoming;
    nsecs_t lastKernelPoll = 0;
    bool dirty = true;

    ALOGI("boot log: %zu x %d characters", cols, rows);
    do {
        const nsecs_t now = systemTime();
        if (now - lastKernelPoll >= kKernelPollInterval) {
            kernel.poll(&incoming);
            lastKernelPoll = now;
        }
        logcat.take(&incoming);
        if (!incoming.empty()) {
            for (const LogLine& l : incoming) wrap(l, cols, &screen);
            incoming.clear();
            dirty = true;
        }

        if (dirty) {
            canvas.clear(SK_ColorBLACK);
            paint.setColor(kHeaderColor);
            canvas.drawRect(SkRect::MakeWH(w, lineHeight), paint);
            paint.setColor(kHeaderTextColor);
            canvas.drawString(header, 2, -metrics.fAscent, font, paint);
            const size_t shown = std::min(screen.size(), (size_t)rows);
            int y = lineHeight;
            for (size_t i = screen.size() - shown; i < screen.size(); i++) {
                const LogLine& l = screen[i];
                paint.setColor(l.level == Level::kError ? kErrorColor
                        : l.level == Level::kWarn ? kWarnColor : kTextColor);
                canvas.drawSimpleText(l.text.data(), l.text.size(), SkTextEncoding::kUTF8,
                        2, y - metrics.fAscent, font, paint);
                y += lineHeight;
            }
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE,
                    bitmap.getPixels());
            dirty = false;
        }
        processDisplayEvents();
        glDrawTexiOES(0, 0, 0, w, h);
        eglSwapBuffers(mDisplay, mSurface);

        usleep(ns2us(kFrameInterval));
        checkExit();
    } while (!exitPending());

    logcat.stop();
    glDeleteTextures(1, &texture);
    return false;
}

// andr36oid: the lines under the logo. The logo fills the middle third of the animation
// (gen-bootanimation.sh), the text goes just below it, the progress bar under the text.
//
// "Resizing SD card ..." in grey while the first start sets up the card:
// andr36oid-firstresize.sh sets sys.andr36oid.resizing=1 and init clears it at
// post-fs-data, after Android has formatted userdata. 2: "Cleaning up first boot partitions ...",
// while userdata grows over the bundled apps' partition (AUTOAPK) once they're installed.
//
// Settings > Personalization > Boot progress bar / Show boot details: the stage the start is
// in, and a bar that moves with it. The animation starts before /data and so before persist.*
// is there, so andr36oid-bootanim.rc keeps both as marker files on /cache, looked at once.
// Off, nothing else happens here. On, a few system properties are read 4 times a second.
void BootAnimation::drawBootNotes(int x, int y, int w, int h) {
    if (w <= 0 || h <= 0) return;
    if (!mStagesChecked) {
        mStagesChecked = true;
        if (!mShuttingDown) {
            mStageBar = access(kStageBarMarker, F_OK) == 0;
            mStageText = access(kStageTextMarker, F_OK) == 0;
            // The first start (the card being resized) always shows the bar: it takes two
            // minutes, and nobody could have switched it on yet
            char resizing[PROPERTY_VALUE_MAX];
            property_get(kResizeProp, resizing, "0");
            mFirstBoot = strcmp(resizing, "1") == 0;
            if (mFirstBoot) mStageBar = true;
            if (mStageBar || mStageText) {
                ALOGI("boot stages: bar %d, details %d", mStageBar, mStageText);
            }
        }
    }
    const nsecs_t now = systemTime();
    if (mStageBar || mStageText) updateBootStage(now);

    char value[PROPERTY_VALUE_MAX];
    property_get(kResizeProp, value, "0");
    const char* text = strcmp(value, "1") == 0 ? kResizeText
            : strcmp(value, "2") == 0 ? kReclaimText : nullptr;
    if (text == nullptr && mStageText && mStage >= 0) text = kStages[mStage].text;

    const int top = y + h * 2 / 3 + h / 30;
    const int fontSize = std::max(12, h / 24);
    if (text != nullptr) drawNoticeLine(text, x, top, w, fontSize);
    if (mStageBar) drawProgressBar(x, top + fontSize * 2, w, now);
}

// A stage's usual length. The first start adds the resize and the format to the first
// stage (about 60 s), the first dexopt to the package scan (about 17 s) and the setup
// wizard's start to the last one, as measured on a G80C.
float BootAnimation::stageSeconds(int i) const {
    const float s = kStages[i].seconds;
    if (!mFirstBoot) return s;
    if (i == 0) return s + 60.0f;
    if (i == 4) return s + 17.0f;
    if (i == kStageCount - 1) return s + 5.0f;
    return s;
}

// The furthest stage whose signal is there; skipped ones don't matter, it never goes back
void BootAnimation::updateBootStage(nsecs_t now) {
    if (mStage >= 0 && now - mLastStageCheck < kStageCheckInterval) return;
    mLastStageCheck = now;
    int stage = std::max(mStage, 0);
    for (int i = kStageCount - 1; i > stage; i--) {
        if (stageReached(kStages[i])) {
            stage = i;
            break;
        }
    }
    if (stage != mStage) {
        mStage = stage;
        mStageStart = now;
        ALOGI("boot stage %d: %s", stage, kStages[stage].text);
    }
}

// Each stage owns a share of the bar as long as it usually takes (kStages). Inside a stage
// the bar creeps towards its end but stops at 90% of it until the next stage shows up.
// When the start is done the bar fills up and fades out while the animation plays its
// last part.
void BootAnimation::drawProgressBar(int x, int top, int w, nsecs_t now) {
    float total = 0, before = 0;
    for (int i = 0; i < kStageCount; i++) {
        if (i < mStage) before += stageSeconds(i);
        total += stageSeconds(i);
    }
    float p = 1.0f;
    float fade = 1.0f;  // 1 visible, 0 gone
    if (!exitPending()) {
        const float span = stageSeconds(std::max(mStage, 0));
        const float t = (now - mStageStart) / 1e9f;
        p = (before + span * 0.9f * (1.0f - expf(-t / span))) / total;
    } else {
        // The start is done: the bar runs to the end in 0.3 s, then fades out in 0.5 s
        if (mBarDoneAt == 0) {
            mBarDoneAt = now;
            mBarDoneFrom = mProgress;
        }
        const float t = (now - mBarDoneAt) / 1e9f;
        p = mBarDoneFrom + (1.0f - mBarDoneFrom) * std::min(1.0f, t / 0.3f);
        fade = 1.0f - std::max(0.0f, std::min(1.0f, (t - 0.3f) / 0.5f));
    }
    mProgress = std::min(1.0f, std::max(mProgress, p));

    const int bw = std::min(w, mWidth) * 2 / 5;
    const int bh = std::max(2, mHeight / 160);
    const int bx = x + (w - bw) / 2;
    const int fill = (int)(bw * mProgress + 0.5f);
    GLfloat clear[4];
    glGetFloatv(GL_COLOR_CLEAR_VALUE, clear);
    glEnable(GL_SCISSOR_TEST);
    glScissor(bx, mHeight - (top + bh), bw, bh);
    // fading towards the black strip it sits on
    glClearColor(0.2f * fade, 0.2f * fade, 0.2f * fade, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    if (fill > 0) {
        glScissor(bx, mHeight - (top + bh), fill, bh);
        glClearColor(0.55f * fade, 0.55f * fade, 0.55f * fade, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
    }
    glDisable(GL_SCISSOR_TEST);
    glClearColor(clear[0], clear[1], clear[2], clear[3]);
}

// One grey line, centred. It's drawn once into a texture and kept until the text changes.
// The strip is opaque black like the background there, so it needs no blending.
void BootAnimation::drawNoticeLine(const char* text, int x, int top, int w, int fontSize) {
    if (mResizeTexture != 0 && mResizeShown != text) {
        glDeleteTextures(1, &mResizeTexture);
        mResizeTexture = 0;
    }
    if (mResizeTexture == 0) {
        mResizeShown = text;
        sk_sp<SkTypeface> face = SkTypeface::MakeFromFile(kResizeFontFile);
        if (face == nullptr) face = SkTypeface::MakeDefault();
        SkFont font(face, fontSize);
        font.setEdging(SkFont::Edging::kAntiAlias);
        SkFontMetrics metrics;
        font.getMetrics(&metrics);
        const int th = std::max(1, (int)ceilf((metrics.fDescent - metrics.fAscent) * 1.4f));

        SkBitmap bitmap;
        bitmap.allocPixels(SkImageInfo::Make(w, th, kRGBA_8888_SkColorType, kOpaque_SkAlphaType));
        SkCanvas canvas(bitmap);
        canvas.clear(SK_ColorBLACK);
        SkPaint paint;
        paint.setAntiAlias(true);
        paint.setColor(kResizeColor);
        const SkScalar tw = font.measureText(text, strlen(text), SkTextEncoding::kUTF8);
        canvas.drawString(text, (w - tw) / 2, (th - metrics.fDescent - metrics.fAscent) / 2,
                font, paint);

        // The same texture setup as the boot log's
        bool npot = false;
        const char* exts = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
        if (exts != nullptr) {
            npot = strstr(exts, "GL_ARB_texture_non_power_of_two") != nullptr
                    || strstr(exts, "GL_OES_texture_npot") != nullptr;
        }
        int pw = 1 << (31 - __builtin_clz(w));
        int ph = 1 << (31 - __builtin_clz(th));
        if (pw < w) pw <<= 1;
        if (ph < th) ph <<= 1;
        if (npot) {
            pw = w;
            ph = th;
        }
        glGenTextures(1, &mResizeTexture);
        glBindTexture(GL_TEXTURE_2D, mResizeTexture);
        glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, pw, ph, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, th, GL_RGBA, GL_UNSIGNED_BYTE,
                bitmap.getPixels());
        GLint crop[4] = { 0, th, w, -th };
        glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, crop);
        mResizeHeight = th;
        ALOGI("showing \"%s\"", text);
    } else {
        glBindTexture(GL_TEXTURE_2D, mResizeTexture);
    }
    glDrawTexiOES(x, mHeight - (top + mResizeHeight), 0, w, mResizeHeight);
    // the animation uploads parts that play once into texture 0
    glBindTexture(GL_TEXTURE_2D, 0);
}

}  // namespace android
