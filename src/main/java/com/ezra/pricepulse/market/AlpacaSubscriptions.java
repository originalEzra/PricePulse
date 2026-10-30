package com.ezra.pricepulse.market;

import java.util.*;

/** Guarded by AlpacaStream's monitor. Alpaca ACKs contain the full set but no request ID. */
final class AlpacaSubscriptions {
    record Command(long id,String action,String symbol,long intent,Set<String> expected){}
    record Failure(long intent,String code){}
    record View(String epoch,boolean authenticated,Map<String,Long> desired,Set<String> actual,Map<String,Long> actualIntents,
            Map<String,Failure> failures,Command pending) {
        boolean confirms(String symbol){return authenticated&&desired.containsKey(symbol)&&actual.contains(symbol)
                &&Objects.equals(actualIntents.get(symbol),desired.get(symbol))
                &&!(pending!=null&&pending.action().equals("unsubscribe")&&pending.symbol().equals(symbol));}
        boolean absent(String symbol){return authenticated&&!desired.containsKey(symbol)&&!actual.contains(symbol)
                &&!(pending!=null&&pending.action().equals("subscribe")&&pending.symbol().equals(symbol));}
        boolean rejectedAbsent(String symbol){return authenticated&&failures.containsKey(symbol)&&!actual.contains(symbol)
                &&!(pending!=null&&pending.action().equals("subscribe")&&pending.symbol().equals(symbol));}
    }
    private Map<String,Long> desired=Map.of();
    private Set<String> actual=Set.of();
    private final Map<String,Long> actualIntents=new HashMap<>();
    private final Map<String,Failure> failures=new HashMap<>();
    private String epoch;
    private boolean authenticated;
    private long sequence;
    private Command pending;
    void desire(Map<String,Long> next){
        failures.entrySet().removeIf(e->!Objects.equals(next.get(e.getKey()),e.getValue().intent()));
        desired=Map.copyOf(next);
    }
    void connect(){epoch=UUID.randomUUID().toString();disconnect();}
    void disconnect(){authenticated=false;actual=Set.of();actualIntents.clear();pending=null;}
    void authenticated(){authenticated=true;}
    Command next(){
        if(!authenticated||pending!=null)return null;
        String remove=actual.stream().filter(s->!desired.containsKey(s)||!Objects.equals(actualIntents.get(s),desired.get(s))).sorted().findFirst().orElse(null);
        if(remove!=null){var expected=new HashSet<>(actual);expected.remove(remove);
            return pending=new Command(++sequence,"unsubscribe",remove,-1,Set.copyOf(expected));}
        String add=desired.keySet().stream().filter(s->!actual.contains(s)
                &&(failures.get(s)==null||failures.get(s).intent()!=desired.get(s))).sorted().findFirst().orElse(null);
        if(add==null)return null;
        var expected=new HashSet<>(actual);expected.add(add);
        return pending=new Command(++sequence,"subscribe",add,desired.get(add),Set.copyOf(expected));
    }
    boolean acknowledge(Set<String> received){
        if(pending!=null&&pending.expected().equals(received)){
            actual=Set.copyOf(received);
            if(pending.action().equals("subscribe"))actualIntents.put(pending.symbol(),pending.intent());
            else actualIntents.remove(pending.symbol());
            pending=null;return true;
        }
        if(actual.equals(received))return false; // Duplicate previous ACK cannot settle a newer command.
        throw new IllegalStateException("SUBSCRIPTION_MISMATCH");
    }
    boolean reject(int code){
        // 405 explicitly preserves the previous set. Other errors do not prove that a mutation failed.
        if(code!=405||pending==null||!pending.action().equals("subscribe"))return false;
        failures.put(pending.symbol(),new Failure(pending.intent(),"UPSTREAM_LIMIT_REACHED"));pending=null;return true;
    }
    boolean rejectUncertain(int code){
        if(!Set.of(400,410,500).contains(code)||pending==null||!pending.action().equals("subscribe"))return false;
        failures.put(pending.symbol(),new Failure(pending.intent(),"UPSTREAM_SUBSCRIPTION_FAILED"));return true;
    }
    View view(){return new View(epoch,authenticated,desired,actual,Map.copyOf(actualIntents),Map.copyOf(failures),pending);}
}
