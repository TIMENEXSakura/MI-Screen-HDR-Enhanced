#pragma once
#include <android/native_window.h>
#include <android/hardware_buffer.h>
#include <media/NdkImage.h>
#include <memory>
#include <stdexcept>
#include <string>
using ImagePtr = std::unique_ptr<AImage, decltype(&AImage_delete)>;
inline ImagePtr imagePtr(AImage* p = nullptr) { return ImagePtr(p, AImage_delete); }
inline void require(bool ok, const std::string& message) { if (!ok) throw std::runtime_error(message); }
class Renderer {
public:
    std::string description;
    virtual ~Renderer() = default;
    virtual void init(ANativeWindow*, int, int) = 0;
    virtual void render(ImagePtr, int64_t) = 0;
    virtual void finish() = 0;
};
std::unique_ptr<Renderer> makeGles();
std::unique_ptr<Renderer> makeVulkan();
