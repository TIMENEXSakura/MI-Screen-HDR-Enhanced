#pragma once
#include <unistd.h>
#include <utility>

// An acquired image and its own producer fence are one ownership unit. Release
// consumes the fd, including when a newer queued image replaces this image.
template<class Image,class Release>
class CaptureFrame {
public:
    Image* image=nullptr;
    int fence=-1;
    CaptureFrame()=default;
    CaptureFrame(Image* value,int fd):image(value),fence(fd){}
    CaptureFrame(const CaptureFrame&)=delete;
    CaptureFrame& operator=(const CaptureFrame&)=delete;
    CaptureFrame(CaptureFrame&& other) noexcept
        :image(std::exchange(other.image,nullptr)),fence(std::exchange(other.fence,-1)){}
    CaptureFrame& operator=(CaptureFrame&& other) noexcept {
        if(this!=&other){reset();image=std::exchange(other.image,nullptr);fence=std::exchange(other.fence,-1);}
        return *this;
    }
    ~CaptureFrame(){reset();}
    explicit operator bool() const {return image!=nullptr;}
    void reset() {
        if(image)Release{}(image,fence);
        else if(fence>=0)close(fence);
        image=nullptr;fence=-1;
    }
    void ready(){if(fence>=0)close(std::exchange(fence,-1));}
    Image* releaseImage(){return std::exchange(image,nullptr);}
};

// Bound the drain so a producer can never keep the consumer inside this loop.
// The caller probes the queue again after processing the returned frame.
template<class Frame,class Acquire>
Frame acquireNewestFrame(Acquire acquire,unsigned budget) {
    Frame latest;
    for(unsigned i=0;i<budget;i++) {
        Frame next=acquire();
        if(!next)break;
        latest=std::move(next);
    }
    return latest;
}
