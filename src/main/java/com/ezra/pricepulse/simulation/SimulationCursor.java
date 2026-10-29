package com.ezra.pricepulse.simulation;

import com.ezra.pricepulse.common.error.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class SimulationCursor {
    private final byte[] secret;
    public record Position(long after,long watermark) {}
    public SimulationCursor(SecretKey key){secret=key.getEncoded();}
    public String encode(long user,String run,String kind,long from,long to,long after,long watermark) {
        String payload=user+"|"+run+"|"+kind+"|"+from+"|"+to+"|"+after+"|"+watermark;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))+"."+sign(payload);
    }
    public Position decode(String value,long user,String run,String kind,long from,long to) {
        try {
            if(value.length()>800)throw new IllegalArgumentException();
            String[] parts=value.split("\\.",-1);if(parts.length!=2)throw new IllegalArgumentException();
            String payload=new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8);
            if(!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8),parts[1].getBytes(StandardCharsets.UTF_8)))throw new IllegalArgumentException();
            String[] fields=payload.split("\\|",-1);
            if(fields.length!=7 || Long.parseLong(fields[0])!=user || !fields[1].equals(run) || !fields[2].equals(kind)
                    || Long.parseLong(fields[3])!=from || Long.parseLong(fields[4])!=to)throw new IllegalArgumentException();
            long after=Long.parseLong(fields[5]),watermark=Long.parseLong(fields[6]);
            if(after<0 || watermark<after)throw new IllegalArgumentException();
            return new Position(after,watermark);
        }catch(RuntimeException ignored){throw new BusinessException(ErrorCode.VALIDATION_ERROR,"Invalid simulation cursor or range");}
    }
    private String sign(String text) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        }catch(GeneralSecurityException e){throw new IllegalStateException("Cannot sign simulation cursor");}
    }
}
