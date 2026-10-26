#include "../src/main/cpp/fence_wait.h"
#include <atomic>
#include <cassert>
#include <csignal>
#include <iostream>
#include <thread>
#include <unistd.h>
#include <pthread.h>
using namespace std::chrono_literals;
struct Pipe {
    int fd[2];
    Pipe(){assert(pipe(fd)==0);}
    ~Pipe(){for(int value:fd)if(value>=0)close(value);}
    void signal(){char byte=1;assert(write(fd[1],&byte,1)==1);}
};
int main() {
    auto never=[] {return false;};
    {
        Pipe p;std::thread producer([&]{std::this_thread::sleep_for(20ms);p.signal();});
        auto ready=waitCaptureFence(p.fd[0],1000,never);producer.join();
        assert(ready.state==FenceState::Ready);
    }
    {
        Pipe p;auto timeout=waitCaptureFence(p.fd[0],60,never);
        assert(timeout.state==FenceState::TimedOut&&timeout.elapsedMs>=60&&timeout.elapsedMs<500);
        // Waiting did not close or consume the producer fence. The next frame
        // and a late signal from this frame can still be handled independently.
        Pipe next;next.signal();assert(waitCaptureFence(next.fd[0],1000,never).state==FenceState::Ready);
        p.signal();assert(waitCaptureFence(p.fd[0],1000,never).state==FenceState::Ready);
    }
    {
        Pipe p;std::atomic<bool> stop=false;
        std::thread stopper([&]{std::this_thread::sleep_for(20ms);stop=true;});
        auto cancelled=waitCaptureFence(p.fd[0],10000,[&]{return stop.load();});stopper.join();
        assert(cancelled.state==FenceState::Cancelled&&cancelled.elapsedMs<500);
    }
    {
        Pipe p;int closed=p.fd[0];close(closed);p.fd[0]=-1;
        auto bad=waitCaptureFence(closed,1000,never);
        assert(bad.state==FenceState::Failed&&bad.error==EBADF);
    }
    {
        Pipe p;close(p.fd[1]);p.fd[1]=-1;
        auto bad=waitCaptureFence(p.fd[0],1000,never);
        assert(bad.state==FenceState::Failed&&(bad.events&POLLHUP));
    }
    {
        struct sigaction action{};action.sa_handler=+[](int){};sigemptyset(&action.sa_mask);
        assert(sigaction(SIGUSR1,&action,nullptr)==0);
        Pipe p;auto waiter=pthread_self();std::atomic<bool> done=false;
        std::thread interrupter([&]{for(int i=0;i<80&&!done;i++){std::this_thread::sleep_for(5ms);pthread_kill(waiter,SIGUSR1);}});
        auto timeout=waitCaptureFence(p.fd[0],80,never);done=true;interrupter.join();
        assert(timeout.state==FenceState::TimedOut&&timeout.elapsedMs>=80&&timeout.elapsedMs<300);
    }
    std::cout<<"PASS input fence readiness, timeout recovery, cancellation, invalid descriptors and interrupted deadlines\n";
}
