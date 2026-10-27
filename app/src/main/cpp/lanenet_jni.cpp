// 高德 AR 车道线模型推理桥（CPdazi 版）
//
// 运行时来源：高德车机版 7.1.5 的 libVAE.so —— 它静态链接了 MNN，并导出 MNN 符号
// (GLOBAL DEFAULT)。官方 MNN 3.6.1 跑不了这批 int8 量化模型
// （报 EltwiseInt8 不支持 / Create execution error 518），只有高德这份能加载。
//
// 为什么 dlopen 而不是直接链接：libVAE.so 的符号表里有畸形条目
// （_end / __bss_start 等 local 符号混进了 global 段），lld 会直接拒绝链接。
// 所以改成运行时 dlopen + dlsym，把用到的 MNN 方法逐个转发过去。
//
// 全程不访问 MNN 类内部字段（Tensor 是非 pImpl 类，跨版本布局可能不同）：
//   输入：Tensor::create(shape, halide_type_t, userData, dimType) 包住我们自己的缓冲
//        -> inputDevice->copyFromHostTensor(host)
//   推理：net->runSession(session)
//   输出：outDevice->copyToHostTensor(host) 写回我们自己的缓冲
//
// 与逆向原版的差异：
//   1. 只保留车道线需要的路径，包名改为 com.jixiexiaoge.drivingassist.vision
//   2. 新增 nativeInferCompact —— 大数组（roadOut / chedaoshu）由 Kotlin 侧复用传入，
//      避免每帧经 JNI 新建几万个 float 的数组（原版一次 150K float ≈ 600KB/帧）
//   3. 新增 nativeSetRoi —— 中心裁剪标定用（视野域不匹配时的 P3 手段）

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>
#include <map>
#include <sys/time.h>

#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>

