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
    t=t.replace('auto packets = mail::man->queue<video::packet_t>(mail::video_packets);','')
    video_start=t.index('videoBroadcastThread(udp::socket &sock)')
    t=t[:video_start]+t[video_start:].replace('while (auto packet = packets->pop()) {','while (auto packet = android_host::pop_video_packet()) {',1)
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
    t=t.replace('#include "audio.h"','#include "audio.h"\n#include "latest_queue.h"')
    t=t.replace('safe::queue_t<std::vector<std::int16_t>>','sunshine_android::LatestQueue<std::vector<std::int16_t>>')
    t=t.replace('std::make_shared<sample_queue_t::element_type>(30)','std::make_shared<sample_queue_t::element_type>(std::max(1,40/config.packetDuration))')
    audio.write_text(t,encoding='utf-8')
    t=stream.read_text(encoding='utf-8')
    t=t.replace('auto cb = _map_type_cb.find(type);','android_host::network_feedback(session,type,payload);\n    auto cb = _map_type_cb.find(type);')
    t=t.replace('session->pingTimeout = std::chrono::steady_clock::now() + config::stream.ping_timeout;','session->pingTimeout = std::chrono::steady_clock::now() + config::stream.ping_timeout;\n      android_host::network_rtt(session,event.peer->roundTripTime);')
    # Record each datagram result in the individual-send path. Our platform
    # deliberately reports batch unsupported before sending any prefix.
    t=t.replace('auto shards = fec::encode(current_payload,','auto fec_started = std::chrono::steady_clock::now();\n          auto shards = fec::encode(current_payload,')
    t=t.replace('// set FEC info now that we know','auto fec_elapsed = std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-fec_started).count();\n          uint64_t sent_data=0,sent_parity=0,sent_bytes=0,send_errors=0;\n          auto send_started=std::chrono::steady_clock::now();\n          // set FEC info now that we know')
    t=t.replace('platf::send(send_info);','if (platf::send(send_info)) { sent_bytes+=send_info.size; if(size_t(x)<shards.data_shards)++sent_data;else ++sent_parity; } else ++send_errors;',1)
    t=t.replace('if (packet->is_idr()) {\n            BOOST_LOG','android_host::record_network_send(session,sent_data,sent_parity,sent_bytes,send_errors,fec_elapsed,std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-send_started).count());\n          if (packet->is_idr()) {\n            BOOST_LOG')
    t=t.replace('session->video.lowseq = lowseq;','session->video.lowseq = lowseq;\n        android_host::record_frame_sent(session,packet->frame_index());')
    stream.write_text(t,encoding='utf-8')
