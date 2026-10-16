from pathlib import Path
import shutil

def prepare(upstream: Path, source: Path, overlay: Path):
    shutil.copytree(upstream,source,dirs_exist_ok=True,ignore=shutil.ignore_patterns(".git"))
    for name in ['logging.h','video.h','video_colorspace.h','process.h']:
        shutil.copy2(overlay/name,source/'src'/name)
    config=source/'src/config.cpp'
    t=config.read_text(encoding='utf-8')
    t=t.replace('#ifdef __APPLE__\n  // values accurate', '#if defined(__APPLE__) || defined(__ANDROID__)\n  // values accurate')
    config.write_text(t,encoding='utf-8')
    crypto=source/'src/crypto.cpp'
    t=crypto.read_text(encoding='utf-8').replace('#include <openssl/pem.h>', '#include <openssl/pem.h>\n#include <openssl/rsa.h>')
    crypto.write_text(t,encoding='utf-8')
    platform=source/'src/platform/common.h'
    t=platform.read_text(encoding='utf-8').replace('namespace process {','namespace process::inline v1 {')
    platform.write_text(t,encoding='utf-8')
    http=source/'src/nvhttp.cpp'
    t=http.read_text(encoding='utf-8').replace('SSL_get_peer_certificate','SSL_get1_peer_certificate')
    t=t.replace('auto device_nodes = root.get_child("root.devices");','auto device_nodes = android_host::import_pairing(root);')
    t=t.replace('http::unique_id = std::move(*unique_id_p);','http::unique_id = std::move(*unique_id_p);\n    android_host::server_uuid(http::unique_id);')
    t=t.replace('root.erase("root"s);', 'android_host::import_pairing(root);\n    root.erase("root"s);')
    t=t.replace('pt::write_json(config::nvhttp.file_state, root);','android_host::export_pairing(root);\n      pt::write_json(config::nvhttp.file_state, root);')
    # This handler only runs after the server's TLS client-certificate verification.
    t=t.replace('if constexpr (std::is_same_v<SimpleWeb::HTTPS, T>) {\n      auto args = request->parse_query_string();', 'if constexpr (std::is_same_v<SimpleWeb::HTTPS, T>) {\n      pair_status = 1;\n      auto args = request->parse_query_string();')
    t=t.replace('auto launch_session = make_launch_session(host_audio, args);','auto launch_session = make_launch_session(host_audio, args);\n    launch_session->android_client_id = android_host::paired_client(request->native_socket_handle());')
    t=t.replace('launch_session->android_client_id = android_host::paired_client(request->native_socket_handle());', 'launch_session->android_client_id = android_host::paired_client(request->native_socket_handle());\n    launch_session->android_client_address = request->remote_endpoint().address().to_string();')
    t=t.replace('return verified = 1;', 'if (!android_host::paired_authorized(ssl)) return 0;\n      return verified = 1;')
    t=t.replace('ptr->second.async_insert_pin.response = std::move(response);','ptr->second.async_insert_pin.response = std::move(response);\n          android_host::pin_requested();')
    t=t.replace('#include "config.h"','#include "config.h"\n#include "android_host.h"')
    http.write_text(t,encoding='utf-8')
    rtsp_header=source/'src/rtsp.h'
    t=rtsp_header.read_text(encoding='utf-8').replace('std::string unique_id;', 'std::string unique_id;\n    std::string android_client_id;\n    std::string android_client_address;')
    rtsp_header.write_text(t,encoding='utf-8')
    web_header=source/'third-party/Simple-Web-Server/server_http.hpp'
    t=web_header.read_text(encoding='utf-8')
    anchor='CaseInsensitiveMultimap header;'
    assert anchor in t
    t=t.replace(anchor,anchor+'\n      auto native_socket_handle() const { return connection.lock()->socket->native_handle(); }',1)
    web_header.write_text(t,encoding='utf-8')
    stream=source/'src/stream.cpp'
    t=stream.read_text(encoding='utf-8')
    t=t.replace('#include "process.h"','#include "process.h"\n#include "encoded_queue.h"')
    anchor='struct session_t {'
    assert t.count(anchor)==1
    t=t.replace(anchor,'struct session_t : std::enable_shared_from_this<session_t> {')
    anchor='std::thread videoThread;'
    assert t.count(anchor)==1
    t=t.replace(anchor,anchor+'\n    std::thread videoSenderThread;')
    # Release the broadcast reference on the join thread. A packet can be the
    # last session owner on a sender thread; its destructor must never join that
    # same broadcast thread or destruct a context still sending another peer.
    anchor='BOOST_LOG(debug) << "Session ended"sv;'
    assert t.count(anchor)==1
    t=t.replace(anchor,'if (session.broadcast_ref) session.broadcast_ref.release();\n      '+anchor)
    t=t.replace('auto session = (session_t *) packet->channel_data;',
                'auto session = (session_t *) packet->channel_data;\n      if (session->state.load(std::memory_order_acquire) != session::state_e::RUNNING) continue;',1)
    t=t.replace('auto packets = mail::man->queue<video::packet_t>(mail::video_packets);','')
    video_start=t.index('videoBroadcastThread(udp::socket &sock)')
    t=t[:video_start]+t[video_start:].replace('while (auto packet = packets->pop()) {','while (auto packet = android_host::pop_video_packet(channel)) {',1)
    start=t.index('videoBroadcastThread(udp::socket &sock)')
    end=t.index('audioBroadcastThread(udp::socket &sock)',start)
    video=t[start:end].replace('videoBroadcastThread(udp::socket &sock)','videoBroadcastThread(udp::socket &sock, session_t *channel)',1)
    video=video.replace('auto shutdown_event = mail::man->event<bool>(mail::broadcast_shutdown);','auto shutdown_event = channel->shutdown_event;',1)
    t=t[:start]+video+t[end:]
    anchor='ctx.video_thread = std::thread { videoBroadcastThread, std::ref(ctx.video_sock) };'
    assert t.count(anchor)==1
    t=t.replace(anchor,'// Each session owns an independently paced video/FEC sender.')
    t=t.replace('ctx.video_thread.join();','if (ctx.video_thread.joinable()) ctx.video_thread.join();')
    anchor='video::capture(session->mail, session->config.monitor, session);'
    assert t.count(anchor)==1
    t=t.replace(anchor,'''android_host::open_video_channel(session);
    session->videoSenderThread = std::thread { videoBroadcastThread, std::ref(ref->video_sock), session };
    video::capture(session->mail, session->config.monitor, session);
    android_host::remove_video_channel(session);
    if (session->videoSenderThread.joinable()) session->videoSenderThread.join();''')
    t=t.replace('auto video_packets = mail::man->queue<video::packet_t>(mail::video_packets);','')
    t=t.replace('video_packets->stop();','android_host::stop_video_queue();')
    t=t.replace('video_packets.reset();','')
    t=t.replace('start_broadcast(broadcast_ctx_t &ctx) {','start_broadcast(broadcast_ctx_t &ctx) {\n    android_host::reset_video_queue();')
    t=t.replace('auto fecPercentage = config::stream.fec_percentage;', 'auto fecPercentage = android_host::fec_for_channel(session);')
    anchor='session->config = config;'
    assert anchor in t
    t=t.replace(anchor,anchor+'\n      session->config.monitor.appid = launch_session.appid;\n      session->config.monitor.clientId = launch_session.android_client_id;\n      session->config.monitor.clientAddress = launch_session.android_client_address;\n      session->config.monitor.audioPacketMs = config.audio.packetDuration;')
    stream.write_text(t,encoding='utf-8')

    audio=source/'src/audio.cpp'
    t=audio.read_text(encoding='utf-8')
    t=t.replace('#include "audio.h"','#include "audio.h"\n#include "latest_queue.h"\n#include "android_host.h"')
    anchor='auto packets = mail::man->queue<packet_t>(mail::audio_packets);'
    assert t.count(anchor)==1
    t=t.replace(anchor,anchor+'\n    auto channel_owner = android_host::retain_channel(channel_data);')
    anchor='packets->raise(channel_data, std::move(packet));'
    assert t.count(anchor)==1
    t=t.replace(anchor,'packets->raise(channel_owner, std::move(packet));')
    t=t.replace('safe::queue_t<std::vector<std::int16_t>>','sunshine_android::LatestQueue<std::vector<std::int16_t>>')
    t=t.replace('std::make_shared<sample_queue_t::element_type>(30)','std::make_shared<sample_queue_t::element_type>(std::max(1,40/config.packetDuration))')
    audio.write_text(t,encoding='utf-8')
    audio_header=source/'src/audio.h'
    t=audio_header.read_text(encoding='utf-8')
    anchor='using packet_t = std::pair<void *, buffer_t>;'
    assert t.count(anchor)==1
    t=t.replace(anchor,'using packet_t = std::pair<std::shared_ptr<void>, buffer_t>;')
    audio_header.write_text(t,encoding='utf-8')
    t=stream.read_text(encoding='utf-8')
    anchor='auto session = (session_t *) channel_data;'
    assert t.count(anchor)==1
    t=t.replace(anchor,'auto session = (session_t *) channel_data.get();\n      if (session->state.load(std::memory_order_acquire) != session::state_e::RUNNING) continue;')
    t=t.replace('auto cb = _map_type_cb.find(type);','android_host::network_feedback(session,type,payload);\n    auto cb = _map_type_cb.find(type);')
    t=t.replace('session->pingTimeout = std::chrono::steady_clock::now() + config::stream.ping_timeout;','session->pingTimeout = std::chrono::steady_clock::now() + config::stream.ping_timeout;\n      android_host::network_rtt(session,event.peer->roundTripTime);')
    # The Android socket path has no batched pacing. Spread the actual wire
    # bytes (including minimum parity/header overhead) across bounded bursts.
    # Keep one deadline across all FEC blocks so a large frame cannot restart
    # its free burst at each block boundary.
    anchor='auto fecPercentage = android_host::fec_for_channel(session);'
    assert t.count(anchor)==1
    t=t.replace(anchor,anchor+'''
      auto frame_send_started = std::chrono::steady_clock::now();
      auto frame_cpu_started = android_host::video_thread_cpu_ns();
      auto frame_wire_estimate = ((payload.size()+payload_blocksize-1)/payload_blocksize)*blocksize;
      frame_wire_estimate = frame_wire_estimate*(100+fecPercentage)/100 + 6*blocksize;
      auto frame_pacing_kbps = sunshine_android::frame_pacing_kbps(
        android_host::pacing_for_channel(session),frame_wire_estimate);
      sunshine_android::DatagramPacer wire_pacer(frame_pacing_kbps);
      android_host::protect_video_recovery(session,
        int((frame_wire_estimate*8+frame_pacing_kbps-1)/frame_pacing_kbps)+30);''')
    anchor='constexpr auto MAX_FEC_BLOCKS = 3;'
    assert t.count(anchor)==1
    t=t.replace(anchor,'constexpr auto MAX_FEC_BLOCKS = 4;')
    anchor='if (payload.size() > multi_fec_threshold) {'
    assert t.count(anchor)==1
    t=t.replace(anchor,'''auto block_count = sunshine_android::video_fec_blocks(
        (payload.size()+blocksize-1)/blocksize,fecPercentage,session->config.minRequiredFecPackets);
      if (block_count > 1) {''')
    t=t.replace('auto unaligned_size = payload.size() / MAX_FEC_BLOCKS;','auto unaligned_size = (payload.size()+block_count-1) / block_count;')
    anchor='''fec_blocks[0] = payload.substr(0, aligned_size);
        fec_blocks[1] = payload.substr(aligned_size, aligned_size);
        fec_blocks[2] = payload.substr(aligned_size * 2);'''
    assert t.count(anchor)==1
    t=t.replace(anchor,'''for(size_t n=0;n<block_count;++n) fec_blocks[n]=payload.substr(n*aligned_size,aligned_size);''')
    t=t.replace('lastBlockIndex = 2 << 6;','lastBlockIndex = (block_count-1) << 6;')
    t=t.replace('fec_blocks_end = std::end(fec_blocks);','fec_blocks_end = std::begin(fec_blocks)+block_count;')
    anchor='auto blockIndex = 0;'
    assert t.count(anchor)==1
    t=t.replace(anchor,'android_host::record_video_stage(session,0,std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-frame_send_started).count());\n        '+anchor)
    t=t.replace('auto shards = fec::encode(current_payload,','auto fec_started = std::chrono::steady_clock::now();\n          auto shards = fec::encode(current_payload,')
    t=t.replace('// set FEC info now that we know','auto fec_elapsed = std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-fec_started).count();\n          uint64_t sent_data=0,sent_parity=0,sent_bytes=0,send_errors=0,send_retries=0,send_syscalls=0;\n          int last_send_errno=0;\n          double pacing_ms=0,send_ms=0;\n          auto header_started = std::chrono::steady_clock::now();\n          // set FEC info now that we know')
    t=t.replace('auto peer_address = session->video.peer.address();','android_host::record_video_stage(session,1,std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-header_started).count());\n          auto peer_address = session->video.peer.address();')
    # Replace only the fallback VIDEO loop; audio and header/encryption setup
    # retain upstream ordering. Each OS batch is at most one paced burst.
    start=t.index('for (auto x = 0; x < shards.size(); ++x) {',t.index('// Batched send is not available'))
    end=t.index('\n          }',start)
    original=t[start:end]
    assert 'platf::send(send_info);' in original
    t=t[:start]+'''for (size_t x = 0; x < size_t(shards.size());) {
              if (session->state.load(std::memory_order_acquire) != session::state_e::RUNNING) break;
              auto before_wait = std::chrono::steady_clock::now();
              auto wait_ns = wire_pacer.wait_ns(std::chrono::duration_cast<std::chrono::nanoseconds>(before_wait.time_since_epoch()).count());
              if (wait_ns > 0) {
                android_host::wait_video_packet(session,wait_ns);
                auto after_wait = std::chrono::steady_clock::now();
                pacing_ms += std::chrono::duration<double,std::milli>(after_wait-before_wait).count();
              }
              if (session->state.load(std::memory_order_acquire) != session::state_e::RUNNING) break;
              auto count = std::min<size_t>(sunshine_android::DatagramPacer::burst_packets,shards.size()-x);
              auto chunk = platf::batched_send_info_t{shards.prefix(x),size_t(shards.prefixsize+shards.blocksize),count,
                (uintptr_t)sock.native_handle(),peer_address,session->video.peer.port(),session->localAddress};
              auto send_started = std::chrono::steady_clock::now();
              for(size_t n=0;n<count;++n)wire_pacer.account(chunk.block_size,std::chrono::duration_cast<std::chrono::nanoseconds>(send_started.time_since_epoch()).count());
              auto result = android_host::send_video_batch(chunk);
              auto data = x<shards.data_shards?std::min(result.accepted,size_t(shards.data_shards)-x):0;
              sent_data+=data;sent_parity+=result.accepted-data;sent_bytes+=result.accepted*chunk.block_size;
              send_errors+=count-result.accepted;send_retries+=result.retries;send_syscalls+=result.syscalls;
              if(result.error)last_send_errno=result.error;
              send_ms += std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-send_started).count();
              x+=count;
            }'''+t[end:]
    anchor='if (packet->is_idr()) {\n            BOOST_LOG'
    assert t.count(anchor)==1
    t=t.replace(anchor,'android_host::record_network_send(session,sent_data,sent_parity,sent_bytes,send_errors,send_retries,send_syscalls,fec_elapsed,pacing_ms,send_ms,std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-frame_send_started).count(),fecPercentage>0&&shards.percentage==0,last_send_errno);\n          if (packet->is_idr()) {\n            BOOST_LOG')
    t=t.replace('session->video.lowseq = lowseq;','session->video.lowseq = lowseq;\n        android_host::record_frame_sent(session,packet->frame_index());\n        android_host::record_video_stage(session,2,(android_host::video_thread_cpu_ns()-frame_cpu_started)/1000000.0);')
    t+='''
namespace android_host {
std::shared_ptr<void> retain_channel(void* channel) {
  return static_cast<stream::session_t*>(channel)->shared_from_this();
}
}
'''
    stream.write_text(t,encoding='utf-8')
