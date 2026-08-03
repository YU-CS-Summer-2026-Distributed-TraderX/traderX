package finos.traderx.algoengine.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** research.md Decision 3: reference price for deriving each child order's limit price. */
@Component
public class PriceClient {
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final String priceServiceUrl;
  private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private final ObjectMapper mapper = new ObjectMapper();

  public PriceClient(@Value("${price.service.url}") String priceServiceUrl) {
    this.priceServiceUrl = priceServiceUrl;
  }

  public BigDecimal currentPrice(String ticker) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(priceServiceUrl + "/prices/" + ticker))
        .timeout(TIMEOUT)
        .header("Accept", "application/json")
        .GET()
        .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException("price fetch for " + ticker + " -> HTTP " + response.statusCode());
    }
    JsonNode body = mapper.readTree(response.body());
    return new BigDecimal(body.get("price").asText());
  }
}
