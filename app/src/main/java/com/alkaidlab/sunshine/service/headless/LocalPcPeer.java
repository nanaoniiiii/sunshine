package com.alkaidlab.sunshine.service.headless;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Local DeX-for-PC peer discovery experiment. TCP is restricted to this device's loopback. */
public final class LocalPcPeer implements AutoCloseable {
    static final String NAME="Sunshine Local DeX", ID="02:53:4e:44:45:58", PREFIX="COM.SEC.ANDROID.APP.DEXONPC";
    final AtomicInteger advertisements=new AtomicInteger(), requests=new AtomicInteger();
    public volatile String stage="created";
    public volatile Exception failure;
    private volatile boolean running;
    private ServerSocket discovery;
    private DatagramSocket udp;
    private Socket discoveryClient,mainClient;
    private PcControlChannel channel;
    private Inet4Address phoneIp;
    private InetAddress broadcast;
    private Thread advertiser,acceptor;

    static byte[] encrypt(String text)throws Exception {
        Cipher aes=Cipher.getInstance("AES/CBC/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec("com.sec.android.".getBytes(StandardCharsets.UTF_8),"AES"),new IvParameterSpec(new byte[16]));
        byte[] seed="com.sec.android.app.dexonpc     ".getBytes(StandardCharsets.UTF_8);
        byte[] key=Base64.getEncoder().encodeToString(aes.doFinal(seed)).substring(0,16).getBytes(StandardCharsets.US_ASCII);
        byte[] plain=text.getBytes(StandardCharsets.UTF_8);
        byte[] padded=Arrays.copyOf(plain,(plain.length+15)/16*16);
        Arrays.fill(padded,plain.length,padded.length,(byte)' ');
        aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(new byte[16]));
        return aes.doFinal(padded);
    }
    static String announcement(String event) {
        return String.join("/&%",PREFIX,event,ID,NAME,"127.0.0.1","15000","2.0.0.21","Windows","PC");
    }
    private void record(String value) { stage=value; }
    public LocalPcPeer() {}
    public void start()throws Exception {
        if(running)throw new IllegalStateException("Already started");
        for(NetworkInterface net:Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if(!net.getName().equals("wlan0"))continue;
            for(InterfaceAddress address:net.getInterfaceAddresses())if(address.getAddress() instanceof Inet4Address&&address.getBroadcast()!=null) {
                phoneIp=(Inet4Address)address.getAddress();broadcast=address.getBroadcast();
            }
        }
        if(phoneIp==null)throw new IOException("No wlan0 IPv4 broadcast address");
        try {
            discovery=new ServerSocket();discovery.bind(new InetSocketAddress("127.0.0.1",51010));discovery.setSoTimeout(500);
            udp=new DatagramSocket(new InetSocketAddress(phoneIp,0));udp.setBroadcast(true);running=true;
            advertiser=new Thread(()->{
                while(running)try {advertise("EVT_NODE_JOIN");Thread.sleep(800);}
                catch(Exception e){if(running){failure=e;record("advertise_failed:"+e.getClass().getSimpleName());}break;}
            },"Local-DeX-PC-Advertise");
            acceptor=new Thread(this::serve,"Local-DeX-PC-Discovery");
            advertiser.setDaemon(true);acceptor.setDaemon(true);advertiser.start();acceptor.start();record("advertising");
        }catch(Exception e){close();throw e;}
    }
    private void advertise(String event)throws Exception {
        byte[] data=encrypt(announcement(event));
        udp.send(new DatagramPacket(data,data.length,broadcast,51007));advertisements.incrementAndGet();
    }
    private void serve() {
        try {
            while(running) {
                try {discoveryClient=discovery.accept();}catch(SocketTimeoutException ignored){continue;}
                if(!discoveryClient.getInetAddress().isLoopbackAddress()){discoveryClient.close();continue;}
                discoveryClient.setSoTimeout(5000);
                byte[] buffer=new byte[4096];int count=discoveryClient.getInputStream().read(buffer);
                if(count<1)continue;
                String request=new String(buffer,0,count,StandardCharsets.UTF_8);
                if(!request.startsWith(PREFIX+"/&%DEXONPC_CONN_REQ/&%")){record("unexpected_discovery_request");continue;}
                requests.incrementAndGet();record("connection_request_received");
                String reply=announcement("DEXONPC_CONN_RESP_OK")+"/&%Manual";
                discoveryClient.getOutputStream().write(reply.getBytes(StandardCharsets.UTF_8));discoveryClient.getOutputStream().flush();
                record("connection_allowed");
                for(int i=0;i<40&&running;i++) {
                    Socket candidate=new Socket();
                    try {
                        candidate.bind(new InetSocketAddress("127.0.0.1",0));
                        candidate.connect(new InetSocketAddress(phoneIp,15000),250);mainClient=candidate;
                        mainClient.setSoTimeout(5000);record("main_channel_connected");break;
                    } catch(IOException e){candidate.close();Thread.sleep(200);}
                }
                if(mainClient==null){record("main_channel_timeout");return;}
                channel=new PcControlChannel(mainClient,this::record);
                channel.authenticate();channel.startDisplay();mainClient.setSoTimeout(1000);channel.pump();return;
            }
        }catch(Exception e){if(running){failure=e;record("discovery_failed:"+e.getClass().getSimpleName()+":"+e.getMessage());}}
    }
    public void close() {
        if(running&&udp!=null)try{advertise("EVT_NODE_LEAVE");}catch(Exception ignored){}
        running=false;
        if(channel!=null)channel.close();
        for(Socket socket:new Socket[]{discoveryClient,mainClient})if(socket!=null)try{socket.close();}catch(IOException ignored){}
        if(discovery!=null)try{discovery.close();}catch(IOException ignored){}
        if(udp!=null)udp.close();
        for(Thread thread:new Thread[]{advertiser,acceptor})if(thread!=null&&thread!=Thread.currentThread()) {
            thread.interrupt();try{thread.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        }
        record("closed");
    }
}
