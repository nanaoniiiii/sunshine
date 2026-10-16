package com.alkaidlab.sunshine.service.headless;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Interoperability adapter for the crypto library already shipped on this Samsung phone.
 * Vendor APK/native libraries stay on the user's device and are not distributed with this project. */
public final class PcCrypto {
    private final Class<?> api;
    private static volatile ClassLoader vendorLoader;
    public static synchronized void configure(ClassLoader loader) { vendorLoader=loader; }
    PcCrypto()throws Exception {
        ClassLoader loader=vendorLoader;
        if(loader==null)throw new IllegalStateException("Samsung crypto loader not initialized");
        api=Class.forName("com.sec.android.sidesync.cryptopp.CryptoppNativeManager",true,loader);
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
}
