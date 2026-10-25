#include "engine.h"
#include <jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl31.h>
#include <thread>
#include <vector>
#include <mutex>
#include <array>
#include <stdexcept>
#include <algorithm>
#include <cstdint>
#include <deque>
#include <sstream>
#include <sys/resource.h>
#include <unistd.h>
#ifdef __aarch64__
#include <arm_neon.h>
#else
#include <cmath>
#endif
WorkloadConfig workload;
std::atomic<bool> running{false};
std::atomic<long long> counters[3]{};
std::atomic<int> states[3]{};
static std::mutex messagesMutex;
static std::string messages[3];
static std::chrono::steady_clock::time_point deadline;
static std::atomic<uint64_t> checksum{0};
bool active() { return running.load(std::memory_order_relaxed) && std::chrono::steady_clock::now() < deadline; }
void detail(int e,const std::string& m) { std::lock_guard<std::mutex> lock(messagesMutex); messages[e]=m; }
static void cpuWorker(unsigned index) {
    setpriority(PRIO_PROCESS,gettid(),8); // Leave UI and accelerator submission responsive.

#ifdef __aarch64__
    float32x4_t v[8];
    for(int k=0;k<8;k++) { float lanes[]={0.011f*(index+k+1),0.013f*(index+k+2),0.017f*(index+k+3),0.019f*(index+k+4)}; v[k]=vld1q_f32(lanes); }
    const auto a=vdupq_n_f32(0.99991f), b=vdupq_n_f32(0.000091f);
    while(active()) {
        for(int j=0;j<4096;j++) for(auto &x:v) x=vfmaq_f32(b,x,a);
        float32x4_t sum=vdupq_n_f32(0); for(auto x:v) sum=vaddq_f32(sum,x); float val=vaddvq_f32(sum); uint32_t bits; __builtin_memcpy(&bits,&val,4); checksum.fetch_xor(bits,std::memory_order_relaxed);
        counters[0].fetch_add(4096LL*8*4*2,std::memory_order_relaxed); states[0]=2;
    }
#else
    std::array<float,32> v{}; for(unsigned i=0;i<v.size();i++) v[i]=0.01f*(index+i+1);
    while(active()) { for(int j=0;j<4096;j++) for(auto &x:v) x=std::fma(x,0.99991f,0.000091f);
        float val=0; for(auto x:v) val+=x; uint32_t bits; __builtin_memcpy(&bits,&val,4); checksum.fetch_xor(bits,std::memory_order_relaxed);
        counters[0].fetch_add(4096LL*32*2,std::memory_order_relaxed); states[0]=2; }
#endif
}
static void gpuWorker() {
    EGLDisplay display=EGL_NO_DISPLAY; EGLContext context=EGL_NO_CONTEXT; EGLSurface surface=EGL_NO_SURFACE;
    GLuint shader=0,program=0;
    std::vector<GLuint> buffers(workload.queueDepth,0);
    struct Pending { GLsync fence; unsigned slot; };
    std::deque<Pending> pending;
    try {
        display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if(display==EGL_NO_DISPLAY || !eglInitialize(display,nullptr,nullptr)) throw std::runtime_error("EGL 初始化失敗");
        EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE};
        EGLConfig config; EGLint count=0;
        if(!eglChooseConfig(display,attrs,&config,1,&count)||count<1) throw std::runtime_error("無 ES 3.1 EGL config");
        EGLint ctx[]={EGL_CONTEXT_MAJOR_VERSION_KHR,3,EGL_CONTEXT_MINOR_VERSION_KHR,1,EGL_NONE};
        context=eglCreateContext(display,config,EGL_NO_CONTEXT,ctx);
        EGLint size[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE}; surface=eglCreatePbufferSurface(display,config,size);
        if(context==EGL_NO_CONTEXT||surface==EGL_NO_SURFACE||!eglMakeCurrent(display,surface,surface,context)) throw std::runtime_error("EGL context 失敗");
        std::ostringstream code;
        code << "#version 310 es\nprecision highp float;\nprecision highp int;\n"
             << "layout(local_size_x=128) in;\nlayout(std430,binding=0) buffer Output { vec4 result[]; };\n"
             << "uniform float seed;\nuniform int iterations;\nvoid main() { uint id=gl_GlobalInvocationID.x;\n";
        for(unsigned i=0;i<workload.gpuChains;i++) {
            code << "vec4 v" << i << "=vec4(float(id%1024u)*0.0001+seed+" << (0.02+i*0.01) << ")+vec4(0.01,0.03,0.07,0.11);\n";
            code << "vec4 b" << i << "=vec4(0.000091,0.000081,0.000071,0.000061)*(seed+" << (0.1+i*0.01) << ");\n";
        }
        code << "for(int k=0;k<iterations;k++) {\n";
        for(unsigned i=0;i<workload.gpuChains;i++) code << "v" << i << "=v" << i << "*vec4(0.99991,0.99992,0.99993,0.99994)+b" << i << ";\n";
        code << "}\nresult[id]=";
        for(unsigned i=0;i<workload.gpuChains;i++) code << (i?"+":"") << "v" << i;
        code << ";\n}\n";
        const std::string source=code.str(); const char* src=source.c_str();
        shader=glCreateShader(GL_COMPUTE_SHADER); glShaderSource(shader,1,&src,nullptr); glCompileShader(shader);
        GLint ok=0; glGetShaderiv(shader,GL_COMPILE_STATUS,&ok);
        if(!ok) { char log[1024]{}; glGetShaderInfoLog(shader,1024,nullptr,log); throw std::runtime_error(log); }
        program=glCreateProgram(); glAttachShader(program,shader); glLinkProgram(program); glGetProgramiv(program,GL_LINK_STATUS,&ok);
        if(!ok) { char log[2048]{}; glGetProgramInfoLog(program,sizeof(log),nullptr,log); throw std::runtime_error(std::string("Compute program link: ")+log); }
        glGenBuffers(buffers.size(),buffers.data());
        for(auto buffer:buffers) { glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffer); glBufferData(GL_SHADER_STORAGE_BUFFER,GPU_LANES*4*sizeof(float),nullptr,GL_DYNAMIC_COPY); }
        glUseProgram(program);
        const auto renderer=glGetString(GL_RENDERER);
        detail(1,std::string(renderer?reinterpret_cast<const char*>(renderer):"OpenGL ES compute")+" · "+std::to_string(workload.gpuChains)+" chains · queue "+std::to_string(workload.queueDepth));
        GLint seed=glGetUniformLocation(program,"seed"); glUniform1i(glGetUniformLocation(program,"iterations"),workload.gpuIterations);
        unsigned nextSlot=0; uint64_t submitted=0;
        while(active() || !pending.empty()) {
            while(active() && pending.size()<workload.queueDepth) {
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,buffers[nextSlot]);
                glUniform1f(seed,0.01f+float(submitted++%100)/1000.f);
                glDispatchCompute(GPU_LANES/128,1,1);
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
                auto fence=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);
                if(!fence || glGetError()!=GL_NO_ERROR) throw std::runtime_error("GPU dispatch / fence 失敗");
                pending.push_back({fence,nextSlot}); nextSlot=(nextSlot+1)%buffers.size();
            }
            if(pending.empty()) break;
            GLenum result=glClientWaitSync(pending.front().fence,GL_SYNC_FLUSH_COMMANDS_BIT,5000000);
            if(result==GL_WAIT_FAILED) throw std::runtime_error("GPU fence wait 失敗");
            if(result==GL_ALREADY_SIGNALED || result==GL_CONDITION_SATISFIED) {
                glDeleteSync(pending.front().fence); pending.pop_front(); counters[1]++; states[1]=2;
            }
        }
    } catch(const std::exception& e) { detail(1,e.what()); states[1]=3; }
    if(context!=EGL_NO_CONTEXT) { for(auto &item:pending) glDeleteSync(item.fence); glDeleteBuffers(buffers.size(),buffers.data()); if(program) glDeleteProgram(program); if(shader) glDeleteShader(shader); }
    if(display!=EGL_NO_DISPLAY) { eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT); if(surface!=EGL_NO_SURFACE) eglDestroySurface(display,surface); if(context!=EGL_NO_CONTEXT) eglDestroyContext(display,context); eglTerminate(display); }
    if(states[1]!=3) states[1]=4;
}
extern "C" JNIEXPORT void JNICALL Java_dev_maxheater_NativeEngine_run(JNIEnv* env,jclass,jboolean cpu,jboolean gpu,jboolean npu,jstring dir,jint seconds) {
    const char* str=env->GetStringUTFChars(dir,nullptr); std::string path(str); env->ReleaseStringUTFChars(dir,str);
    deadline=std::chrono::steady_clock::now()+std::chrono::seconds(std::clamp(int(seconds),1,300));
    // Java serializes sessions; cancellation is armed before the executor is submitted.
    std::vector<std::thread> workers;
    try {
        if(gpu) { states[1]=1; workers.emplace_back(gpuWorker); }
        if(npu) { states[2]=1; workers.emplace_back(runQnn,path); }
        if(cpu) { states[0]=1; unsigned n=std::clamp(std::thread::hardware_concurrency(),1u,32u); detail(0,std::to_string(n)+" threads · FMA"); for(unsigned i=0;i<n;i++) { if(!active()) break; workers.emplace_back(cpuWorker,i); } }
    } catch(const std::exception& e) { detail(0,e.what()); states[0]=3; running=false; }
    for(auto &w:workers) if(w.joinable()) w.join();
    running=false; for(auto &s:states) if(s==1||s==2) s=4;
}
extern "C" JNIEXPORT void JNICALL Java_dev_maxheater_NativeEngine_arm(JNIEnv*,jclass) { for(int i=0;i<3;i++) { counters[i]=0; states[i]=0; detail(i,""); } running=true; }
extern "C" JNIEXPORT void JNICALL Java_dev_maxheater_NativeEngine_stop(JNIEnv*,jclass) { running=false; }
extern "C" JNIEXPORT jlongArray JNICALL Java_dev_maxheater_NativeEngine_snapshot(JNIEnv* env,jclass) {
    jlong a[6]; for(int i=0;i<3;i++) { a[i]=counters[i].load(); a[i+3]=states[i].load(); }
    auto out=env->NewLongArray(6); env->SetLongArrayRegion(out,0,6,a); return out;
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_maxheater_NativeEngine_detail(JNIEnv* env,jclass,jint e) { std::lock_guard<std::mutex> lock(messagesMutex); return env->NewStringUTF(e>=0&&e<3?messages[e].c_str():""); }

extern "C" JNIEXPORT jlongArray JNICALL Java_dev_maxheater_NativeEngine_operationCounts(JNIEnv* env,jclass) {
    jlong counts[]={1,static_cast<jlong>(gpuOps()),static_cast<jlong>(npuOps())};
    auto result=env->NewLongArray(3); env->SetLongArrayRegion(result,0,3,counts); return result;
}

extern "C" JNIEXPORT jboolean JNICALL Java_dev_maxheater_NativeEngine_configureNative(JNIEnv*,jclass,jint matrix,jint layers,jint iterations,jint chains,jint depth) {
    if(running || matrix<512 || matrix>3072 || matrix%128 || layers<1 || layers>32 || iterations<64 || iterations>1024 || chains<4 || chains>32 || depth<1 || depth>4) return false;
    workload={uint32_t(matrix),uint32_t(layers),uint32_t(iterations),uint32_t(chains),uint32_t(depth)};
    return true;
}
