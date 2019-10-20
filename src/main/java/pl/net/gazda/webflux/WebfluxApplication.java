package pl.net.gazda.webflux;

import com.google.common.collect.ImmutableList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.netflix.ribbon.RibbonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

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
	@ResponseBody
	public Mono<List<String>> mono() {
		return Mono.just(ImmutableList.<String>builder())
				.zipWith(webClientGetRequest("/status/200/delay/1"), ImmutableList.Builder::add)
				.zipWith(webClientGetRequest("/status/200/delay/2"), ImmutableList.Builder::add)
				.zipWith(webClientGetRequest("/status/400/delay/1"), ImmutableList.Builder::add)
				.zipWith(webClientGetRequest("/unknown"), ImmutableList.Builder::add)
				.map(ImmutableList.Builder::build);

	}

    @GetMapping("/flux-concat")
    @ResponseBody
	public Mono<?> concatMap() {
        return Flux.fromIterable(nCopies(50, "/status/200/delay/1"))
            .flatMap(this::webClientGetRequest, 10)
            .doOnEach(signal -> log.info("{}", signal))
            .reduce(String::concat);

    }

	@GetMapping("/flux-merge")
	public Flux<String> flux() {
		return Flux.merge(
				webClientGetRequest("/status/200/delay/1"),
				webClientGetRequest("/status/200/delay/2"),
				webClientGetRequest("/status/400/1"),
				webClientGetRequest("/unknown")
		);
	}

	@GetMapping("/flux-reduce")
	public Mono<String> fluxReduceToMono() {
		return Flux.merge(
				webClientGetRequest("/status/200/delay/2"),
				webClientGetRequest("/status/200/delay/1"),
				webClientGetRequest("/status/400/1"),
				webClientGetRequest("/unknown"))
			.collect(StringBuilder::new, (stringBuilder, s) -> stringBuilder.append(s).append(" \n"))
			.map(StringBuilder::toString);
	}


	private Mono<String> webClientGetRequest(String path) {
		return webClientBuilder().build()
				.get()
				.uri(uriBuilder -> uriBuilder.scheme("http").host("wiremock").path(path).build())
				.retrieve()
				.bodyToMono(String.class)
				.onErrorResume(throwable -> Mono.just(throwable.toString()));
	}
}
