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
    assert(pacing_delay_ns(1,1500,1000)==12000000);
    assert(pacing_delay_ns(10,1500,10000)==12000000);
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
    int target=policy.react(*report,10000,1000,4000);
    assert(target==8500 && policy.fec==20 && policy.pending_fec==22);
    policy.driver_result(target,false); assert(policy.fec==20 && policy.state==std::string("driver_rejected"));
    target=policy.react(*report,10000,1000,7000);
    policy.driver_result(target,true); assert(policy.fec==22 && nominal_budget(target,policy.fec)<=policy.total_budget);
    policy.last_bad=7000; policy.rtt_at=8000; policy.rtt=1;
    assert(policy.recover(8500,10000,30000)==0); // Stale control feedback.
    policy.rtt_at=30000;
    target=policy.recover(8500,10000,30000); assert(target>8500 && target<=10000);
    policy.driver_result(target,true); assert(policy.fec==21);
    policy.enabled(false,31000); assert(policy.recover(target,10000,40000)==0);
    assert(policy.propose_fec(35,10000,9500)==0); // Keep floor and total budget.
    report->received_parity=0;
    policy.enabled(true,50000);
    target=policy.react(*report,10000,1000,53000);
    assert(target==8500 && policy.pending_fec==26); // Unrecoverable block requests stronger FEC.
    policy.driver_result(target,true);
    policy.last_sent_frame=1; policy.last_sent_at=54000; report->frame=UINT32_MAX;
    assert(policy.feedback(*report,60,54000)); // Frame sequence wrap.
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
}
