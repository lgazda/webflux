package pl.net.gazda.webflux;

import com.fasterxml.jackson.annotation.JsonFormat;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.client.WebClient;
import pl.net.gazda.webflux.WebfluxApplication.MultiDataResult.MultiDataResultBuilder;
import pl.net.gazda.webflux.WebfluxApplication.MultiDataResult.NumericData;
import pl.net.gazda.webflux.WebfluxApplication.MultiDataResult.TimeData;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import static java.time.Duration.ofSeconds;
import static java.util.Collections.nCopies;

@SpringBootApplication
@Configuration
@EnableWebFlux
@RestController
@RibbonClient(name = "wiremock")
@Slf4j
public class WebfluxApplication {

	public static void main(String[] args) {
		SpringApplication.run(WebfluxApplication.class, args);

	}

	@Bean
	@LoadBalanced
	public WebClient.Builder webClientBuilder() {
		return WebClient.builder();
	}


	@GetMapping("/mono-zip")
	public Mono<?> mono() {
		return Mono.just(MultiDataResult.builder())
				.zipWith(webClientGet("/numeric").bodyToMono(NumericData.class), MultiDataResultBuilder::number)
				.zipWith(webClientGet("/time").bodyToMono(TimeData.class), MultiDataResultBuilder::time)
				.map(MultiDataResultBuilder::build);

	}

    @GetMapping("/flux-flatmap")
	public Mono<?> concatMap() {
        return Flux.fromIterable(nCopies(50, "/time"))
            .flatMap(url -> webClientGet(url).bodyToMono(TimeData.class), 10)
            .doOnEach(signal -> log.info("{}", signal))
            .collect(LinkedList::new, List::add);

    }

	@GetMapping("/flux-merge")
	public Flux<?> flux() {
		return Flux.merge(
				webClientGet("/numeric").bodyToMono(NumericData.class),
				webClientGet("/numeric").bodyToMono(NumericData.class),
				webClientGet("/400").bodyToMono(NumericData.class)
						.onErrorReturn(NumericData.ZERO),
				webClientGet("/unknown").bodyToMono(NumericData.class).onErrorResume(throwable -> Mono.empty())
		);
	}

	@GetMapping("/mono-retry-timeout")
	public Mono<?> monoTimeout() {
		return  webClientGet("/delayed")
						.bodyToMono(String.class)
						.timeout(ofSeconds(1))
						.retry(3);
	}

	private WebClient.ResponseSpec webClientGet(String path) {
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