#define LOG_TAG "LaneNet"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// 真实符号表（从 libVAE.so 的 .dynsym 抄出来的 mangled name）
// ---------------------------------------------------------------------------
namespace {

#define SYM_Interpreter_createFromFile "_ZN3MNN11Interpreter14createFromFileEPKc"
#define SYM_Interpreter_createSession  "_ZN3MNN11Interpreter13createSessionERKNS_14ScheduleConfigE"
#define SYM_Interpreter_inputAll       "_ZNK3MNN11Interpreter18getSessionInputAllEPKNS_7SessionE"
#define SYM_Interpreter_outputAll      "_ZNK3MNN11Interpreter19getSessionOutputAllEPKNS_7SessionE"
#define SYM_Interpreter_runSession     "_ZNK3MNN11Interpreter10runSessionEPNS_7SessionE"
#define SYM_Interpreter_releaseSession "_ZN3MNN11Interpreter14releaseSessionEPNS_7SessionE"
#define SYM_Interpreter_dtor           "_ZN3MNN11InterpreterD1Ev"
#define SYM_Tensor_create \
    "_ZN3MNN6Tensor6createERKNSt6__ndk16vectorIiNS1_9allocatorIiEEEE13halide_type_tPvNS0_13DimensionTypeE"
#define SYM_Tensor_create_alt \
    "_ZN3MNN6Tensor6createERKNSt3__16vectorIiNS1_9allocatorIiEEEE13halide_type_tPvNS0_13DimensionTypeE"
#define SYM_Tensor_shape   "_ZNK3MNN6Tensor5shapeEv"
#define SYM_Tensor_dimType "_ZNK3MNN6Tensor16getDimensionTypeEv"
#define SYM_Tensor_copyFrom "_ZN3MNN6Tensor18copyFromHostTensorEPKS0_"
#define SYM_Tensor_copyTo   "_ZNK3MNN6Tensor16copyToHostTensorEPS0_"
#define SYM_Tensor_dtor     "_ZN3MNN6TensorD1Ev"

typedef MNN::Interpreter* (*Fn_createFromFile)(const char*);
typedef MNN::Session* (*Fn_createSession)(MNN::Interpreter*, const MNN::ScheduleConfig&);
typedef const std::map<std::string, MNN::Tensor*>& (*Fn_tensorMap)(const MNN::Interpreter*,
                                                                  const MNN::Session*);
typedef MNN::ErrorCode (*Fn_runSession)(const MNN::Interpreter*, MNN::Session*);
typedef bool (*Fn_releaseSession)(MNN::Interpreter*, MNN::Session*);
typedef void (*Fn_dtor)(void*);
typedef MNN::Tensor* (*Fn_tCreate)(const std::vector<int>&, halide_type_t, void*,
                                   MNN::Tensor::DimensionType);
typedef std::vector<int> (*Fn_tShape)(const MNN::Tensor*);
typedef MNN::Tensor::DimensionType (*Fn_tDimType)(const MNN::Tensor*);
typedef bool (*Fn_tCopyFrom)(MNN::Tensor*, const MNN::Tensor*);
typedef bool (*Fn_tCopyTo)(const MNN::Tensor*, MNN::Tensor*);

struct FnTable {
    Fn_createFromFile createFromFile = nullptr;
    Fn_createSession createSession = nullptr;
    Fn_tensorMap inputAll = nullptr;
    Fn_tensorMap outputAll = nullptr;
    Fn_runSession runSession = nullptr;
    Fn_releaseSession releaseSession = nullptr;
    Fn_dtor dInterpreter = nullptr;
    Fn_tCreate tCreate = nullptr;
    Fn_tShape tShape = nullptr;
    Fn_tDimType tDimType = nullptr;
    Fn_tCopyFrom tCopyFrom = nullptr;
    Fn_tCopyTo tCopyTo = nullptr;
    Fn_dtor dTensor = nullptr;
};

FnTable g;
bool gReady = false;
std::string gLoadError;

void* openLibVae() {
    // 先在 app 自己的 native library 目录按 basename 找
    void* h = dlopen("libVAE.so", RTLD_NOW | RTLD_GLOBAL);
    if (h) return h;

    const char* e1 = dlerror();
    LOGE("dlopen(libVAE.so) failed: %s", e1 ? e1 : "?");

    // 退而求其次：用自身路径拼绝对路径
    Dl_info info;
    if (dladdr((void*) &openLibVae, &info) && info.dli_fname) {
        std::string self(info.dli_fname);
        size_t slash = self.rfind('/');
        if (slash != std::string::npos) {
            std::string path = self.substr(0, slash) + "/libVAE.so";
            h = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
            if (h) {
                LOGI("dlopen(%s) ok", path.c_str());
                return h;
            }
            const char* e2 = dlerror();
            LOGE("dlopen(%s) failed: %s", path.c_str(), e2 ? e2 : "?");
            gLoadError = e2 ? e2 : "unknown";
            return nullptr;
        }
    }
    gLoadError = e1 ? e1 : "unknown";
    return nullptr;
}

template <typename T>
bool bind(T& slot, void* h, const char* name) {
    void* p = dlsym(h, name);
    if (!p) {
        LOGE("dlsym missing: %s", name);
        return false;
    }
    slot = (T) p;
    return true;
}

/** 同一方法的多个候选 mangled name 依次尝试（NDK libc++ inline namespace 兼容） */
template <typename T>
bool bindAny(T& slot, void* h, const char* a, const char* b) {
    if (bind(slot, h, a)) return true;
    return bind(slot, h, b);
}

bool loadRuntime() {
    if (gReady) return true;
    if (!gLoadError.empty()) return false;

    void* h = openLibVae();
    if (!h) return false;

    bool ok = true;
    ok &= bind(g.createFromFile, h, SYM_Interpreter_createFromFile);
    ok &= bind(g.createSession, h, SYM_Interpreter_createSession);
    ok &= bind(g.inputAll, h, SYM_Interpreter_inputAll);
    ok &= bind(g.outputAll, h, SYM_Interpreter_outputAll);
    ok &= bind(g.runSession, h, SYM_Interpreter_runSession);
    ok &= bind(g.releaseSession, h, SYM_Interpreter_releaseSession);
    ok &= bind(g.dInterpreter, h, SYM_Interpreter_dtor);
    ok &= bindAny(g.tCreate, h, SYM_Tensor_create, SYM_Tensor_create_alt);
    ok &= bind(g.tShape, h, SYM_Tensor_shape);
    ok &= bind(g.tDimType, h, SYM_Tensor_dimType);
    ok &= bind(g.tCopyFrom, h, SYM_Tensor_copyFrom);
    ok &= bind(g.tCopyTo, h, SYM_Tensor_copyTo);
    ok &= bind(g.dTensor, h, SYM_Tensor_dtor);

    if (!ok) {
        gLoadError = "dlsym failed";
        return false;
    }
    gReady = true;
    LOGI("MNN runtime bound from libVAE.so");
    return true;
}

} // namespace

