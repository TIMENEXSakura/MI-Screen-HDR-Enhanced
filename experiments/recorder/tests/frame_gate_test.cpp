#include "../src/main/cpp/frame_gate.h"
#include <cassert>
#include <iostream>
int main() {
    FrameGate at60(60); int frames=0;
    for(int i=0;i<1200;i++) if(at60.accept(1'000'000'000LL+i*1'000'000'000LL/120)==FrameGate::Accepted) frames++;
    assert(frames==600);
    FrameGate at30(30); frames=0;
    for(int i=0;i<600;i++) if(at30.accept(1'000'000'000LL+i*1'000'000'000LL/60)==FrameGate::Accepted) frames++;
    assert(frames==300);
    FrameGate jitter(60); frames=0;
    for(int i=0;i<600;i++) if(jitter.accept(1'000'000'000LL+i*16'666'667LL+(i%2)*100'000)==FrameGate::Accepted) frames++;
    assert(frames==600);
    FrameGate gap(60);
    assert(gap.accept(1'000'000'000)==FrameGate::Accepted);
    assert(gap.accept(1'000'000'000)==FrameGate::Invalid);
    assert(gap.accept(999'000'000)==FrameGate::Invalid);
    assert(gap.accept(-1)==FrameGate::Invalid);
    assert(gap.accept(10'000'000'000)==FrameGate::Accepted);
    assert(gap.accept(10'001'000'000)==FrameGate::RateLimited);
    assert(gap.accept(10'016'666'667)==FrameGate::Accepted);
    std::cout << "PASS rate limiting, timestamp order, jitter tolerance and stalls\n";
}
