import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Interoperability adapter for the crypto library already shipped on this Samsung phone.
 * Vendor APK/native libraries stay on the user's device and are not distributed with this project. */
final class PcCrypto {
    private final Class<?> api;
    PcCrypto()throws Exception {
        api=Class.forName("com.sec.android.sidesync.cryptopp.CryptoppNativeManager");
        System.load("/data/local/tmp/headless-dex-libs/libc++_shared.so");
        System.load("/data/local/tmp/headless-dex-libs/libCryptoppManager.so");
        invoke("setDebugMode",new Class<?>[]{int.class},0);
    }
    Object invoke(String name,Class<?>[] types,Object...args)throws Exception {
        try{return api.getMethod(name,types).invoke(null,args);}
        catch(InvocationTargetException e){Throwable t=e.getCause();if(t instanceof Exception)throw (Exception)t;throw new IllegalStateException(t);}
    }
    Object pair()throws Exception{return invoke("generateKeyPair",new Class<?>[0]);}
    byte[] field(Object value,String name)throws Exception{return (byte[])value.getClass().getField(name).get(value);}
    byte[] shared(byte[] peer,byte[] privateKey)throws Exception{
        Object value=invoke("retrieveSharedSecret",new Class<?>[]{byte[].class,byte[].class},peer,privateKey);
        return field(value,"sharedSecret");
    }
    byte[] hash(byte[] value)throws Exception{return (byte[])invoke("SHA256",new Class<?>[]{byte[].class},value);}
    byte[] mac(byte[] secret,String text)throws Exception{return (byte[])invoke("EncryptorWithMAC",new Class<?>[]{byte[].class,String.class},secret,text);}
    boolean verify(byte[] secret,byte[] mac,String text)throws Exception{return (boolean)invoke("MacVerification",new Class<?>[]{byte[].class,byte[].class,String.class},secret,mac,text);}
    byte[] key(byte[] secret,String ids)throws Exception{return (byte[])invoke("SessionKey",new Class<?>[]{byte[].class,String.class},secret,ids);}
    byte[] encrypt(byte[] key,String text)throws Exception{return (byte[])invoke("cbcEncryption",new Class<?>[]{byte[].class,String.class},key,text);}
    String decrypt(byte[] key,byte[] value)throws Exception{return new String((byte[])invoke("cbcDecryption",new Class<?>[]{byte[].class,byte[].class},key,value),StandardCharsets.UTF_8);}
    public static void main(String[] args)throws Exception {
        PcCrypto crypto=new PcCrypto();Object a=crypto.pair(),b=crypto.pair();
        byte[] as=crypto.shared(crypto.field(b,"publicKey"),crypto.field(a,"privateKey"));
        byte[] bs=crypto.shared(crypto.field(a,"publicKey"),crypto.field(b,"privateKey"));
        if(!Arrays.equals(as,bs))throw new AssertionError("ECDH mismatch");
        String sample="Local DeX protocol self test";
        if(!crypto.verify(bs,crypto.mac(as,sample),sample))throw new AssertionError("MAC mismatch");
        byte[] ak=crypto.key(as,"local-peer-test"),bk=crypto.key(bs,"local-peer-test");
        if(!Arrays.equals(ak,bk)||!crypto.decrypt(bk,crypto.encrypt(ak,sample)).equals(sample))throw new AssertionError("Session encryption mismatch");
        System.out.println("PASS: on-device vendor-compatible ECDH, MAC, session key and encryption; no secrets logged");
    }
}
