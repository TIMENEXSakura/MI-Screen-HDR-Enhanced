#include "../src/main/cpp/capture_frame.h"
#include <cassert>
#include <cerrno>
#include <fcntl.h>
#include <iostream>
#include <stdexcept>
#include <vector>
struct Image {int id,ownFence;};
static std::vector<int> released;
struct Release {
    void operator()(Image* image,int fd) const {
        // A queue drain must never give an older image the newer image's fd.
        assert(fd==image->ownFence);
        if(fd>=0){assert(fcntl(fd,F_GETFD)!=-1);close(fd);}
        released.push_back(image->id);delete image;
    }
};
using Frame=CaptureFrame<Image,Release>;
Frame makeFrame(int id) {
    int fd=open("/dev/null",O_RDONLY);assert(fd>=0);
    return Frame(new Image{id,fd},fd);
}
int main() {
    {
        int count=0;
        Frame newest=acquireNewestFrame<Frame>([&]{return ++count<=3?makeFrame(count):Frame{};},6);
        assert(newest.image->id==3&&count==4);
        assert((released==std::vector<int>{1,2}));
        assert(fcntl(newest.fence,F_GETFD)!=-1);
    }
    assert((released==std::vector<int>{1,2,3}));released.clear();
    {
        int count=0;
        Frame newest=acquireNewestFrame<Frame>([&]{return makeFrame(++count);},6);
        assert(count==6&&newest.image->id==6&&released.size()==5);
        int fd=newest.fence;newest.ready();
        assert(fcntl(fd,F_GETFD)==-1&&errno==EBADF);
        Image* image=newest.releaseImage();delete image;
    }
    assert(released.size()==5);released.clear();
    {
        int count=0;
        try {
            auto newest=acquireNewestFrame<Frame>([&]() -> Frame {
                if(++count==2)throw std::runtime_error("acquire failed");
                return makeFrame(count);
            },6);
            assert(false);
        }catch(const std::runtime_error&){}
        assert((released==std::vector<int>{1}));
    }
    std::cout<<"PASS queued image/fence pairing, bounded drains, ready ownership and exceptional release\n";
}
