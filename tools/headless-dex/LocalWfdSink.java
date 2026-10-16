import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Minimal WFD RTSP receiver for an isolated loopback experiment. Does not decode video. */
final class LocalWfdSink implements AutoCloseable {
    static final int MAX_HEADER = 16 * 1024, MAX_BODY = 64 * 1024;
    static final class Message {
        final String firstLine, body;
        final Map<String, String> headers;
        Message(String line, Map<String, String> fields, String text) { firstLine=line; headers=fields; body=text; }
        String header(String name) { return headers.getOrDefault(name.toLowerCase(Locale.ROOT), ""); }
    }
    static Message readMessage(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int tail=0;
        while (tail != 0x0d0a0d0a) {
            int b=input.read();
            if (b<0) throw new EOFException("RTSP peer closed");
            bytes.write(b); tail=(tail<<8)|b;
            if (bytes.size()>MAX_HEADER) throw new IOException("RTSP header too large");
        }
        String[] lines=new String(bytes.toByteArray(),StandardCharsets.US_ASCII).split("\r\n");
        if (lines.length==0 || (!lines[0].startsWith("RTSP/1.0 ") && !lines[0].endsWith(" RTSP/1.0")))
            throw new IOException("Invalid RTSP first line");
        Map<String,String> fields=new LinkedHashMap<>();
        for(int i=1;i<lines.length;i++) {
            int colon=lines[i].indexOf(':');
            if(colon<=0)throw new IOException("Invalid RTSP header");
            String key=lines[i].substring(0,colon).trim().toLowerCase(Locale.ROOT);
            if(fields.put(key,lines[i].substring(colon+1).trim())!=null)throw new IOException("Duplicate RTSP header");
        }
        int length;
        try {length=Integer.parseInt(fields.getOrDefault("content-length","0"));}
        catch(NumberFormatException e){throw new IOException("Invalid RTSP Content-Length",e);}
        if(length<0||length>MAX_BODY)throw new IOException("RTSP body too large");
        byte[] body=new byte[length];
        for(int p=0;p<length;) {int n=input.read(body,p,length-p);if(n<0)throw new EOFException("Truncated RTSP body");p+=n;}
        return new Message(lines[0],fields,new String(body,StandardCharsets.UTF_8));
    }
    static String capabilities(String query,int rtpPort) {
        Map<String,String> values=new LinkedHashMap<>();
        // H.264 high profile, level 3.2, CEA 720p30; one AAC stereo mode; no HDCP.
        values.put("wfd_video_formats","28 00 02 02 00000020 00000000 00000000 00 0000 0000 00 none none");
        values.put("wfd_audio_codecs","AAC 00000001 00");
        values.put("wfd_client_rtp_ports","RTP/AVP/UDP;unicast "+rtpPort+" 0 mode=play");
        values.put("wfd_content_protection","none");
        values.put("wfd_display_edid","none");
        values.put("wfd_coupled_sink","00 none");
        values.put("wfd_connector_type","05");
        values.put("wfd_uibc_capability","none");
        StringBuilder result=new StringBuilder();
        for(String line:query.split("\r?\n")) {
            String key=line.trim().split("[:\\s]",2)[0];
            if(values.containsKey(key))result.append(key).append(": ").append(values.get(key)).append("\r\n");
        }
        return result.toString();
    }

