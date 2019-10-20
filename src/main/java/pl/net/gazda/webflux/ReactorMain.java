package pl.net.gazda.webflux;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import static java.lang.Thread.sleep;
import static java.time.Duration.ofSeconds;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;

public class ReactorMain {

    public static class ResultCombiner {
        private int intValue;
        private String stringValue;

        public void combine(Object object) {
            if (object instanceof Integer) {
                intValue = (int) object;
            } else if (object instanceof String) {
                stringValue = (String) object;
            }
        }

        public void combine(String stringValue) {
            this.stringValue = stringValue;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        var stringMono = Mono.just("String");
        var intMono = Mono.just(4);

        ResultCombiner block = Flux.merge(stringMono, intMono)
                .collect(ResultCombiner::new, ResultCombiner::combine)
                .block();

        System.out.println(block);

        List<Integer> ints = IntStream.range(1, 10).boxed().collect(toList());

        Flux.range(1, 10)
                .concatMap(i -> Mono.just(i).delayElement(ofSeconds(1)), 2)
                .doOnEach(System.out::println)
                .subscribe(System.out::println);




        sleep(10000);
    }
}