// ---------------------------------------------------------------------------
// 把 MNN 用到的方法转发到真实地址
// （头文件提供准确声明，这里提供实现，链接期就不再依赖 libVAE.so）
// ---------------------------------------------------------------------------
namespace MNN {

Interpreter* Interpreter::createFromFile(const char* file) {
    return g.createFromFile ? g.createFromFile(file) : nullptr;
}

Session* Interpreter::createSession(const ScheduleConfig& config) {
    return g.createSession ? g.createSession(this, config) : nullptr;
}

const std::map<std::string, Tensor*>& Interpreter::getSessionInputAll(const Session* s) const {
    static std::map<std::string, Tensor*> empty;
    return g.inputAll ? g.inputAll(this, s) : empty;
}

const std::map<std::string, Tensor*>& Interpreter::getSessionOutputAll(const Session* s) const {
    static std::map<std::string, Tensor*> empty;
    return g.outputAll ? g.outputAll(this, s) : empty;
}

ErrorCode Interpreter::runSession(Session* session) const {
    return g.runSession ? g.runSession(this, session) : OUT_OF_MEMORY;
}

bool Interpreter::releaseSession(Session* session) {
    return g.releaseSession ? g.releaseSession(this, session) : false;
}

Interpreter::~Interpreter() {
    if (g.dInterpreter) g.dInterpreter(this);
}

Tensor* Tensor::create(const std::vector<int>& shape, halide_type_t type, void* data,
                       DimensionType dimType) {
    return g.tCreate ? g.tCreate(shape, type, data, dimType) : nullptr;
}

std::vector<int> Tensor::shape() const {
    return g.tShape ? g.tShape(this) : std::vector<int>();
}

Tensor::DimensionType Tensor::getDimensionType() const {
    return g.tDimType ? g.tDimType(this) : TENSORFLOW;
}

bool Tensor::copyFromHostTensor(const Tensor* hostTensor) {
    return g.tCopyFrom ? g.tCopyFrom(this, hostTensor) : false;
}

bool Tensor::copyToHostTensor(Tensor* hostTensor) const {
    return g.tCopyTo ? g.tCopyTo(this, hostTensor) : false;
}

Tensor::~Tensor() {
    if (g.dTensor) g.dTensor(this);
}

} // namespace MNN

// ---------------------------------------------------------------------------
// 业务层
// ---------------------------------------------------------------------------
using namespace MNN;

