package com.alkaidlab.sunshine.service.headless;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.*;

/** Desktop JVM tests: no Android device, permissions, or external network required. */
public final class LocalWfdSinkTest {
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static byte[] wire(String first,String headers,String body){
        byte[] payload=body.getBytes(StandardCharsets.UTF_8);
        return (first+"\r\n"+headers+"Content-Length: "+payload.length+"\r\n\r\n"+body).getBytes(StandardCharsets.UTF_8);
    }
    static void send(OutputStream out,String first,String headers,String body)throws IOException{
        out.write(wire(first,headers,body));out.flush();
    }
    static void rejects(byte[] bytes)throws Exception{
        try{LocalWfdSink.readMessage(new ByteArrayInputStream(bytes));throw new AssertionError("Invalid message accepted");}
        catch(IOException expected){}
    }
    static void parser()throws Exception{
        byte[] frame=wire("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0","cSeQ: 7\r\n","wfd_trigger_method: SETUP\r\n");
        InputStream fragmented=new ByteArrayInputStream(frame){
            public synchronized int read(byte[] b,int off,int length){return super.read(b,off,Math.min(length,1));}
        };
        LocalWfdSink.Message message=LocalWfdSink.readMessage(fragmented);
        require(message.header("CSEQ").equals("7"),"Case-insensitive headers");
        require(message.body.equals("wfd_trigger_method: SETUP\r\n"),"Fragmented body");
        rejects("RTSP/1.0 200 OK\r\nContent-Length: 65537\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        rejects("RTSP/1.0 200 OK\r\nContent-Length: -1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        rejects("RTSP/1.0 200 OK\r\nContent-Length: abc\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        rejects("RTSP/1.0 200 OK\r\nContent-Length: 5\r\n\r\nx".getBytes(StandardCharsets.US_ASCII));
        rejects("RTSP/1.0 200 OK\r\nCSeq: 1\r\ncseq: 2\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        rejects(new byte[17000]);
        rejects("INVALID\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        String caps=LocalWfdSink.capabilities("wfd_client_rtp_ports\r\nwfd_content_protection\r\n",12340);
        require(caps.contains("unicast 12340 0"),"Advertised port");
        require(caps.contains("wfd_content_protection: none"),"No HDCP advertised");
        require(!caps.contains("wfd_audio_codecs"),"Only queried capabilities returned");
        String formats=LocalWfdSink.capabilities("wfd_video_formats\r\n",12340).split(": ",2)[1].trim();
        String[] fields=formats.split(" ");int nativeMode=Integer.parseInt(fields[0],16);
        require((nativeMode&7)==0&&(nativeMode>>3)==8,"CEA 1080p60 is the preferred native format");
        require((Integer.parseInt(fields[4],16)&(1<<8))!=0,"Preferred native mode must be supported");
        require((Integer.parseInt(fields[4],16)&(1<<7))!=0,"Retain 1080p30 fallback");
    }
    static void handshake()throws Exception{
        InetAddress loop=InetAddress.getByName("127.0.0.1");
        try(ServerSocket server=new ServerSocket(0,1,loop)){
            server.setSoTimeout(4000);
            AtomicReference<Throwable> error=new AtomicReference<>();
            CountDownLatch complete=new CountDownLatch(1),release=new CountDownLatch(1);
            Thread source=new Thread(()->{
                try(Socket socket=server.accept()){
                    socket.setSoTimeout(3000);
                    InputStream input=new BufferedInputStream(socket.getInputStream());OutputStream out=socket.getOutputStream();
                    send(out,"OPTIONS * RTSP/1.0","CSeq: 1\r\n","");
                    require(LocalWfdSink.readMessage(input).header("cseq").equals("1"),"M1 response");
                    LocalWfdSink.Message options=LocalWfdSink.readMessage(input);
                    require(options.firstLine.equals("OPTIONS * RTSP/1.0"),"M2 OPTIONS");
                    send(out,"RTSP/1.0 200 OK","CSeq: "+options.header("cseq")+"\r\n","");
                    send(out,"GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0","CSeq: 2\r\n",
                            "wfd_video_formats\r\nwfd_client_rtp_ports\r\nwfd_content_protection\r\n");
                    LocalWfdSink.Message caps=LocalWfdSink.readMessage(input);
                    Matcher port=Pattern.compile("unicast (\\d+) 0").matcher(caps.body);
                    require(port.find(),"M3 RTP port");int rtp=Integer.parseInt(port.group(1));
                    send(out,"SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0","CSeq: 3\r\n",
                            "wfd_presentation_URL: rtsp://127.0.0.1/wfd1.0/streamid=0 none\r\n"+
                            "wfd_video_formats: 40 00 02 10 00000100 00000000 00000000 00 0000 0000 00 none none\r\n");
                    require(LocalWfdSink.readMessage(input).header("cseq").equals("3"),"M4 response");
                    send(out,"SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0","CSeq: 4\r\n","wfd_trigger_method: SETUP\r\n");
                    require(LocalWfdSink.readMessage(input).header("cseq").equals("4"),"M5 response");
                    LocalWfdSink.Message setup=LocalWfdSink.readMessage(input);
                    require(setup.firstLine.startsWith("SETUP rtsp://127.0.0.1/wfd1.0/streamid=0 "),"SETUP URI");
                    require(setup.header("transport").contains("client_port="+rtp+"-"+(rtp+1)),"SETUP port pair");
                    send(out,"RTSP/1.0 200 OK","CSeq: "+setup.header("cseq")+"\r\nSession: loopback-test;timeout=30\r\n","");
                    LocalWfdSink.Message play=LocalWfdSink.readMessage(input);
                    require(play.firstLine.startsWith("PLAY ")&&play.header("session").equals("loopback-test"),"PLAY session");
                    send(out,"RTSP/1.0 200 OK","CSeq: "+play.header("cseq")+"\r\n","");
                    try(DatagramSocket udp=new DatagramSocket(new InetSocketAddress(loop,0))){
                        byte[] data=new byte[12];udp.send(new DatagramPacket(data,data.length,loop,rtp));
                        udp.send(new DatagramPacket(data,data.length,loop,rtp+1));
                    }
                    complete.countDown();release.await(3,TimeUnit.SECONDS);
                }catch(Throwable e){error.set(e);complete.countDown();}
            },"Fake-WFD-Source");
            source.setDaemon(true);source.start();
            try(LocalWfdSink sink=new LocalWfdSink(server.getLocalPort())){
                sink.start();require(complete.await(5,TimeUnit.SECONDS),"Handshake deadline");
                if(error.get()!=null)throw new AssertionError("Source failed",error.get());
                for(int i=0;i<50&&((Number)sink.snapshot().get("rtcp_packets")).longValue()==0;i++)Thread.sleep(20);
                require(sink.failure==null,"Sink failed: "+sink.failure);
                require(((Number)sink.snapshot().get("play_requests")).intValue()==1,"Exactly one PLAY");
                require(sink.snapshot().get("selected_video_format").toString().startsWith("40 00 02 10 00000100 "),"Record source-selected format separately from advertised capabilities");
                require(((Number)sink.snapshot().get("rtp_packets")).longValue()==1,"RTP drain");
                require(((Number)sink.snapshot().get("rtcp_packets")).longValue()==1,"RTCP drain");
            }finally{release.countDown();source.join(4000);}
        }
    }
    @org.junit.Test public void validatesFragmentedAndMalformedRtsp() throws Exception { parser(); }
    @org.junit.Test public void negotiatesSessionAndReceivesLoopbackMedia() throws Exception { handshake(); }
}
