package com.ezra.pricepulse.monitoring;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ezra.pricepulse.common.error.BusinessException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AlpacaAssetClientTest {
    final ObjectMapper json=new ObjectMapper();
    final String id="80e04a93-e1a8-4503-ab61-2ef1992650b4";
    String asset(String symbol,String name,String status){return "{\"id\":\""+id+"\",\"symbol\":\""+symbol+"\",\"name\":\""+name+"\",\"class\":\"us_equity\",\"exchange\":\"NASDAQ\",\"status\":\""+status+"\",\"tradable\":true}";}
    @Test void importsDirectoryOutsideLegacyUniverseAndPreservesLongNamesAndProviderType() {
        var client=new AlpacaAssetClient(path->json.readTree("["+asset("PLTR","Name".repeat(60),"active")+"]"),Clock.systemUTC());
        var values=client.catalog();assertThat(values).hasSize(1);assertThat(values.getFirst().name()).hasSize(240);
        assertThat(values.getFirst().symbol()).isEqualTo("PLTR");assertThat(values.getFirst().assetClass()).isEqualTo("us_equity");
    }
    @Test void duplicateOrEmptyCatalogCannotReplaceAnExistingSnapshot() {
        var empty=new AlpacaAssetClient(path->json.readTree("[]"),Clock.systemUTC());
        assertThatThrownBy(empty::catalog).isInstanceOf(BusinessException.class);
        var duplicate=new AlpacaAssetClient(path->json.readTree("["+asset("PLTR","Palantir","active")+","+asset("PLTR","Palantir","active")+"]"),Clock.systemUTC());
        assertThatThrownBy(duplicate::catalog).isInstanceOf(BusinessException.class);
    }
    @Test void providerFailuresNeverExposeResponseOrCredentials() {
        var client=new AlpacaAssetClient(path->{throw new IllegalStateException("private-provider-secret");},Clock.systemUTC());
        assertThatThrownBy(client::catalog).isInstanceOf(BusinessException.class).hasMessageNotContaining("private-provider-secret");
    }
    @Test void lookupValidatesIdentityAndPreservesInactiveStatusForTheCaller() {
        var paths=new ArrayList<String>();var client=new AlpacaAssetClient(path->{paths.add(path);return json.readTree(asset("PLTR","Palantir","inactive"));},Clock.systemUTC());
        assertThat(client.get(id).status()).isEqualTo("inactive");assertThat(paths).containsExactly("/v2/assets/"+id);
        assertThatThrownBy(()->client.get("https://untrusted.invalid/key")).isInstanceOf(BusinessException.class);assertThat(paths).hasSize(1);
    }
}
