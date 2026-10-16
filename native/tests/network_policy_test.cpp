#include "../network_policy.h"
#include "../video_controls.h"
#include <cassert>
#include <string>

int main() {
    using namespace sunshine_android;
    std::string bytes(21,'\0');
    auto put16=[&](int off,int value){bytes[off]=char(value>>8); bytes[off+1]=char(value);};
    bytes[3]=100; put16(10,10); put16(12,2); put16(14,9); put16(16,1);
    bytes[18]=20; bytes[20]=1;
    auto report=parse_fec_report(bytes);
    assert(report && report->frame==100 && report->data==10 && report->parity==2);
    assert(report->received_data==9 && report->received_parity==1 && !report->unrecoverable());
    assert(!parse_fec_report(std::string_view(bytes).substr(1)));
    for(size_t size=0;size<21;++size) assert(!parse_fec_report(std::string_view(bytes).substr(0,size)));
    auto invalid=bytes; invalid[20]=0; assert(!parse_fec_report(invalid));
    invalid=bytes; invalid[19]=1; assert(!parse_fec_report(invalid));
    invalid=bytes; invalid[15]=11; assert(!parse_fec_report(invalid));
    invalid=bytes; invalid[11]=char(255); assert(!parse_fec_report(invalid));
    assert(nominal_budget(10000,20)==12000 && encoding_budget(12000,35)==8888);
    assert(encoding_budget(nominal_budget(7308,20),20)>=7308); // Integer rounding preserves the initial budget.
    assert(pacing_budget(12000)==80000&&pacing_budget(60000)==120500);
    assert(pacing_delay_ns(1,1500,1000)==12000000);
    assert(pacing_delay_ns(10,1500,10000)==12000000);
    DatagramPacer pacer(10000);
    for(int n=0;n<16;++n){assert(pacer.wait_ns(0)==0);pacer.account(1500,0);}
    assert(pacer.wait_ns(0)==19200000); // Full datagrams, including headers/parity.
    pacer.account(1500,30000000); // Overslept; do not spend accumulated idle credit.
    for(int n=0;n<15;++n){assert(pacer.wait_ns(30000000)==0);pacer.account(1500,30000000);}
    assert(pacer.wait_ns(30000000)==19200000);
    DatagramPacer fragmented(10000);
    for(int n=0;n<15;++n)fragmented.account(1500,0); // End first FEC block before burst boundary.
    assert(fragmented.wait_ns(0)==0);fragmented.account(1000,0);
    assert(fragmented.wait_ns(0)==18800000); // Next FEC block shares the burst and byte budget.
    assert(frame_pacing_kbps(8000,300000)==120000);
    assert(frame_pacing_kbps(8000,1000000)==160000);
    assert(frame_pacing_kbps(200000,1000000)==200000);
    assert(frame_pacing_kbps(8000,1000)==8000);
    assert(video_fec_blocks(90,20,6)==1);
    assert(video_fec_blocks(91,20,6)==3);
    assert(video_fec_blocks(636,20,6)==3);
    assert(video_fec_blocks(637,20,6)==4);
    assert(video_fec_blocks(840,20,6)==4);
    assert(video_fec_blocks(3000,20,6)==4);
    assert(video_fec_blocks(400,20,200)==4); // Minimum parity also limits capacity.
    NetworkPolicy policy; policy.initialize(10000,20);
    assert(!policy.feedback(*report,60,0)); // Never accept a frame that wasn't sent.
    policy.last_sent_frame=100; policy.has_sent_frame=true; policy.last_sent_at=0;
    assert(policy.feedback(*report,60,1));
    assert(policy.reports==1 && policy.recovered_blocks==1 && policy.missing_data==1);
    auto delayed=*report; delayed.frame=99; policy.last_sent_frame=500;
    assert(!policy.feedback(delayed,60,2));
    policy.last_sent_frame=100;
    assert(!policy.feedback(*report,60,2) && policy.duplicate_reports==1);
    report->frame=101; assert(!policy.feedback(*report,60,3));
    report->frame=100;
    report->frame=98; assert(!policy.feedback(*report,60,6000)); // Idle sender cannot make an old report fresh.
    report->frame=100;
    assert(policy.react(*report,10000,1000,4000)==0); // Default-off.
    policy.enabled(true,1000);
    assert(policy.react(*report,10000,1000,2000)==0); // Cooldown.
    assert(policy.react(*report,10000,1000,4000)==0); // An isolated recoverable loss is watched.
    assert(policy.react(*report,10000,1000,4100)==0);
    int target=policy.react(*report,10000,1000,4300);
    assert(target==8500 && policy.fec==20 && policy.pending_fec==22);
    policy.driver_result(target,false); assert(policy.fec==20 && policy.state==std::string("driver_rejected"));
    assert(policy.react(*report,10000,1000,7000)==0);
    assert(policy.react(*report,10000,1000,7100)==0);
    target=policy.react(*report,10000,1000,7300);
    policy.driver_result(target,true); assert(policy.fec==22 && nominal_budget(target,policy.fec)<=policy.total_budget);
    policy.last_bad=7000; policy.rtt_at=8000; policy.rtt=1;
    assert(policy.recover(8500,10000,30000)==0); // Stale control feedback.
    policy.rtt_at=30000;
    policy.observe_queue(0,0,8500,1000,30000);
    target=policy.recover(8500,10000,30000); assert(target>8500 && target<=10000);
    policy.driver_result(target,true); assert(policy.fec==21);
    policy.enabled(false,31000); assert(policy.recover(target,10000,40000)==0);
    assert(policy.propose_fec(35,10000,9500)==0); // Keep floor and total budget.
    report->received_parity=0;
    policy.enabled(true,50000);
    assert(policy.react(*report,10000,1000,53000)==0);
    target=policy.react(*report,10000,1000,53300);
    assert(target==8500 && policy.pending_fec==26); // Unrecoverable block requests stronger FEC.
    policy.driver_result(target,true);
    policy.last_sent_frame=1; policy.last_sent_at=54000; report->frame=UINT32_MAX;
    assert(policy.feedback(*report,60,54000)); // Frame sequence wrap.
    NetworkPolicy pressure; pressure.initialize(10000,20); pressure.enabled(true,0);
    assert(pressure.observe_queue(50,4,10000,1000,3000)==0);
    assert(pressure.observe_queue(50,4,10000,1000,3200)==0);
    assert(pressure.observe_queue(50,4,10000,1000,3400)==0);
    auto reduced=pressure.observe_queue(50,4,10000,1000,3600);
    assert(reduced==9000 && pressure.pending_fec==20); // Queue congestion doesn't raise FEC.
    pressure.driver_result(reduced,true); pressure.rtt=2; pressure.rtt_at=25000;
    assert(pressure.recover(9000,10000,25000)==0); // No fresh empty-queue observation.
    pressure.observe_queue(0,0,9000,1000,25000);
    assert(pressure.recover(9000,10000,25000)>9000);
    pressure.enabled(true,30000);
    assert(pressure.observe_rtt(3,9000,1000,31000)==0);
    assert(pressure.observe_rtt(160,9000,1000,33000)==0);
    assert(pressure.observe_rtt(170,9000,1000,34100)==8100);
    configure_video(1000,50000,240);
    auto s=std::make_shared<VideoSession>(); s->negotiated=s->applied=10000; register_video(s);
    assert(control_video(s->id,4,35));
    auto command=take_video_command(s); assert(command.bitrate==8888 && s->network.fec==20);
    s->network.driver_result(command.bitrate,true); s->applied=command.bitrate;
    assert(s->network.fec==35 && video_ceiling(*s)==8888);
    assert(!control_video(s->id,1,10000));
    assert(!control_video(s->id,4,36) && !control_video(s->id,5,2));
    assert(control_video(s->id,5,1) && s->network.adaptive);
    assert(control_video(s->id,1,7000) && !s->network.adaptive);
    unregister_video(s->id);
    assert(!control_video(s->id,4,20));
    // Exercise the production command path, not only NetworkPolicy::recover.
    auto adaptive=std::make_shared<VideoSession>();adaptive->negotiated=adaptive->applied=10000;
    register_video(adaptive);
    assert(control_video(adaptive->id,1,8000));
    auto lower=take_video_command(adaptive);assert(lower.bitrate==8000);
    adaptive->applied=lower.bitrate;
    assert(control_video(adaptive->id,5,1));
    const auto tick=video_now_ms();
    adaptive->network.last_bad=tick-25000;adaptive->network.last_change=tick-6000;
    adaptive->network.observe_queue(0,0,8000,1000,tick);
    adaptive->network.observe_rtt(2,8000,1000,tick);
    auto probe=take_video_command(adaptive);
    assert(probe.bitrate==8400&&adaptive->desired==8400); // Healthy reduced stream probes upward.
    assert(take_video_command(adaptive).bitrate==0); // No second command while driver update is pending.
    adaptive->network.driver_result(probe.bitrate,true);adaptive->applied=probe.bitrate;
    adaptive->network.last_change=tick-6000;adaptive->last_output=VideoClock::now()-std::chrono::seconds(3);
    assert(take_video_command(adaptive).bitrate==0); // Idle output cannot establish recovery.
    adaptive->last_output=VideoClock::now();adaptive->network.rtt_at=tick-6000;
    assert(take_video_command(adaptive).bitrate==0); // Stale RTT must not probe.
    adaptive->network.rtt_at=video_now_ms();adaptive->queue.waiting_idr=true;
    assert(take_video_command(adaptive).bitrate==0); // Decoder recovery is still in progress.
    adaptive->queue.waiting_idr=false;adaptive->network.enabled(false,video_now_ms());
    assert(take_video_command(adaptive).bitrate==0); // Manual/default-off operation stays unchanged.
    unregister_video(adaptive->id);
}