    private final int controlPort;
    private final AtomicLong rtpPackets=new AtomicLong(), rtcpPackets=new AtomicLong();
    volatile Exception failure;
    private volatile boolean running;
    private volatile int messages, plays;
    private volatile String lastRequest="";
    private Socket control;
    private DatagramSocket rtp,rtcp;
    private Thread controlThread,rtpThread,rtcpThread;
    private OutputStream output;
    private int sequence=100,setupSequence=-1;
    private String session="",uri="rtsp://127.0.0.1/wfd1.0/streamid=0";
    private boolean sentOptions;
    private long lastPingNs;
    LocalWfdSink(){this(7236);}
    LocalWfdSink(int port){controlPort=port;}
    synchronized void start()throws IOException {
        if(running)throw new IllegalStateException("Already running");
        InetAddress loop=InetAddress.getByName("127.0.0.1");
        try {
            // Reserve an adjacent pair without changing another process's socket options.
            for(int i=0;i<30;i++) {
                DatagramSocket candidate=new DatagramSocket(new InetSocketAddress(loop,0));
                int port=candidate.getLocalPort();candidate.close();port&=~1;
                try {rtp=new DatagramSocket(new InetSocketAddress(loop,port));rtcp=new DatagramSocket(new InetSocketAddress(loop,port+1));break;}
                catch(SocketException e){if(rtp!=null)rtp.close();rtp=null;if(i==29)throw e;}
            }
            rtp.setSoTimeout(500);rtcp.setSoTimeout(500);running=true;
            rtpThread=drain(rtp,rtpPackets,"HeadlessDex-RTP");rtcpThread=drain(rtcp,rtcpPackets,"HeadlessDex-RTCP");
            controlThread=new Thread(this::run,"HeadlessDex-RTSP");controlThread.setDaemon(true);controlThread.start();
        } catch(IOException e){close();throw e;}
    }
    private Thread drain(DatagramSocket socket,AtomicLong counter,String name) {
        Thread thread=new Thread(()->{
            byte[] data=new byte[65535];
            while(running)try {DatagramPacket p=new DatagramPacket(data,data.length);socket.receive(p);if(p.getAddress().isLoopbackAddress())counter.incrementAndGet();}
            catch(SocketTimeoutException ignored){}catch(IOException e){if(running)failure=e;break;}
        },name);thread.setDaemon(true);thread.start();return thread;
    }
    private void run() {
        try {
            IOException last=null;
            for(int i=0;i<20&&running;i++) {
                Socket candidate=new Socket();
                try {candidate.connect(new InetSocketAddress("127.0.0.1",controlPort),250);control=candidate;break;}
                catch(IOException e){last=e;candidate.close();Thread.sleep(250);}
            }
            if(!running)return;
            if(control==null)throw new IOException("No loopback WFD listener",last);
            control.setSoTimeout(2500);output=control.getOutputStream();
            InputStream input=new BufferedInputStream(control.getInputStream());
            while(running) {
                if(!session.isEmpty()&&System.nanoTime()-lastPingNs>4000000000L){request("GET_PARAMETER","");lastPingNs=System.nanoTime();}
                // Poll before parsing so an idle timeout never discards a partially parsed message.
                if(input.available()==0){Thread.sleep(20);continue;}
                handle(readMessage(input));messages++;
            }
        }catch(Exception e){if(running)failure=e;}
    }
    private void send(String first,String headers,String body)throws IOException {
        byte[] payload=body.getBytes(StandardCharsets.UTF_8);
        String head=first+"\r\n"+headers+(payload.length>0?"Content-Type: text/parameters\r\nContent-Length: "+payload.length+"\r\n":"")+"\r\n";
        output.write(head.getBytes(StandardCharsets.US_ASCII));output.write(payload);output.flush();
    }
    private int request(String method,String extra)throws IOException {
        int cseq=sequence++;
        send(method+" "+(method.equals("OPTIONS")?"*":uri)+" RTSP/1.0","CSeq: "+cseq+"\r\n"+(session.isEmpty()?"":"Session: "+session+"\r\n")+extra,"");
        return cseq;
    }
    private void handle(Message message)throws IOException {
        lastRequest=message.firstLine;
        if(message.firstLine.startsWith("RTSP/1.0 ")) {
            String[] fields=message.firstLine.split(" ",3);
            if(fields.length<2||!fields[1].equals("200"))throw new IOException("WFD peer returned "+message.firstLine);
            if(message.header("cseq").equals(String.valueOf(setupSequence))) {
                session=message.header("session").split(";",2)[0].trim();
                if(session.isEmpty())throw new IOException("SETUP response missing Session");
                request("PLAY","");plays++;
                lastPingNs=System.nanoTime();
            }
            return;
        }
        String method=message.firstLine.split(" ",2)[0],cseq=message.header("cseq");
        if(cseq.isEmpty())throw new IOException("Request missing CSeq");
        if(method.equals("OPTIONS")) {
            send("RTSP/1.0 200 OK","CSeq: "+cseq+"\r\nPublic: org.wfa.wfd1.0, OPTIONS, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, TEARDOWN\r\n","");
            if(!sentOptions){sentOptions=true;request("OPTIONS","Require: org.wfa.wfd1.0\r\n");}
        } else if(method.equals("GET_PARAMETER")) {
            send("RTSP/1.0 200 OK","CSeq: "+cseq+"\r\n"+(session.isEmpty()?"":"Session: "+session+"\r\n"),capabilities(message.body,rtp.getLocalPort()));
        } else if(method.equals("SET_PARAMETER")) {
            String trigger="";
            for(String line:message.body.split("\r?\n")) {
                if(line.startsWith("wfd_presentation_URL:"))uri=line.substring(line.indexOf(':')+1).trim().split(" ",2)[0];
                if(line.startsWith("wfd_trigger_method:"))trigger=line.substring(line.indexOf(':')+1).trim();
            }
            send("RTSP/1.0 200 OK","CSeq: "+cseq+"\r\n"+(session.isEmpty()?"":"Session: "+session+"\r\n"),"");
            if(trigger.equals("SETUP")) setupSequence=request("SETUP","Transport: RTP/AVP/UDP;unicast;client_port="+rtp.getLocalPort()+"-"+rtcp.getLocalPort()+"\r\n");
            if(trigger.equals("TEARDOWN")){request("TEARDOWN","");running=false;}
        } else send("RTSP/1.0 405 Method Not Allowed","CSeq: "+cseq+"\r\n","");
    }
    Map<String,Object> snapshot() {
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("messages",messages);value.put("play_requests",plays);value.put("rtp_packets",rtpPackets.get());
        value.put("rtcp_packets",rtcpPackets.get());value.put("video_decoded",false);
        value.put("last_message",lastRequest);
        if(failure!=null)value.put("error",failure.toString());return value;
    }
    public synchronized void close() {
        running=false;
        if(control!=null)try{control.close();}catch(IOException ignored){}
        if(rtp!=null)rtp.close();if(rtcp!=null)rtcp.close();
        for(Thread thread:new Thread[]{controlThread,rtpThread,rtcpThread})if(thread!=null&&thread!=Thread.currentThread())
            try{thread.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
}