namespace {

struct OutSlot {
    std::string name;
    std::vector<int> shape;
    Tensor::DimensionType dimType = Tensor::TENSORFLOW;
    bool nhwc = true;
    int H = 0, W = 0, C = 0;
    /** true = 每帧都要回填数据；false 只在首帧探针时拷一次 */
    bool needed = false;
    std::vector<float> host;
};

struct Net {
    Interpreter* net = nullptr;
    Session* session = nullptr;
    Tensor* input = nullptr;
    std::string inputName;
    int inW = 0;
    int inH = 0;
    int inC = 1;                 // 这批模型里 1 = 灰度
    Tensor::DimensionType inDim = Tensor::TENSORFLOW;
    std::vector<OutSlot> outs;
    std::vector<float> inBuf;
    bool probed = false;
    long frames = 0;
    long totalMs = 0;
};

long nowMs() {
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    return (long) tv.tv_sec * 1000L + tv.tv_usec / 1000L;
}

/**
 * 输入 ROI（中心裁剪标定用）。
 * 视野域不匹配时，左右各裁掉一部分再喂模型；roiL/roiR 是「裁掉宽度占整帧的比例」。
 * 叠加坐标映射需要按同样比例反算，Kotlin 侧 LaneResult.roiL/roiR 保存同一组值。
 */
static float gRoiL = 0.0f;
static float gRoiR = 0.0f;

std::string shapeStr(const std::vector<int>& s) {
    std::string r = "[";
    for (size_t i = 0; i < s.size(); ++i) {
        if (i) r += ",";
        r += std::to_string(s[i]);
    }
    r += "]";
    return r;
}

int elemCount(const std::vector<int>& s) {
    int n = 1;
    for (int v : s) n *= v;
    return n;
}

halide_type_t f32() {
    halide_type_t t;
    t.code = (halide_type_code_t) 2;
    t.bits = 32;
    t.lanes = 1;
    return t;
}

void parseHWC(OutSlot& s) {
    if (s.shape.size() != 4) {
        s.nhwc = true;
        s.H = s.shape.size() > 1 ? s.shape[1] : 1;
        s.W = s.shape.size() > 2 ? s.shape[2] : 1;
        s.C = s.shape.size() > 3 ? s.shape[3] : 1;
        return;
    }
    if (s.dimType == Tensor::CAFFE || s.dimType == Tensor::CAFFE_C4) {
        s.nhwc = false;
        s.C = s.shape[1];
        s.H = s.shape[2];
        s.W = s.shape[3];
    } else {
        s.nhwc = true;
        s.H = s.shape[1];
        s.W = s.shape[2];
        s.C = s.shape[3];
    }
}

void inspect(Net* n) {
    const auto& ins = n->net->getSessionInputAll(n->session);
    LOGI("input tensors: %zu", ins.size());
    for (const auto& kv : ins) {
        Tensor* t = kv.second;
        auto shape = t->shape();
        LOGI("  input[%s] shape=%s dimType=%d", kv.first.c_str(), shapeStr(shape).c_str(),
             (int) t->getDimensionType());
        if (!n->input) {
            n->input = t;
            n->inputName = kv.first;
            n->inDim = t->getDimensionType();
            if (shape.size() == 4) {
                if (n->inDim == Tensor::CAFFE || n->inDim == Tensor::CAFFE_C4) {
                    n->inC = shape[1];
                    n->inH = shape[2];
                    n->inW = shape[3];
                } else {
                    n->inH = shape[1];
                    n->inW = shape[2];
                    n->inC = shape[3];
                }
            }
            if (n->inC <= 0) n->inC = 1;
        }
    }

    const auto& os = n->net->getSessionOutputAll(n->session);
    LOGI("output tensors: %zu", os.size());
    for (const auto& kv : os) {
        OutSlot s;
        s.name = kv.first;
        s.shape = kv.second->shape();
        s.dimType = kv.second->getDimensionType();
        parseHWC(s);
        s.host.assign((size_t) elemCount(s.shape), 0.f);
        LOGI("  out[%s] shape=%s dimType=%d (H=%d W=%d C=%d %s)", s.name.c_str(),
             shapeStr(s.shape).c_str(), (int) s.dimType, s.H, s.W, s.C,
             s.nhwc ? "NHWC" : "NCHW");
        n->outs.push_back(std::move(s));
    }
}

/** 只有这几路是业务需要的：可行驶区探针 + 左右车道数 */
void markNeeded(Net* n) {
    static const char* kKeep[] = { "roadOut", "chedaoshuLeftOut", "chedaoshuRightOut" };
    for (auto& s : n->outs) {
        for (const char* k : kKeep) {
            if (s.name.find(k) != std::string::npos) {
                s.needed = true;
                break;
            }
        }
    }
}

int findOut(Net* n, const char* name) {
    for (size_t i = 0; i < n->outs.size(); ++i) {
        if (n->outs[i].name == name) return (int) i;
    }
    for (size_t i = 0; i < n->outs.size(); ++i) {
        if (n->outs[i].name.find(name) != std::string::npos) return (int) i;
    }
    return -1;
}

inline float at(const OutSlot& s, int h, int w, int c) {
    if (s.nhwc) return s.host[((size_t) h * s.W + w) * s.C + c];
    return s.host[((size_t) c * s.H + h) * s.W + w];
}

/** 首帧打一次各通道 min/max/mean，用于确认模型确实在工作（不是全 0） */
void probeStats(Net* n) {
    int ri = findOut(n, "roadOut");
    if (ri < 0) return;
    OutSlot& s = n->outs[ri];
    int maxC = s.C > 4 ? 4 : s.C;
    for (int c = 0; c < maxC; ++c) {
        float mn = 1e9f, mx = -1e9f, sum = 0.f;
        for (int i = 0; i < s.H; ++i) {
            for (int j = 0; j < s.W; ++j) {
                float v = at(s, i, j, c);
                if (v < mn) mn = v;
                if (v > mx) mx = v;
                sum += v;
            }
        }
        LOGI("  probe roadOut.ch%d min=%.4f max=%.4f mean=%.4f", c, mn, mx,
             sum / (float) (s.H * s.W));
    }
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM*, void*) {
    loadRuntime();
    return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL
Java_com_jixiexiaoge_drivingassist_vision_LaneNative_nativeLoadStatus(JNIEnv* env, jclass) {
    if (!gReady) loadRuntime();
    if (gReady) return env->NewStringUTF("ok");
    return env->NewStringUTF(gLoadError.empty() ? "libVAE load failed" : gLoadError.c_str());
}

JNIEXPORT void JNICALL
Java_com_jixiexiaoge_drivingassist_vision_LaneNative_nativeSetRoi(JNIEnv*, jclass, jfloat l,
                                                              jfloat r) {
    gRoiL = l < 0.f ? 0.f : (l > 0.4f ? 0.4f : l);
    gRoiR = r < 0.f ? 0.f : (r > 0.4f ? 0.4f : r);
    LOGI("roi set: left=%.3f right=%.3f", gRoiL, gRoiR);
}

JNIEXPORT jlong JNICALL
Java_com_jixiexiaoge_drivingassist_vision_LaneNative_nativeInit(JNIEnv* env, jclass,
                                                             jstring jpath) {
    if (!loadRuntime()) {
        LOGE("runtime not ready: %s", gLoadError.c_str());
        return 0;
    }
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    LOGI("=== init from %s", path);

    Net* n = new Net();
    n->net = Interpreter::createFromFile(path);
    env->ReleaseStringUTFChars(jpath, path);

    if (!n->net) {
        LOGE("createFromFile FAILED");
        delete n;
        return 0;
    }

    ScheduleConfig cfg;
    cfg.type = MNN_FORWARD_CPU;
    cfg.numThread = 4;
    n->session = n->net->createSession(cfg);
    if (!n->session) {
        LOGE("createSession FAILED");
        delete n->net;
        delete n;
        return 0;
    }

    inspect(n);
    markNeeded(n);
    if (!n->input || n->inW <= 0 || n->inH <= 0) {
        LOGE("no usable input tensor");
        n->net->releaseSession(n->session);
        delete n->net;
        delete n;
        return 0;
    }
    n->inBuf.assign((size_t) n->inW * n->inH * n->inC, 0.f);
    LOGI("=== ready: input %dx%d c=%d (%s) outs=%zu", n->inW, n->inH, n->inC,
         n->inputName.c_str(), n->outs.size());
    return (jlong) n;
}

JNIEXPORT jintArray JNICALL
Java_com_jixiexiaoge_drivingassist_vision_LaneNative_nativeInputShape(JNIEnv* env, jclass,
                                                                   jlong h) {
    jintArray r = env->NewIntArray(3);
    jint v[3] = { 0, 0, 0 };
    Net* n = (Net*) h;
    if (n) {
        v[0] = n->inW;
        v[1] = n->inH;
        v[2] = (jint) n->outs.size();
    }
    env->SetIntArrayRegion(r, 0, 3, v);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_jixiexiaoge_drivingassist_vision_LaneNative_nativeInputChannels(JNIEnv*, jclass,
                                                                     jlong h) {
    Net* n = (Net*) h;
    return n ? n->inC : 0;
}

JNIEXPORT jintArray JNICALL
Java_com_jixiexiaoge_drivingassist_vision_LaneNative_nativeRoadShape(JNIEnv* env, jclass,
                                                                  jlong h) {
    // {H, W, C, nhwc}——Kotlin 侧按第 4 位决定如何索引扁平数组
    jintArray r = env->NewIntArray(4);
    jint v[4] = { 0, 0, 0, 1 };
    Net* n = (Net*) h;
    if (n) {
        int ri = findOut(n, "roadOut");
        if (ri >= 0) {
            v[0] = n->outs[ri].H;
            v[1] = n->outs[ri].W;
            v[2] = n->outs[ri].C;
            v[3] = n->outs[ri].nhwc ? 1 : 0;
        }
    }
    env->SetIntArrayRegion(r, 0, 4, v);
    return r;
}

JNIEXPORT jobjectArray JNICALL
Java_com_jixiexiaoge_drivingassist_vision_LaneNative_nativeOutNames(JNIEnv* env, jclass,
                                                                 jlong h) {
    Net* n = (Net*) h;
    int cnt = n ? (int) n->outs.size() : 0;
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray r = env->NewObjectArray(cnt, strCls, nullptr);
    for (int i = 0; i < cnt; ++i) {
        env->SetObjectArrayElement(r, i, env->NewStringUTF(n->outs[i].name.c_str()));
    }
    return r;
}

JNIEXPORT jintArray JNICALL
Java_com_jixiexiaoge_drivingassist_vision_LaneNative_nativeOutSizes(JNIEnv* env, jclass,
                                                                 jlong h) {
    Net* n = (Net*) h;
    int cnt = n ? (int) n->outs.size() : 0;
    jintArray r = env->NewIntArray(cnt);
    if (cnt > 0) {
        std::vector<jint> v(cnt);
        for (int i = 0; i < cnt; ++i) v[i] = (jint) n->outs[i].host.size();
        env->SetIntArrayRegion(r, 0, cnt, v.data());
    }
    return r;
}

JNIEXPORT jintArray JNICALL
Java_com_jixiexiaoge_drivingassist_vision_LaneNative_nativeOutShapeAt(JNIEnv* env, jclass,
                                                                   jlong h, jint idx) {
    jintArray r = env->NewIntArray(3);
    jint v[3] = { 0, 0, 0 };
    Net* n = (Net*) h;
    if (n && idx >= 0 && idx < (jint) n->outs.size()) {
        v[0] = n->outs[idx].H;
        v[1] = n->outs[idx].W;
        v[2] = n->outs[idx].C;
    }
    env->SetIntArrayRegion(r, 0, 3, v);
    return r;
}

JNIEXPORT jstring JNICALL
Java_com_jixiexiaoge_drivingassist_vision_LaneNative_nativeDescribe(JNIEnv* env, jclass,
                                                                 jlong h) {
    Net* n = (Net*) h;
    if (!n) return env->NewStringUTF("no net");
    std::string r = "input " + n->inputName + " " + std::to_string(n->inW) + "x" +
                    std::to_string(n->inH) + " c=" + std::to_string(n->inC) + "\n";
    for (auto& s : n->outs) {
        r += "out " + s.name + " " + shapeStr(s.shape) + " " + (s.nhwc ? "NHWC" : "NCHW") +
             "\n";
    }
    return env->NewStringUTF(r.c_str());
}

// ---------------------------------------------------------------------------
// 推理核心
//
// src 是灰度平面（I420 的 Y），行距 stride。
// 内部按模型真实输入布局（NCHW / NHWC）做最近邻缩放 + /255 归一化。
// ---------------------------------------------------------------------------
static int execAndFetch(Net* n) {
    const bool nchw = (n->inDim == Tensor::CAFFE || n->inDim == Tensor::CAFFE_C4);
    std::vector<int> hostShape;
    if (nchw) {
        hostShape = { 1, n->inC, n->inH, n->inW };
    } else {
        hostShape = { 1, n->inH, n->inW, n->inC };
    }

    Tensor* host = Tensor::create(hostShape, f32(), n->inBuf.data(), n->inDim);
    if (!host) {
        LOGE("Tensor::create(host) failed");
        return -2;
    }
    n->input->copyFromHostTensor(host);
    delete host;

    long t0 = nowMs();
    ErrorCode err = n->net->runSession(n->session);
    long t1 = nowMs();
    n->frames++;
    n->totalMs += (t1 - t0);

    if (err != NO_ERROR) {
        LOGE("runSession error %d", (int) err);
        return -3;
    }

    // 首帧全拷（要打探针统计），之后只拷真正用到的几路，省掉每帧几百 KB 的 memcpy
    const bool probeAll = !n->probed;
    const auto& os = n->net->getSessionOutputAll(n->session);
    for (auto& slot : n->outs) {
        if (!probeAll && !slot.needed) continue;
        auto it = os.find(slot.name);
        if (it == os.end()) continue;
        Tensor* h2 = Tensor::create(slot.shape, f32(), slot.host.data(), slot.dimType);
        if (!h2) continue;
        it->second->copyToHostTensor(h2);
        delete h2;
    }

    if (!n->probed) {
        n->probed = true;
        probeStats(n);
    }
    return 0;
}

static int runInferGray(Net* n, const unsigned char* src, int w, int h, int stride) {
    if (!n || !src || w <= 0 || h <= 0) return -1;
    const int inW = n->inW, inH = n->inH, inC = n->inC;
    const bool nchw = (n->inDim == Tensor::CAFFE || n->inDim == Tensor::CAFFE_C4);
    float* dst = n->inBuf.data();
    const size_t plane = (size_t) inW * inH;

    // ROI：只取 [x0, x1) 这一段横向区域
    int x0 = (int) (gRoiL * (float) w);
    int x1 = w - (int) (gRoiR * (float) w);
    int rw = x1 - x0;
    if (rw <= 0) {
        x0 = 0;
        rw = w;
    }

    for (int dy = 0; dy < inH; ++dy) {
        int sy = (int) ((long) dy * h / inH);
        if (sy >= h) sy = h - 1;
        const unsigned char* row = src + (long) sy * stride + x0;
        for (int dx = 0; dx < inW; ++dx) {
            int sx = (int) ((long) dx * rw / inW);
            if (sx >= rw) sx = rw - 1;
            float v = row[sx] * (1.0f / 255.0f);

            size_t base = (size_t) dy * inW + dx;
            if (nchw) {
                if (inC == 1) {
                    dst[base] = v;
                } else {
                    dst[base] = v;
                    dst[plane + base] = v;
                    dst[2 * plane + base] = v;
                }
            } else {
                float* p = dst + base * inC;
                if (inC == 1) {
                    p[0] = v;
                } else {
                    p[0] = v;
                    p[1] = v;
                    p[2] = v;
                }
            }
        }
    }

    return execAndFetch(n);
}

/**
 * 车道线专用推理：只回填两路小输出。
 *
 * roadDst  ：roadOut 的 H*W*C 个 float（调用方复用同一个数组，避免每帧分配）
 * countDst ：8 个 float = chedaoshuLeftOut 的 4 维 + chedaoshuRightOut 的 4 维
 *
 * 返回 0 成功；-1 参数错；-2/-3 见 execAndFetch；-4 roadOut 缺失。
 */
JNIEXPORT jint JNICALL
Java_com_jixiexiaoge_drivingassist_vision_LaneNative_nativeInferCompact(
        JNIEnv* env, jclass, jlong h, jbyteArray jy, jint w, jint hgt, jint stride,
        jfloatArray roadDst, jfloatArray countDst) {
    Net* n = (Net*) h;
    if (!n || !jy) return -1;

    jint roadLen = roadDst ? env->GetArrayLength(roadDst) : 0;
    jint cntLen = countDst ? env->GetArrayLength(countDst) : 0;

    jbyte* y = env->GetByteArrayElements(jy, nullptr);
    if (!y) return -1;
    int rc = runInferGray(n, (const unsigned char*) y, w, hgt, stride);
    env->ReleaseByteArrayElements(jy, y, JNI_ABORT);
    if (rc != 0) return rc;

    int ri = findOut(n, "roadOut");
    if (ri < 0) return -4;
    OutSlot& s = n->outs[ri];
    if (roadDst && roadLen >= (jint) s.host.size()) {
        env->SetFloatArrayRegion(roadDst, 0, (jsize) s.host.size(), s.host.data());
    }

    if (countDst && cntLen >= 8) {
        float c[8] = { 0, 0, 0, 0, 0, 0, 0, 0 };
        int li = findOut(n, "chedaoshuLeftOut");
        if (li >= 0) {
            const auto& v = n->outs[li].host;
            for (size_t i = 0; i < v.size() && i < 4; ++i) c[i] = v[i];
        }
        int rr = findOut(n, "chedaoshuRightOut");
        if (rr >= 0) {
            const auto& v = n->outs[rr].host;
            for (size_t i = 0; i < v.size() && i < 4; ++i) c[4 + i] = v[i];
        }
        env->SetFloatArrayRegion(countDst, 0, 8, c);
    }

    if (n->frames % 100 == 0) {
        LOGI("frames=%ld runSession avg=%.1fms", n->frames, (double) n->totalMs / n->frames);
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_com_jixiexiaoge_drivingassist_vision_LaneNative_nativeRelease(JNIEnv*, jclass, jlong h) {
    Net* n = (Net*) h;
    if (!n) return;
    if (n->net) {
        if (n->session) n->net->releaseSession(n->session);
        delete n->net;
    }
    delete n;
}

} // extern "C"
