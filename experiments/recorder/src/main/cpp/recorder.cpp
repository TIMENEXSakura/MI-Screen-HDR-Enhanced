#include "graphics.h"
#include "frame_gate.h"
#include <android/native_window_jni.h>
#include <media/NdkImageReader.h>
#include <jni.h>
#include <poll.h>
#include <unistd.h>
#include <cerrno>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <chrono>
#include <sstream>
#include <iomanip>

namespace {
using Clock=std::chrono::steady_clock;
std::string jsonString(const std::string& value) {
    std::ostringstream out; out<<'"';
    for(unsigned char c:value) {
        if(c=='"'||c=='\\') out<<'\\'<<c;
        else if(c<32) out<<"\\u"<<std::hex<<std::setw(4)<<std::setfill('0')<<static_cast<int>(c)<<std::dec;
        else out<<c;
    }
    return out.str()+'"';
}
class Engine {
    AImageReader* reader=nullptr;
    ANativeWindow* encoder=nullptr;
    ANativeWindow* input=nullptr;
    std::thread thread;
    std::mutex mutex;
    std::condition_variable wake;
    bool quitting=false, available=false, initialized=false, active=true;
    int64_t baseTimestamp=0, firstTimestamp=0;
    std::string error, driver;
    uint64_t acquired=0, rendered=0, rateLimited=0, badTimestamps=0;
    double totalMs=0, maxMs=0;
    int width=0,height=0,fps=0;
    bool vulkan=false;
    static void onImage(void* context,AImageReader*) {
        auto* self=static_cast<Engine*>(context);
        std::lock_guard<std::mutex> lock(self->mutex);
        self->available=true; self->wake.notify_all();
    }
    void run() {
        auto renderer=vulkan?makeVulkan():makeGles();
        try {
            renderer->init(encoder,width,height);
            { std::lock_guard<std::mutex> lock(mutex); driver=renderer->description; initialized=true; wake.notify_all(); }
            FrameGate gate(fps);
            while(true) {
                { std::unique_lock<std::mutex> lock(mutex); wake.wait(lock,[&]{return quitting||available;}); if(quitting) break; available=false; }
                AImage* raw=nullptr; int fence=-1;
                media_status_t result=AImageReader_acquireLatestImageAsync(reader,&raw,&fence);
                if(result==AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE) continue;
                require(result==AMEDIA_OK && raw,"Cannot acquire capture image: "+std::to_string(result));
                auto image=imagePtr(raw);
                if(fence>=0) {
                    pollfd fd{fence,POLLIN,0}; int ready;
                    do { ready=poll(&fd,1,2000); } while(ready<0 && errno==EINTR);
                    close(fence); require(ready>0 && (fd.revents&POLLIN),"Capture producer fence timed out");
                }
                int64_t timestamp=0;
                require(AImage_getTimestamp(image.get(),&timestamp)==AMEDIA_OK,"Missing capture timestamp");
                int64_t outputTimestamp=timestamp;
                { std::lock_guard<std::mutex> lock(mutex);
                  if(!active) continue;
                  if(baseTimestamp>0) { if(firstTimestamp==0) firstTimestamp=timestamp; outputTimestamp=baseTimestamp+timestamp-firstTimestamp; }
                }
                FrameGate::Result decision=gate.accept(timestamp);
                { std::lock_guard<std::mutex> lock(mutex); acquired++; if(decision==FrameGate::RateLimited) rateLimited++; if(decision==FrameGate::Invalid) badTimestamps++; }
                if(decision!=FrameGate::Accepted) continue;
                AHardwareBuffer* buffer=nullptr; require(AImage_getHardwareBuffer(image.get(),&buffer)==AMEDIA_OK && buffer,"Missing capture hardware buffer");
                AHardwareBuffer_Desc desc{}; AHardwareBuffer_describe(buffer,&desc);
                require(desc.format==AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM || desc.format==AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM,"Capture format changed; this test requires SDR RGBA input");
                AImageCropRect crop{}; require(AImage_getCropRect(image.get(),&crop)==AMEDIA_OK && crop.left>=0 && crop.top>=0 && crop.right>crop.left && crop.bottom>crop.top && crop.right<=static_cast<int32_t>(desc.width) && crop.bottom<=static_cast<int32_t>(desc.height),"Invalid capture crop");
                auto start=Clock::now(); renderer->render(std::move(image),outputTimestamp);
                double ms=std::chrono::duration<double,std::milli>(Clock::now()-start).count();
                { std::lock_guard<std::mutex> lock(mutex); rendered++; totalMs+=ms; maxMs=std::max(maxMs,ms); }
            }
            renderer->finish();
        } catch(const std::exception& e) {
            std::lock_guard<std::mutex> lock(mutex); error=e.what(); initialized=true; wake.notify_all();
        }
        // GPU objects and EGL contexts are destroyed on their owning worker.
        renderer.reset();
    }
public:
    void init(ANativeWindow* window,int w,int h,int rate,bool vk) {
        encoder=window; width=w; height=h; fps=rate; vulkan=vk;
        require(w>0 && h>0 && w<=8192 && h<=8192 && (rate>=1 && rate<=240),"Invalid capture settings");
        require(AImageReader_newWithUsage(width,height,AIMAGE_FORMAT_RGBA_8888,AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,6,&reader)==AMEDIA_OK,"RGBA capture reader unavailable");
        require(AImageReader_getWindow(reader,&input)==AMEDIA_OK && input,"Capture input Surface unavailable");
        AImageReader_ImageListener listener{this,onImage}; require(AImageReader_setImageListener(reader,&listener)==AMEDIA_OK,"Capture listener unavailable");
        thread=std::thread([this]{run();});
        std::unique_lock<std::mutex> lock(mutex); wake.wait(lock,[&]{return initialized;}); require(error.empty(),error);
    }
    ANativeWindow* window() const { return input; }
    void configure(bool enabled,int64_t base) {
        std::lock_guard<std::mutex> lock(mutex); active=enabled;
        if(base>0 && baseTimestamp!=base) { baseTimestamp=base; firstTimestamp=0; }
    }
    void requestStop() { std::lock_guard<std::mutex> lock(mutex); quitting=true; wake.notify_all(); }
    void stop() {
        requestStop();
        if(thread.joinable()) thread.join();
    }
    std::string stats() {
        std::lock_guard<std::mutex> lock(mutex);
        std::ostringstream out;
        out<<"{\"backend\":"<<jsonString(vulkan?"vulkan":"gles")<<",\"driver\":"<<jsonString(driver)
           <<",\"acquired\":"<<acquired<<",\"submitted\":"<<rendered<<",\"rate_limited\":"<<rateLimited
           <<",\"invalid_timestamps\":"<<badTimestamps<<",\"submit_and_wait_ms_mean\":"<<(rendered?totalMs/rendered:0)
           <<",\"submit_and_wait_ms_max\":"<<maxMs<<",\"error\":"<<jsonString(error)<<"}";
        return out.str();
    }
    ~Engine() { stop(); if(reader) AImageReader_delete(reader); if(encoder) ANativeWindow_release(encoder); }
};
void fail(JNIEnv* env,const char* message) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),message); }
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_local_recorderlab_NativeBridge_create(JNIEnv* env,jclass,jobject surface,jint width,jint height,jint fps,jboolean vulkan) {
    try {
        auto engine=std::make_unique<Engine>(); ANativeWindow* window=ANativeWindow_fromSurface(env,surface);
        require(window!=nullptr,"Encoder surface unavailable"); engine->init(window,width,height,fps,vulkan);
        return reinterpret_cast<jlong>(engine.release());
    } catch(const std::exception& e) { fail(env,e.what()); return 0; }
}
extern "C" JNIEXPORT jobject JNICALL Java_dev_local_recorderlab_NativeBridge_inputSurface(JNIEnv* env,jclass,jlong handle) {
    if(!handle) { fail(env,"No capture engine"); return nullptr; }
    return ANativeWindow_toSurface(env,reinterpret_cast<Engine*>(handle)->window());
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_local_recorderlab_NativeBridge_stats(JNIEnv* env,jclass,jlong handle) {
    if(!handle) return env->NewStringUTF("{}");
    return env->NewStringUTF(reinterpret_cast<Engine*>(handle)->stats().c_str());
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_local_recorderlab_NativeBridge_stop(JNIEnv* env,jclass,jlong handle) {
    if(!handle) return env->NewStringUTF("{}");
    std::unique_ptr<Engine> engine(reinterpret_cast<Engine*>(handle)); engine->stop();
    return env->NewStringUTF(engine->stats().c_str());
}

extern "C" JNIEXPORT void JNICALL Java_dev_local_recorderlab_NativeBridge_configure(JNIEnv*,jclass,jlong handle,jboolean active,jlong base) {
    if(handle) reinterpret_cast<Engine*>(handle)->configure(active,base);
}
extern "C" JNIEXPORT void JNICALL Java_dev_local_recorderlab_NativeBridge_requestStop(JNIEnv*,jclass,jlong handle) {
    if(handle) reinterpret_cast<Engine*>(handle)->requestStop();
}
