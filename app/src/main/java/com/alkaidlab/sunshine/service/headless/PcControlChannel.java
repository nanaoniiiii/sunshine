package com.alkaidlab.sunshine.service.headless;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Authenticated control connection to this phone's existing DeX-for-PC server. */
final class PcControlChannel implements AutoCloseable {
    private final Socket socket;
    private final PcCrypto crypto;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final Consumer<String> status;
    private byte[] sessionKey;
    private int nextSid=1;
    private int receivedSid;
    private LocalWfdSink wfd;
    private PcIdcChannel idc;
    volatile boolean running=true;
    PcControlChannel(Socket socket,Consumer<String> status)throws Exception {
        this.socket=socket;this.status=status;crypto=new PcCrypto();
        input=new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        output=new DataOutputStream(socket.getOutputStream());
        socket.setTcpNoDelay(true);status.accept("control_crypto_ready");
    }
    private void send(String type,String message,JSONObject parameters)throws Exception {
        JSONObject value=new JSONObject().put("TYPE",type).put("MSG",message).put("PARAM",parameters);
        byte[] bytes=type.equals("AUTH")?value.toString().getBytes(StandardCharsets.UTF_8):crypto.encrypt(sessionKey,value.toString());
        writeFrame(bytes,nextSid++);
    }
    private void writeFrame(byte[] bytes,int sid)throws IOException {
        ByteArrayOutputStream packet=new ByteArrayOutputStream(bytes.length+8);
        DataOutputStream framing=new DataOutputStream(packet);
        framing.writeInt(bytes.length);framing.writeInt(sid);framing.write(bytes);
        output.write(packet.toByteArray());output.flush();
    }
    private JSONObject read()throws Exception {
        int length=input.readInt();receivedSid=input.readInt();
        if(length<1||length>131072)throw new IOException("Invalid DeX control frame length");
        byte[] bytes=new byte[length];input.readFully(bytes);
        String text=bytes[0]=='{'?new String(bytes,StandardCharsets.UTF_8):crypto.decrypt(sessionKey,bytes);
        return new JSONObject(text);
    }
    private JSONObject response(String type,String message)throws Exception {
        for(int i=0;i<8;i++) {
            JSONObject value=read();
            if(value.optString("TYPE").equals(type)&&value.optString("MSG").equals(message))return value;
        }
        throw new IOException("Expected "+type+" "+message);
    }
    void authenticate()throws Exception {
        Object pair=crypto.pair();
        byte[] ownPublic=crypto.field(pair,"publicKey"),privateKey=crypto.field(pair,"privateKey");
        String ownPublic64=Base64.getEncoder().encodeToString(ownPublic);
        send("AUTH","SEQ1",new JSONObject().put("MAC",LocalPcPeer.ID));
        status.accept("auth_seq1_sent");
        JSONObject seq1=response("AUTH","SEQ1").getJSONObject("PARAM");
        String phoneId=seq1.getString("UID"),phoneHash=seq1.getString("HPubKey");
        status.accept("auth_seq1_complete");
        send("AUTH","SEQ2",new JSONObject().put("PubKey",ownPublic64));
        JSONObject seq2=response("AUTH","SEQ2").getJSONObject("PARAM");
        String phonePublic64=seq2.getString("PubKey");byte[] phonePublic=Base64.getDecoder().decode(phonePublic64);
        if(!MessageDigest.isEqual(crypto.hash(phonePublic),Base64.getDecoder().decode(phoneHash)))throw new IOException("Phone public key hash mismatch");
        byte[] secret=crypto.shared(phonePublic,privateKey);
        if(!crypto.verify(secret,Base64.getDecoder().decode(seq2.getString("HMAC")),ownPublic64+phonePublic64+LocalPcPeer.ID+phoneId))
            throw new IOException("Phone session MAC mismatch");
        status.accept("auth_seq2_verified");
        byte[] proof=crypto.mac(secret,phonePublic64+ownPublic64+phoneId+LocalPcPeer.ID);
        send("AUTH","SEQ3",new JSONObject().put("HMAC",Base64.getEncoder().encodeToString(proof)));
        if(!response("AUTH","SEQ3").getJSONObject("PARAM").getBoolean("Verified"))throw new IOException("Phone rejected session authentication");
        sessionKey=crypto.key(secret,LocalPcPeer.ID+phoneId);
        java.util.Arrays.fill(privateKey,(byte)0);java.util.Arrays.fill(secret,(byte)0);
        status.accept("session_authenticated");
    }
    void startDisplay()throws Exception {
        JSONObject terminal=new JSONObject().put("SinkMAC",LocalPcPeer.ID).put("SinkName",LocalPcPeer.NAME)
                .put("SinkIP","127.0.0.1").put("ConnectedSourceIP","127.0.0.1")
                .put("SinkType","Windows").put("PcVer","2.0.0.21").put("SinkApkVersion","2.0.0.21")
                .put("KmsVer","2.0.0").put("SinkOSVersion","10.0").put("SinkUIRatio","100.0")
                .put("SinkIDCConnectBeforehand",false).put("SinkAutoStartStatus",false)
                .put("SinkNotificationStatus",false).put("SinkCmdAsCtrlStatus",false)
                .put("WifiConnectivity","AP");
        send("REQ","TerminalInfo",terminal);
        JSONObject reply=response("RESP","TerminalInfo");
        if(!reply.optBoolean("RESULT"))throw new IOException("TerminalInfo rejected");
        status.accept("terminal_info_accepted");
        send("REQ","ChangePSSState",new JSONObject().put("PSS_STATE",1).put("PSS_MODE",0));
        status.accept("display_start_requested");
        response("RESP","ChangePSSState");
        idc=new PcIdcChannel(crypto,sessionKey,status);idc.start();
        wfd=new LocalWfdSink();wfd.start();idc.enableScreen();
    }
    void pump()throws Exception {
        while(running&&!socket.isClosed()) {
            send("REQ","CheckConnect",new JSONObject());
            // Wait only between complete frames. A timeout inside a frame must end the
            // connection, otherwise its remaining bytes become a bogus length header.
            if(input.available()>0) {
                JSONObject message=read();
                String type=message.optString("TYPE"),method=message.optString("MSG");
                if(type.equals("REQ")&&method.equals("CheckConnect")) {
                    JSONObject value=new JSONObject().put("TYPE","RESP").put("MSG",method).put("PARAM",new JSONObject()).put("RESULT",true);
                    writeFrame(crypto.encrypt(sessionKey,value.toString()),receivedSid);
                }
            }
            if(wfd!=null)status.accept("wfd_status:"+new JSONObject(wfd.snapshot()).toString());
            Thread.sleep(900);
        }
    }
    public void close() {
        running=false;if(wfd!=null)wfd.close();if(idc!=null)idc.close();
        try{socket.close();}catch(IOException ignored){}
        if(sessionKey!=null)java.util.Arrays.fill(sessionKey,(byte)0);
    }
}
