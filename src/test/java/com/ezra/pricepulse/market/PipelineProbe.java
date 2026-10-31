package com.ezra.pricepulse.market;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.management.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Test-classpath only. All samples stay in memory until the lane has drained. */
final class PipelineProbe {
    static boolean detailed=true;
    static final ThreadLocal<Sample> CURRENT = new ThreadLocal<>();
    static final List<String> PHASES = List.of("stock_lock_wait", "quote_persist", "redis_candidate",
            "mysql_rule_fetch", "rule_decision", "rule_transition", "trigger_outbox_write", "index_maintenance",
            "transaction_body", "transaction_commit", "transaction_total", "quote_process_total", "queue_wait",
            "trade_processing_lag", "connection_acquire");
    static final class Sample implements QuoteTiming.Sink {
        final String key; final int stock, number; final boolean measured; final long receivedNanos, scheduledNanos;
        final Map<String, Long> phases = new HashMap<>(), sqlCounts = new TreeMap<>(), sqlNanos = new TreeMap<>();
        long enqueuedNanos, startedNanos, completedNanos; volatile String discarded;
        AlpacaProtocol.Trade trade; java.time.Instant wallReceivedAt;
        volatile boolean finished, failed; String outcome; int candidates, triggers, rearms; String baselineReason;
        AutoCloseable binding;
        Sample(String key,int stock,int number,boolean measured,long received,long scheduled) {
            this.key=key;this.stock=stock;this.number=number;this.measured=measured;receivedNanos=received;scheduledNanos=scheduled;
        }
        public void record(String phase,long nanos){phases.merge(phase,nanos,Long::sum);}
        void sql(String kind,long nanos){sqlCounts.merge(kind,1L,Long::sum);sqlNanos.merge(kind,nanos,Long::sum);}
    }
    static void record(String name,long start){Sample s=CURRENT.get();if(s!=null)s.record(name,System.nanoTime()-start);}
    @SuppressWarnings("unchecked") static <T> T mapper(Class<T> type,T actual) {
        if(!detailed)return actual;
        return (T) Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
            String phase=switch(type.getSimpleName()+"."+m.getName()) {
                case "StockMapper.findActiveByIdForUpdate" -> "stock_lock_wait";
                case "AlertRuleMapper.findCandidates", "AlertRuleMapper.cooldownIds", "AlertRuleMapper.listIndexable" -> "mysql_rule_fetch";
                case "AlertRuleMapper.transitionByMatcher" -> "rule_transition";
                default -> type.getSimpleName().equals("QuoteEventMapper") ? "quote_persist" : null;
            };
            long start=System.nanoTime();
            try{return m.invoke(actual,a);}catch(InvocationTargetException e){throw e.getCause();}
            finally{if(phase!=null)record(phase,start);}
        });
    }
    @SuppressWarnings("unchecked") static <T> T transactional(T target,PlatformTransactionManager manager,boolean measure) {
        ProxyFactory proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);
        if(measure)proxy.addAdvice((MethodInterceptor) call->{long t=System.nanoTime();try{return call.proceed();}finally{record("transaction_total",t);}});
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        if(measure)proxy.addAdvice((MethodInterceptor) call->{long t=System.nanoTime();try{return call.proceed();}finally{record("transaction_body",t);}});
        return (T)proxy.getProxy();
    }
    static DataSource dataSource(HikariDataSource actual) {
        if(!detailed)return actual;
        return (DataSource)Proxy.newProxyInstance(PipelineProbe.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
            long started=System.nanoTime();
            try {
                Object value=m.invoke(actual,a);
                if(value instanceof Connection c){record("connection_acquire",started);return connection(c);}
                return value;
            }catch(InvocationTargetException e){throw e.getCause();}
        });
    }
    private static Connection connection(Connection actual) {
        return (Connection)Proxy.newProxyInstance(PipelineProbe.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            long start=System.nanoTime();
            try {
                Object value=m.invoke(actual,a);
                if(value instanceof PreparedStatement s && a!=null && a.length>0 && a[0] instanceof String sql)return statement(s,sql);
                return value;
            }catch(InvocationTargetException e){throw e.getCause();}
            finally{if(m.getName().equals("commit"))record("transaction_commit",start);}
        });
    }
    private static PreparedStatement statement(PreparedStatement actual,String sql) {
        String normalized=sql.stripLeading().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
        String operation=normalized.split(" ",2)[0];String table="other";
        var match=java.util.regex.Pattern.compile("(?:^update |^insert into |^delete from |\\bfrom )`?([a-z_]+)").matcher(normalized);
        if(match.find())table=match.group(1);
        String kind=operation+":"+table;
        return (PreparedStatement)Proxy.newProxyInstance(PipelineProbe.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
            long start=System.nanoTime();
            try{return m.invoke(actual,a);}catch(InvocationTargetException e){throw e.getCause();}
            finally{
                Sample s=CURRENT.get();
                if(s!=null && s.measured && m.getName().startsWith("execute"))s.sql(kind,System.nanoTime()-start);
            }
        });
    }
    static Map<String,Object> distribution(Collection<Long> values) {
        if(values.isEmpty())return Map.of("samples",0);
        long[] sorted=values.stream().mapToLong(Long::longValue).sorted().toArray();
        Map<String,Object> result=new LinkedHashMap<>();result.put("samples",sorted.length);
        result.put("meanMs",Arrays.stream(sorted).average().orElse(0)/1e6);
        for(int p:new int[]{50,95,99})result.put("p"+p+"Ms",sorted[(int)Math.ceil(p/100.0*sorted.length)-1]/1e6);
        result.put("maxMs",sorted[sorted.length-1]/1e6);return result;
    }
    static Map<String,Object> jvm() {
        var os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        var gc=new TreeMap<String,Object>();
        for(var g:ManagementFactory.getGarbageCollectorMXBeans())gc.put(g.getName(),Map.of("count",g.getCollectionCount(),"timeMs",g.getCollectionTime()));
        return Map.of("sampledAtNanos",System.nanoTime(),"cpuNanos",os.getProcessCpuTime(),"threads",ManagementFactory.getThreadMXBean().getThreadCount(),
                "heapUsed",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),"gc",gc);
    }
}
