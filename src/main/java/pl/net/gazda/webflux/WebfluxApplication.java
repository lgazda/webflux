package pl.net.gazda.webflux;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.netflix.ribbon.RibbonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.client.WebClient;
import pl.net.gazda.webflux.WebfluxApplication.MultiDataResult.MultiDataResultBuilder;
import pl.net.gazda.webflux.WebfluxApplication.MultiDataResult.NumericData;
import pl.net.gazda.webflux.WebfluxApplication.MultiDataResult.TimeData;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Date;

import static java.time.Duration.ofSeconds;
import static java.util.Collections.nCopies;

@Slf4j
@Configuration
@EnableWebFlux
@RestController
@SpringBootApplication
@RibbonClient(name = "wiremock")
public class WebfluxApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebfluxApplication.class, args);

    }

    @Bean
    @LoadBalanced
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    /**
     * Uses {@link MultiDataResultBuilder} to combine (zip) results with from multiple publishers which returns a result with a different type.
     * First we create a Mono<MultiDataResultBuilder> and then each zip operation returns the instance of the builder. Thanks to this we can zip multiple results (types).
     * At the end we are building and returning a final result.
     * @return Mono multidata result build from 2 separate calls to external services.
     */
    @GetMapping("/mono-zip")
    public Mono<MultiDataResult> mono() {
        return Mono.just(MultiDataResult.builder())
                .zipWith(wireMockGetRequest("/numeric").bodyToMono(NumericData.class), MultiDataResultBuilder::number)
                .zipWith(wireMockGetRequest("/time").bodyToMono(TimeData.class), MultiDataResultBuilder::time)
                .map(MultiDataResultBuilder::build);
    }

    /**
     * Sends 50 requests for time data resource. At the same time only 10 concurrent requests are being sent and processed.
     * This may simulate a situation where we want to limit processing due to our or resource server capacity.
     * @return Flux<TimeData> build from 50 requests
     */
    @GetMapping("/flux-flatmap")
    public Flux<?> fluxFlatMap() {
        return Flux.fromIterable(nCopies(50, "/time"))
                .flatMap(url -> wireMockGetRequest(url).bodyToMono(TimeData.class), 10);
    }

    /**
     * Merges Mono results from 4 different publishers. All publishers return the same {@link NumericData} result so can be merged to the same Flux.
     * Two on the given publishers fails. For one a default value is being return for second an empty mono is returned due to error so the result is skipped.
     * @return Flux<NumericData>
     */
    @GetMapping("/flux-merge")
    public Flux<NumericData> flux() {
        return Flux.merge(
                wireMockGetRequest("/numeric").bodyToMono(NumericData.class),
                wireMockGetRequest("/numeric").bodyToMono(NumericData.class),
                wireMockGetRequest("/400").bodyToMono(NumericData.class)
                        .onErrorReturn(NumericData.ZERO),
                wireMockGetRequest("/unknown").bodyToMono(NumericData.class).onErrorResume(throwable -> Mono.empty())
        );
    }

    /**
     * Tries to retry 10 times but only 5 are allowed due to circuit breaker configuration.
     * Web client response is being inspected for 5xx status, if such status is returned a custom {@link ServerException} is being propagated so circuit breaker can correctly react
     * on on this kind of problem.
     * @return
     */
    @GetMapping("/retry-circuit")
    public Mono<?> retryCircuit() {
        CircuitBreaker circuitBreaker = CircuitBreaker.of("name", CircuitBreakerConfig
                .custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .waitDurationInOpenState(Duration.ofMillis(1000))
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordExceptions(ServerException.class)
                .build());

        return wireMockGetRequest("/400")
                .onStatus(HttpStatus::is5xxServerError, clientResponse -> Mono.error(new ServerException()))
                .bodyToMono(String.class)
                .transform(CircuitBreakerOperator.of(circuitBreaker))
                .retry(10);
    }

    private static class ServerException extends Exception {}

    /**
     * Simple example of timeout and retry. Request is being retried 3 times until the call responses in 1 second.
     * @return Mono result if the request returns in 1 second
     */
    @GetMapping("/mono-retry-timeout")
    public Mono<?> monoTimeout() {
        return  wireMockGetRequest("/delayed")
                .bodyToMono(String.class)
                .timeout(ofSeconds(1))
                .retry(3);
    }

    private WebClient.ResponseSpec wireMockGetRequest(String path) {
        return webClientBuilder().build()
                .get()
                .uri(uriBuilder -> uriBuilder.scheme("http").host("wiremock").path(path).build())
                .retrieve();
    }

    @Value
    @Builder
    public static class MultiDataResult {
        private final TimeData time;
        private final NumericData number;

        @Data
        @NoArgsConstructor
        public static class TimeData {
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
            private Date time;
        }

        @Data
        @NoArgsConstructor
        @Builder
        @AllArgsConstructor
        public static class NumericData {
            private Number number;

            public static final NumericData ZERO = NumericData.builder().number(0).build();
        }
    }
}

