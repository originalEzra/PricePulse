package com.ezra.pricepulse.market;

import com.ezra.pricepulse.common.error.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Cursor binds owner, stock, range and committed query watermark; user input cannot lift the watermark. */
@Component
public class TimelineCursor {
    private final byte[] secret;
    public TimelineCursor(SecretKey key) { secret=key.getEncoded(); }
    public record Position(long after,long watermark) {}
    public String encode(long user,long stock,long from,long to,long after,long watermark) {
        String payload=user+":"+stock+":"+from+":"+to+":"+after+":"+watermark;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))+"."+sign(payload);
    }
    public Position decode(String value,long user,long stock,long from,long to) {
        try {
            if (value.length()>700) throw new IllegalArgumentException();
            String[] parts=value.split("\\.",-1);
            if(parts.length!=2)throw new IllegalArgumentException();
            String payload=new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8);
            if(!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8),parts[1].getBytes(StandardCharsets.UTF_8)))throw new IllegalArgumentException();
            String[] p=payload.split(":");
            if(p.length!=6 || Long.parseLong(p[0])!=user || Long.parseLong(p[1])!=stock
                    || Long.parseLong(p[2])!=from || Long.parseLong(p[3])!=to)throw new IllegalArgumentException();
            long after=Long.parseLong(p[4]),watermark=Long.parseLong(p[5]);
            if(after<0 || watermark<after)throw new IllegalArgumentException();
            return new Position(after,watermark);
        } catch(RuntimeException ignored) { throw new BusinessException(ErrorCode.VALIDATION_ERROR,"Invalid timeline cursor or query range"); }
    }
    private String sign(String payload) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        }catch(java.security.GeneralSecurityException impossible){throw new IllegalStateException(impossible);}
    }
}
