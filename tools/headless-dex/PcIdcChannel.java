import java.io.*;
import java.net.*;
import java.util.Base64;
import java.util.function.Consumer;

/** Minimal local inter-device control channel after the DeX PC session authenticates. */
final class PcIdcChannel implements AutoCloseable {
    private final PcCrypto crypto;
    private final byte[] key;
    private final Consumer<String> status;
    private ServerSocket server;
    private volatile boolean running;
    private Thread reader;
    private int sequence=1,phoneControlPort;
    PcIdcChannel(PcCrypto crypto,byte[] key,Consumer<String> status){this.crypto=crypto;this.key=key;this.status=status;}
    private String packet(String main,String sub,String type,String info){return info.length()+" "+(sequence++)+" "+main+" "+sub+" "+type+" "+info;}
    private String decode(String value)throws Exception {
        if(value.contains(" ALIVE_CHECK "))return value;
        return crypto.decrypt(key,Base64.getDecoder().decode(value));
    }
    private void write(DataOutputStream out,String text)throws Exception {
        out.writeUTF(Base64.getEncoder().encodeToString(crypto.encrypt(key,text)));out.flush();
    }
    private String request(int port,String main,String sub,String kind,String info,boolean reply)throws Exception {
        try(Socket socket=new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1",port),1000);socket.setSoTimeout(4000);
            write(new DataOutputStream(socket.getOutputStream()),packet(main,sub,kind,info));
            return reply?decode(new DataInputStream(socket.getInputStream()).readUTF()):"";
        }
    }
    void start()throws Exception {
        server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));server.setSoTimeout(500);running=true;
        reader=new Thread(()->{
            while(running)try(Socket socket=server.accept()) {
                socket.setSoTimeout(2000);String value=decode(new DataInputStream(socket.getInputStream()).readUTF());
                String[] fields=value.split(" ",6);
                if(fields.length<5)continue;
                // Record command names only; never clipboard, notification or key payloads.
                status.accept("idc_in:"+fields[2]+":"+fields[3]+":"+fields[4]);
                if(fields[4].equals("REQUEST"))write(new DataOutputStream(socket.getOutputStream()),packet(fields[2],fields[3],"RESPONSE","true"));
            }catch(SocketTimeoutException ignored){}catch(Exception e){if(running)status.accept("idc_in_error:"+e.getClass().getSimpleName());}
        },"Local-DeX-PC-IDC");reader.setDaemon(true);reader.start();
        IOException last=null;
        for(int i=0;i<30;i++)try {
            String info=server.getLocalPort()+"/&%"+LocalPcPeer.ID+"/&%"+LocalPcPeer.ID+"/&%false/&%1920x1080/&%PC/&%WIFI/&%"+LocalPcPeer.NAME;
            String response=request(7246,"CONNECT","SIDESYNC_CONN","REQUEST",info,true);
            String[] parts=response.split(" ",6);
            if(parts.length!=6)throw new IOException("Invalid IDC connection response");
            phoneControlPort=Integer.parseInt(parts[5].split("/&%",2)[0]);
            if(phoneControlPort<1||phoneControlPort>65535)throw new IOException("Invalid IDC control port");
            status.accept("idc_connected");
            request(7246,"CONNECT","IDC_SERVER_LISTEN","EVENT","ready",false);
            Thread.sleep(200);status.accept("idc_server_ready");return;
        }catch(IOException e){last=e;Thread.sleep(100);}
        throw new IOException("No source IDC connection server",last);
    }
    void enableScreen()throws Exception {
        request(phoneControlPort,"SCR_SHR","ENABLE_SCR_SHR","REQUEST","true",false);
        status.accept("idc_screen_enable_sent");
    }
    public void close() {
        running=false;if(server!=null)try{server.close();}catch(IOException ignored){}
        if(reader!=null){reader.interrupt();try{reader.join(1200);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    }
}
