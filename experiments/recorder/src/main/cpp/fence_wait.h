#pragma once
#include <poll.h>
#include <cerrno>
#include <cstdint>
#include <chrono>
#include <algorithm>

enum class FenceState { Ready, TimedOut, Cancelled, Failed };
struct FenceWaitResult { FenceState state; int error; short events; int elapsedMs; };

// Absolute deadline: signals must not restart the entire timeout. Short waits
// also let stop/pause cancel promptly without ever reading an unfinished image.
template<class Cancelled>
FenceWaitResult waitCaptureFence(int fd,int timeoutMs,Cancelled cancelled) {
    using Clock=std::chrono::steady_clock;
    const auto started=Clock::now(), deadline=started+std::chrono::milliseconds(timeoutMs);
    auto result=[&](FenceState state,int error=0,short events=0) {
        return FenceWaitResult{state,error,events,static_cast<int>(std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now()-started).count())};
    };
    for(;;) {
        if(cancelled()) return result(FenceState::Cancelled);
        auto remaining=std::chrono::duration_cast<std::chrono::milliseconds>(deadline-Clock::now()).count();
        pollfd fence{fd,POLLIN,0};
        int ready=poll(&fence,1,static_cast<int>(std::clamp<int64_t>(remaining,0,50)));
        if(ready<0) { if(errno==EINTR)continue; return result(FenceState::Failed,errno); }
        if(ready>0) {
            if(fence.revents&(POLLERR|POLLHUP|POLLNVAL))return result(FenceState::Failed,(fence.revents&POLLNVAL)?EBADF:EIO,fence.revents);
            if(fence.revents&POLLIN)return result(FenceState::Ready,0,fence.revents);
        }
        if(Clock::now()>=deadline)return result(FenceState::TimedOut);
    }
}
