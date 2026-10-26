#pragma once
#include <cstdint>
// Only limits delivery rate; it never invents frames or rewrites capture timestamps.
class FrameGate {
    int64_t period, next=0, previous=0;
public:
    explicit FrameGate(int fps):period(1'000'000'000LL/fps) {}
    enum Result { Accepted, RateLimited, Invalid };
    Result accept(int64_t timestamp) {
        if(timestamp<=0 || timestamp<=previous) return Invalid;
        previous=timestamp;
        if(next && timestamp+250'000<next) return RateLimited;
        if(!next || timestamp-next>period) next=timestamp+period;
        else next+=period;
        return Accepted;
    }
};
