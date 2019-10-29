package pl.net.gazda.webflux;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;
import reactor.retry.Backoff;
import reactor.retry.Retry;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.UUID;

import static java.util.Optional.ofNullable;
import static pl.net.gazda.webflux.ExchangeFilter.RequestId.REQUEST_ID_KEY;
import static reactor.core.publisher.Mono.error;
import static reactor.core.publisher.Mono.just;

@Slf4j
@RestController
@Configuration
public class ExchangeFilter {

    public static class InternalServerException extends Exception {}

    @GetMapping("/web-exchange-filter")
    public Mono<?> exchangeFilter() {

        return WebClient.builder()
                .filter(circuitAndRetryFilter())
                .build()
                .get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("http")
                            .host("localhost")
                            .port(9100)
                            .path("/time2")
                            .build())
                .retrieve()
                .bodyToMono(String.class)
                .log()
                .onErrorResume(throwable -> Mono.subscriberContext()
                        .map(context -> context.get(REQUEST_ID_KEY)))
                ;
    }

    /**
     * Adds a default circuit breaker and retry behavior to all web client requests.
     */
    private static ExchangeFilterFunction circuitAndRetryFilter() {
        CircuitBreaker circuitBreaker = CircuitBreaker.of("name", CircuitBreakerConfig
                .custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .waitDurationInOpenState(Duration.ofMillis(5000))
                .recordExceptions(InternalServerException.class)
                .build());

        Retry<Object> retryPolicy = Retry.anyOf(InternalServerException.class)
                .retryMax(10)
                .backoff(Backoff.fixed(Duration.ofMillis(300)));

        return (request, next) ->
                next.exchange(request)
                    .flatMap(response -> response.statusCode().is5xxServerError() ? error(new InternalServerException()) : just(response))
                    .transform(CircuitBreakerOperator.of(circuitBreaker))
                    .retryWhen(retryPolicy.doOnRetry(context -> log.warn("Retrying: {}", context)));
    }

    /**
     * Extends subscriber context with request id passed in the request header
     */
    @Bean
    public WebFilter requestIdWebFilter() {
        return (exchange, chain) -> {
            HttpHeaders headers = exchange.getRequest().getHeaders();
            RequestId requestId = RequestId.from(headers);

            return chain.filter(exchange).subscriberContext(Context.of(RequestId.class, requestId));
        };
    }

    @Value
    public static class RequestId {
        public static final String REQUEST_ID_KEY = "request-uuid";
        private final String requestId;

        public static RequestId from(HttpHeaders headers) {
            return ofNullable(headers.get(REQUEST_ID_KEY))
                    .filter(requestIdHeaders -> requestIdHeaders.size() > 0 )
                    .map(requestIdHeaders -> requestIdHeaders.get(0))
                    .map(RequestId::new)
                    .orElseGet(RequestId::random);
        }

        public static RequestId random() {
            return new RequestId(UUID.randomUUID().toString());
        }

        @Override
        public String toString() {
            return requestId;
        }
    }


}
