package com.nextaicommerce.platform.shipping;

import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ShippingLabelCrypto {
    private final byte[] key;
    private final SecureRandom random=new SecureRandom();
    public record Encrypted(byte[] payload,byte[] nonce){}

    public ShippingLabelCrypto(@Value("${app.credentials.encryption-key:}") String encoded){this.key=decode(encoded);}
    public boolean configured(){return key!=null;}

    public Encrypted encrypt(byte[] plain){
        if(key==null)throw new IllegalStateException("Label encryption is not configured.");
        try{
            byte[] nonce=new byte[12];random.nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            return new Encrypted(cipher.doFinal(plain),nonce);
        }catch(Exception ex){throw new IllegalStateException("The shipping label could not be encrypted.",ex);}
    }

    public byte[] decrypt(byte[] payload,byte[] nonce){
        if(key==null)throw new IllegalStateException("Label encryption is not configured.");
        try{
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            return cipher.doFinal(payload);
        }catch(Exception ex){throw new IllegalStateException("The stored shipping label could not be opened.",ex);}
    }

    private static byte[] decode(String encoded){
        if(encoded==null||encoded.isBlank())return null;
        try{byte[] value=Base64.getDecoder().decode(encoded);if(value.length!=32)throw new IllegalArgumentException();return value;}
        catch(IllegalArgumentException ex){throw new IllegalStateException("APP_CREDENTIAL_ENCRYPTION_KEY must be a Base64-encoded 32-byte key.");}
    }
}
