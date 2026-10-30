package com.ezra.pricepulse.monitoring;

import com.ezra.pricepulse.common.error.*;
import com.ezra.pricepulse.market.*;
import com.ezra.pricepulse.monitoring.MonitoringModels.Asset;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!standalone & !mq-it")
public class AlpacaAssetClient {
    public interface Transport { JsonNode get(String path)throws Exception; }
    private final Transport transport;
    private final Clock clock;
    @Autowired public AlpacaAssetClient(AlpacaProperties properties,ObjectMapper json,Clock clock){
        this.clock=clock;
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        transport=path->{
            if(properties.getKeyId().isBlank()||properties.getSecretKey().isBlank())throw unavailable();
            String host=switch(properties.getEnvironment()){case "paper"->"paper-api.alpaca.markets";case "live"->"api.alpaca.markets";default->throw unavailable();};
            var request=HttpRequest.newBuilder(URI.create("https://"+host+path)).timeout(Duration.ofSeconds(25))
                    .header("APCA-API-KEY-ID",properties.getKeyId()).header("APCA-API-SECRET-KEY",properties.getSecretKey()).GET().build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()==404)throw new BusinessException(ErrorCode.ASSET_UNAVAILABLE,"股票代码不存在或已停用");
            if(response.statusCode()!=200)throw unavailable();
            return json.readTree(response.body());
        };
    }
    public AlpacaAssetClient(Transport transport,Clock clock){this.transport=transport;this.clock=clock;}
    public List<Asset> catalog(){
        try {
            JsonNode raw=transport.get("/v2/assets?asset_class=us_equity&status=active");
            if(!raw.isArray()||raw.isEmpty()||raw.size()>100000)throw unavailable();
            String revision=UUID.randomUUID().toString();var result=new ArrayList<Asset>();var ids=new HashSet<String>();var symbols=new HashSet<String>();
            for(JsonNode item:raw){Asset a=parse(item,revision);if(!ids.add(a.id())||!symbols.add(a.symbol()))throw unavailable();result.add(a);}
            return List.copyOf(result);
        }catch(BusinessException e){throw e;}catch(Exception ignored){throw unavailable();}
    }
    public Asset get(String id){
        try {String canonical=UUID.fromString(id).toString();if(!canonical.equalsIgnoreCase(id))throw new IllegalArgumentException();
            Asset a=parse(transport.get("/v2/assets/"+canonical),UUID.randomUUID().toString());
            if(!a.id().equals(canonical))throw unavailable();return a;
        }catch(BusinessException e){throw e;}catch(IllegalArgumentException e){throw new BusinessException(ErrorCode.VALIDATION_ERROR,"Invalid asset ID");}
        catch(Exception ignored){throw unavailable();}
    }
    private Asset parse(JsonNode item,String revision){
        String id=UUID.fromString(item.required("id").asText()).toString(),symbol=item.required("symbol").asText(),name=item.required("name").asText();
        String category=item.required("class").asText(),exchange=item.required("exchange").asText(),status=item.required("status").asText();
        if(symbol.isBlank()||symbol.length()>32||name.isBlank()||name.length()>512||!category.equals("us_equity")
                ||exchange.isBlank()||exchange.length()>32||!Set.of("active","inactive").contains(status)||!item.required("tradable").isBoolean())throw unavailable();
        return new Asset(id,symbol,name,category,exchange,status,item.get("tradable").asBoolean(),revision,MarketTime.utc(clock.instant()));
    }
    private static BusinessException unavailable(){return new BusinessException(ErrorCode.ASSET_CATALOG_UNAVAILABLE,"资产目录暂不可用，请稍后重试");}
}
