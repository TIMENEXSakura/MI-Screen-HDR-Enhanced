#include "graphics.h"
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <unordered_map>
#include <sstream>

namespace {
struct Imported { EGLImageKHR image = EGL_NO_IMAGE_KHR; GLuint texture = 0; AHardwareBuffer* buffer = nullptr; };
struct Frame { GLsync fence = nullptr; ImagePtr image = imagePtr(); };
class Gles final : public Renderer {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    GLuint program = 0, vao = 0;
    GLint cropUniform = -1;
    int width = 0, height = 0, frame = 0;
    Frame frames[2];
    std::unordered_map<AHardwareBuffer*, Imported> cache;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC nativeBuffer = nullptr;
    PFNEGLCREATEIMAGEKHRPROC createImage = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC destroyImage = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC targetImage = nullptr;
    PFNEGLPRESENTATIONTIMEANDROIDPROC presentation = nullptr;
    GLuint shader(GLenum type, const char* source) {
        GLuint id = glCreateShader(type); glShaderSource(id, 1, &source, nullptr); glCompileShader(id);
        GLint ok = 0; glGetShaderiv(id, GL_COMPILE_STATUS, &ok);
        if (!ok) { char text[1024]{}; glGetShaderInfoLog(id, sizeof(text), nullptr, text); glDeleteShader(id); throw std::runtime_error(text); }
        return id;
    }
    void release(Frame& f) {
        if (f.fence) {
            GLenum result = glClientWaitSync(f.fence, GL_SYNC_FLUSH_COMMANDS_BIT, 2'000'000'000ULL);
            require(result == GL_ALREADY_SIGNALED || result == GL_CONDITION_SATISFIED, "GLES input fence timed out");
            glDeleteSync(f.fence); f.fence = nullptr;
        }
        f.image.reset();
    }
public:
    void init(ANativeWindow* window, int w, int h) override {
        width=w; height=h;
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        require(display != EGL_NO_DISPLAY && eglInitialize(display, nullptr, nullptr), "EGL initialization failed");
        const EGLint configAttrs[] = {EGL_SURFACE_TYPE,EGL_WINDOW_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,
            EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_RECORDABLE_ANDROID,EGL_TRUE,EGL_NONE};
        EGLConfig config; EGLint count;
        require(eglChooseConfig(display, configAttrs, &config, 1, &count) && count, "No recordable GLES 3 config");
        const EGLint contextAttrs[] = {EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};
        context = eglCreateContext(display,config,EGL_NO_CONTEXT,contextAttrs);
        require(context != EGL_NO_CONTEXT, "GLES 3 context unavailable");
        surface = eglCreateWindowSurface(display,config,window,nullptr);
        require(surface != EGL_NO_SURFACE && eglMakeCurrent(display,surface,surface,context), "Encoder EGL surface unavailable");
        eglSwapInterval(display,0);
        nativeBuffer = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
        createImage = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
        destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
        targetImage = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
        presentation = reinterpret_cast<PFNEGLPRESENTATIONTIMEANDROIDPROC>(eglGetProcAddress("eglPresentationTimeANDROID"));
        require(nativeBuffer && createImage && destroyImage && targetImage && presentation, "Missing EGL hardware-buffer/timestamp extension");
        description = std::string(reinterpret_cast<const char*>(glGetString(GL_VERSION))) + " / " + reinterpret_cast<const char*>(glGetString(GL_RENDERER));
        const char* vert = "#version 300 es\nprecision highp float; uniform vec4 crop; out vec2 uv; void main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2)); gl_Position=vec4(p*2.0-1.0,0,1); uv=crop.xy+vec2(p.x,1.0-p.y)*crop.zw;}";
        const char* frag = "#version 300 es\nprecision highp float; in vec2 uv; uniform sampler2D frame; out vec4 color; void main(){color=vec4(texture(frame,uv).rgb,1.0);}";
        GLuint vs=shader(GL_VERTEX_SHADER,vert), fs=shader(GL_FRAGMENT_SHADER,frag);
        program=glCreateProgram(); glAttachShader(program,vs); glAttachShader(program,fs); glLinkProgram(program);
        glDeleteShader(vs); glDeleteShader(fs); GLint linked; glGetProgramiv(program,GL_LINK_STATUS,&linked);
        require(linked,"GLES shader link failed");
        glUseProgram(program); glUniform1i(glGetUniformLocation(program,"frame"),0); cropUniform=glGetUniformLocation(program,"crop");
        glGenVertexArrays(1,&vao); glBindVertexArray(vao); glViewport(0,0,width,height); glDisable(GL_BLEND);
    }
    void render(ImagePtr image, int64_t timestamp) override {
        Frame& slot=frames[frame++%2]; release(slot); slot.image=std::move(image);
        AHardwareBuffer* buffer=nullptr;
        require(AImage_getHardwareBuffer(slot.image.get(),&buffer)==AMEDIA_OK && buffer,"GLES input has no hardware buffer");
        auto found=cache.find(buffer);
        if(found==cache.end()) {
            require(cache.size()<16,"Input buffer pool grew unexpectedly");
            auto& entry=cache[buffer]; entry.buffer=buffer; AHardwareBuffer_acquire(buffer);
            const EGLint attrs[]={EGL_IMAGE_PRESERVED_KHR,EGL_TRUE,EGL_NONE};
            entry.image=createImage(display,EGL_NO_CONTEXT,EGL_NATIVE_BUFFER_ANDROID,nativeBuffer(buffer),attrs);
            require(entry.image!=EGL_NO_IMAGE_KHR,"Cannot import input buffer into GLES");
            glGenTextures(1,&entry.texture); glBindTexture(GL_TEXTURE_2D,entry.texture);
            targetImage(GL_TEXTURE_2D,entry.image);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
            found=cache.find(buffer);
        }
        AHardwareBuffer_Desc desc{}; AHardwareBuffer_describe(buffer,&desc); AImageCropRect crop{};
        require(AImage_getCropRect(slot.image.get(),&crop)==AMEDIA_OK,"Missing input crop");
        glBindTexture(GL_TEXTURE_2D,found->second.texture);
        glUniform4f(cropUniform,float(crop.left)/desc.width,float(crop.top)/desc.height,
            float(crop.right-crop.left)/desc.width,float(crop.bottom-crop.top)/desc.height);
        glDrawArrays(GL_TRIANGLES,0,3);
        require(glGetError()==GL_NO_ERROR,"GLES render failed");
        slot.fence=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);
        require(slot.fence!=nullptr,"GLES fence unavailable");
        require(presentation(display,surface,timestamp) && eglSwapBuffers(display,surface),"GLES encoder submission failed");
    }
    void finish() override { for(auto& f:frames) release(f); }
    ~Gles() override {
        if(context!=EGL_NO_CONTEXT && surface!=EGL_NO_SURFACE) {
            eglMakeCurrent(display,surface,surface,context); glFinish();
            for(auto& f:frames) { if(f.fence) glDeleteSync(f.fence); f.image.reset(); }
            for(auto& [key,e]:cache) { glDeleteTextures(1,&e.texture); if(e.image!=EGL_NO_IMAGE_KHR) destroyImage(display,e.image); AHardwareBuffer_release(e.buffer); }
            if(program) glDeleteProgram(program); if(vao) glDeleteVertexArrays(1,&vao);
        }
        if(display!=EGL_NO_DISPLAY) {
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
            if(surface!=EGL_NO_SURFACE) eglDestroySurface(display,surface);
            if(context!=EGL_NO_CONTEXT) eglDestroyContext(display,context);
            eglTerminate(display);
        }
    }
};
}
std::unique_ptr<Renderer> makeGles() { return std::make_unique<Gles>(); }
